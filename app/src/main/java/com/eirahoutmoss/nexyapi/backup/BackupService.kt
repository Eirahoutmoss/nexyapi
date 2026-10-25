package com.eirahoutmoss.nexyapi.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.eirahoutmoss.nexyapi.BuildConfig
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import com.eirahoutmoss.nexyapi.data.AppDatabase
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.data.ReportEntity
import com.eirahoutmoss.nexyapi.data.StructureEntity
import com.eirahoutmoss.nexyapi.data.StructureRepository
import com.eirahoutmoss.nexyapi.license.LicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ExportResult(val structures: Int, val measurements: Int, val files: Int)
data class ImportResult(
    val structuresAdded: Int,
    val structuresSkipped: Int,
    val measurementsAdded: Int,
    val reportsAdded: Int,
    val filesAdded: Int,
)

class BackupException(message: String) : IOException(message)

/**
 * Yedek paketi (.nexyapi = zip):
 *   manifest.json  biçim, sürüm, oluşturma zamanı, her dosyanın SHA-256 özeti
 *   data.json      yapılar, ölçümler, raporlar (veritabanı şemasından bağımsız düz JSON)
 *   files/...      fotoğraflar, ham veriler, PDF raporlar (uygulama klasörüne göre yollarıyla)
 *
 * İçe aktarma güvenliği:
 *  - Paket önce geçici klasöre açılır; her dosyanın özeti manifestoyla karşılaştırılır.
 *    Tek bir uyuşmazlık varsa HİÇBİR şey içe alınmaz.
 *  - Dosya yolları uygulama klasörünün dışına çıkamaz (zip slip koruması), boyut sınırı vardır.
 *  - Var olan kayıtların (aynı kimlik) üzerine yazılmaz; yalnızca eksikler eklenir.
 *  - Veritabanı yazımı tek işlemdir (transaction): yarım içe aktarma olmaz.
 */
class BackupService(
    private val context: Context,
    private val db: AppDatabase,
    private val structures: StructureRepository,
    private val licenses: LicenseManager,
) {
    private val filesRoot: File get() = context.filesDir

    // ---------------- dışa aktarma ----------------

    /** [structureId] null ise her şey; değilse yalnızca o yapı. */
    suspend fun export(target: Uri, structureId: String?): ExportResult = withContext(Dispatchers.IO) {
        val allStructures = db.structures().all().filter { structureId == null || it.id == structureId }
        if (allStructures.isEmpty()) throw BackupException("Dışa aktarılacak yapı yok.")
        val ids = allStructures.map { it.id }.toSet()
        val ms = db.measurements().all().filter { it.structureId in ids }
        val rs = db.reports().all().filter { it.structureId in ids }

        val files = LinkedHashSet<String>()
        allStructures.forEach { s -> s.photoFile?.let { files += it } }
        ms.forEach { m -> m.dataFile?.let { files += it } }
        rs.forEach { r ->
            val dir = structures.relative(structures.reportsDir(r.structureId))
            files += "$dir/${r.fileName}"
            files += "$dir/${r.fileName}.canon.txt"
        }
        val existing = files.mapNotNull { rel -> structures.resolve(rel)?.takeIf { it.isFile }?.let { rel to it } }

        val data = JSONObject()
            .put("structures", JSONArray(allStructures.map { it.toJson() }))
            .put("measurements", JSONArray(ms.map { it.toJson() }))
            .put("reports", JSONArray(rs.map { it.toJson() }))
        val dataBytes = data.toString(1).toByteArray(Charsets.UTF_8)

        val fileList = JSONArray()
        existing.forEach { (rel, f) -> fileList.put(JSONObject().put("path", rel).put("sha256", f.inputStream().use { Sha256.hex(it) }).put("size", f.length())) }
        val manifest = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("createdAt", System.currentTimeMillis())
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("scope", if (structureId == null) "all" else "structure")
            .put("dataSha256", Sha256.hex(dataBytes))
            .put("files", fileList)

        val out = context.contentResolver.openOutputStream(target, "w") ?: throw BackupException("Hedef dosya açılamadı.")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(1).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(dataBytes); zip.closeEntry()
            for ((rel, f) in existing) {
                zip.putNextEntry(ZipEntry("files/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ExportResult(allStructures.size, ms.size, existing.size)
    }

    // ---------------- içe aktarma ----------------

    suspend fun import(source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw BackupException("Dosya açılamadı.")
            unpack(input, work)

            val manifestFile = File(work, "manifest.json")
            val dataFile = File(work, "data.json")
            if (!manifestFile.isFile || !dataFile.isFile) throw BackupException("Bu bir NexYapı yedek dosyası değil.")
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optString("format") != FORMAT) throw BackupException("Bu bir NexYapı yedek dosyası değil.")
            if (manifest.optInt("version") > VERSION) throw BackupException("Yedek daha yeni bir NexYapı sürümüyle oluşturulmuş; önce uygulamayı güncelleyin.")
            if (dataFile.inputStream().use { Sha256.hex(it) } != manifest.optString("dataSha256")) throw BackupException("Yedek dosyası bozuk (veri özeti uyuşmuyor).")

            // Her dosyanın özeti
            val declared = manifest.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<Pair<String, File>>()
            for (i in 0 until declared.length()) {
                val o = declared.getJSONObject(i)
                val rel = o.getString("path")
                if (!isSafeRelative(rel)) throw BackupException("Yedekte geçersiz dosya yolu: $rel")
                val f = File(work, "files/$rel")
                if (!f.isFile) throw BackupException("Yedekte eksik dosya: $rel")
                if (f.inputStream().use { Sha256.hex(it) } != o.getString("sha256")) throw BackupException("Yedekte bozuk dosya: $rel")
                files += rel to f
            }

            val data = JSONObject(dataFile.readText())
            val ss = data.getJSONArray("structures").let { a -> (0 until a.length()).map { structureFromJson(a.getJSONObject(it)) } }
            val ms = data.getJSONArray("measurements").let { a -> (0 until a.length()).map { measurementFromJson(a.getJSONObject(it)) } }
            val rs = data.getJSONArray("reports").let { a -> (0 until a.length()).map { reportFromJson(a.getJSONObject(it)) } }

            val existingIds = db.structures().all().map { it.id }.toSet()
            val newStructures = ss.filter { it.id !in existingIds }
            val plan = licenses.state.value.plan
            if (!plan.canAddStructure(existingIds.size + newStructures.size - 1) && newStructures.isNotEmpty()) {
                throw BackupException(
                    "Ücretsiz sürümde en fazla ${plan.maxStructures} yapı olabilir; yedekte ${newStructures.size} yeni yapı var. " +
                        "Profesyonel lisansla içe aktarabilirsiniz.",
                )
            }

            var mAdded = 0; var rAdded = 0
            db.withTransaction {
                newStructures.forEach { db.structures().insert(it) }
                val knownStructures = existingIds + newStructures.map { it.id }
                for (m in ms) {
                    if (m.structureId in knownStructures && db.measurements().get(m.id) == null) { db.measurements().insert(m); mAdded++ }
                }
                for (r in rs) {
                    if (r.structureId in knownStructures && db.reports().findByFingerprint(r.fingerprint) == null) { db.reports().insert(r); rAdded++ }
                }
            }

            // Dosyalar: var olanın üzerine yazılmaz
            var fAdded = 0
            for ((rel, src) in files) {
                val dst = structures.resolve(rel) ?: continue
                if (dst.exists()) continue
                dst.parentFile?.mkdirs()
                src.copyTo(dst)
                fAdded++
            }
            ImportResult(newStructures.size, ss.size - newStructures.size, mAdded, rAdded, fAdded)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun unpack(input: InputStream, work: File) {
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                val allowed = name == "manifest.json" || name == "data.json" || (name.startsWith("files/") && isSafeRelative(name.removePrefix("files/")))
                if (!allowed) throw BackupException("Yedekte beklenmeyen içerik: $name")
                val target = File(work, name).canonicalFile
                if (!target.path.startsWith(work.canonicalPath + File.separator)) throw BackupException("Yedekte geçersiz dosya yolu.")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var entryTotal = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        entryTotal += n; total += n
                        if (entryTotal > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw BackupException("Yedek dosyası olağan dışı büyük; içe aktarılmadı.")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Uygulama klasörüne göre güvenli göreli yol: "..", mutlak yol ve beklenmeyen kök klasörler yasak. */
    private fun isSafeRelative(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/") || rel.contains('\\') || rel.contains('\u0000')) return false
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return parts.first() in setOf("data", "photos", "reports")
    }

    // ---------------- JSON eşlemeleri ----------------

    private fun StructureEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("address", address).put("floor", floor)
        .put("buildYear", buildYear ?: JSONObject.NULL).put("notes", notes)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).put("photoFile", photoFile ?: JSONObject.NULL)

    private fun structureFromJson(o: JSONObject) = StructureEntity(
        id = o.getString("id"), name = o.getString("name"), address = o.optString("address"), floor = o.optString("floor"),
        buildYear = if (o.isNull("buildYear")) null else o.getInt("buildYear"), notes = o.optString("notes"),
        createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt"),
        photoFile = o.optStringOrNull("photoFile")?.takeIf { isSafeRelative(it) },
    )

    private fun MeasurementEntity.toJson() = JSONObject()
        .put("id", id).put("structureId", structureId).put("moduleCode", moduleCode).put("takenAt", takenAt)
        .put("value", value ?: JSONObject.NULL).put("unit", unit).put("summary", summary).put("quality", quality.name)
        .put("deviceModel", deviceModel).put("deviceInfo", deviceInfo).put("dataFile", dataFile ?: JSONObject.NULL)
        .put("dataSha256", dataSha256).put("note", note).put("subject", subject ?: JSONObject.NULL).put("params", params ?: JSONObject.NULL)

    private fun measurementFromJson(o: JSONObject) = MeasurementEntity(
        id = o.getString("id"), structureId = o.getString("structureId"), moduleCode = o.getString("moduleCode"),
        takenAt = o.getLong("takenAt"), value = if (o.isNull("value")) null else o.getDouble("value"), unit = o.optString("unit"),
        summary = o.optString("summary"),
        quality = MeasurementQuality.entries.firstOrNull { it.name == o.optString("quality") } ?: MeasurementQuality.UNRELIABLE,
        deviceModel = o.optString("deviceModel"), deviceInfo = o.optString("deviceInfo", "{}"),
        dataFile = o.optStringOrNull("dataFile")?.takeIf { isSafeRelative(it) }, dataSha256 = o.getString("dataSha256"),
        note = o.optString("note"), subject = o.optStringOrNull("subject"), params = o.optStringOrNull("params"),
    )

    private fun ReportEntity.toJson() = JSONObject()
        .put("id", id).put("structureId", structureId).put("generatedAt", generatedAt).put("fingerprint", fingerprint)
        .put("fileName", fileName).put("measurementCount", measurementCount).put("appVersion", appVersion)
        .put("measurementIds", measurementIds ?: JSONObject.NULL).put("holder", holder ?: JSONObject.NULL)

    private fun reportFromJson(o: JSONObject) = ReportEntity(
        id = o.getString("id"), structureId = o.getString("structureId"), generatedAt = o.getLong("generatedAt"),
        fingerprint = o.getString("fingerprint"),
        fileName = o.getString("fileName").also { if (it.contains('/') || it.contains("..")) throw BackupException("Yedekte geçersiz rapor adı.") },
        measurementCount = o.optInt("measurementCount"), appVersion = o.optString("appVersion"),
        measurementIds = o.optStringOrNull("measurementIds"), holder = o.optStringOrNull("holder"),
    )

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)

    companion object {
        const val FORMAT = "nexyapi-backup"
        const val VERSION = 1
        const val MIME = "application/zip"
        private const val MAX_ENTRY_BYTES = 400L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    }
}

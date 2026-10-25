package com.eirahoutmoss.nexyapi.data

import com.eirahoutmoss.nexyapi.core.model.ModuleType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Ana ekran kartı: yapı + modül başına son ölçüm. */
data class StructureSummary(
    val structure: StructureEntity,
    val latest: Map<ModuleType, ModuleLatest>,
)

/** Kullanıcının girdiği alanlar; doğrulama tek yerde yapılır. */
data class StructureInput(
    val name: String,
    val address: String,
    val floor: String,
    val buildYearText: String,
    val notes: String,
) {
    sealed interface Validation {
        data class Ok(val buildYear: Int?) : Validation
        data class Error(val field: Field, val message: String) : Validation
    }

    enum class Field { NAME, BUILD_YEAR }

    fun validate(currentYear: Int): Validation {
        if (name.isBlank()) return Validation.Error(Field.NAME, "Yapı adı boş olamaz.")
        if (name.trim().length > 80) return Validation.Error(Field.NAME, "Yapı adı en fazla 80 karakter olabilir.")
        val yearText = buildYearText.trim()
        if (yearText.isEmpty()) return Validation.Ok(null)
        val y = yearText.toIntOrNull()
            ?: return Validation.Error(Field.BUILD_YEAR, "Yapım yılı sayı olmalıdır.")
        if (y < 1800 || y > currentYear) {
            return Validation.Error(Field.BUILD_YEAR, "Yapım yılı 1800 ile $currentYear arasında olmalıdır.")
        }
        return Validation.Ok(y)
    }
}

class StructureRepository(private val db: AppDatabase, private val filesRoot: File) {

    fun observeSummaries(): Flow<List<StructureSummary>> =
        combine(db.structures().observeAll(), db.measurements().observeLatestPerModule()) { structures, latest ->
            val byStructure = latest.groupBy { it.structureId }
            structures.map { s ->
                val map = byStructure[s.id].orEmpty()
                    .mapNotNull { l -> ModuleType.fromCode(l.moduleCode)?.let { it to l } }
                    .toMap()
                StructureSummary(s, map)
            }
        }

    fun observeSummary(id: String): Flow<StructureSummary?> =
        observeSummaries().map { list -> list.firstOrNull { it.structure.id == id } }

    fun observeReports(structureId: String): Flow<List<ReportEntity>> =
        db.reports().observeForStructure(structureId)

    suspend fun get(id: String): StructureEntity? = db.structures().get(id)

    suspend fun count(): Int = db.structures().count()

    suspend fun create(input: StructureInput, buildYear: Int?, now: Long): String {
        val id = UUID.randomUUID().toString()
        db.structures().insert(
            StructureEntity(
                id = id,
                name = input.name.trim(),
                address = input.address.trim(),
                floor = input.floor.trim(),
                buildYear = buildYear,
                notes = input.notes.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun update(existing: StructureEntity, input: StructureInput, buildYear: Int?, now: Long) {
        db.structures().update(
            existing.copy(
                name = input.name.trim(),
                address = input.address.trim(),
                floor = input.floor.trim(),
                buildYear = buildYear,
                notes = input.notes.trim(),
                updatedAt = now,
            ),
        )
    }

    /** Yapıyı, ölçümlerini (CASCADE), ham veri dosyalarını, fotoğrafını ve raporlarını siler. Geri alınamaz. */
    suspend fun delete(id: String) {
        val photo = db.structures().get(id)?.photoFile
        db.structures().delete(id)
        withContext(Dispatchers.IO) {
            reportsDir(id).deleteRecursively()
            dataDir(id).deleteRecursively()
            photo?.let { resolve(it)?.delete() }
        }
    }

    /** Kapak fotoğrafını değiştirir; eskisini siler. [relativePath] null ise fotoğraf kaldırılır. */
    suspend fun setPhoto(id: String, relativePath: String?) {
        val old = db.structures().get(id)?.photoFile
        db.structures().setPhoto(id, relativePath, System.currentTimeMillis())
        if (old != null && old != relativePath) withContext(Dispatchers.IO) { resolve(old)?.delete() }
    }

    fun photosDir(): File = File(filesRoot, "photos")

    // ---------- ölçümler ----------

    fun observeModule(structureId: String, module: ModuleType): Flow<List<MeasurementEntity>> =
        db.measurements().observeForModule(structureId, module.code)

    suspend fun subjects(structureId: String, module: ModuleType): List<String> =
        db.measurements().subjects(structureId, module.code)

    suspend fun saveMeasurement(m: MeasurementEntity) = db.measurements().insert(m)

    suspend fun getMeasurement(id: String): MeasurementEntity? = db.measurements().get(id)

    /** Ölçümü ve ham veri dosyasını siler. */
    suspend fun deleteMeasurement(id: String) {
        val m = db.measurements().get(id) ?: return
        db.measurements().delete(id)
        m.dataFile?.let { rel -> withContext(Dispatchers.IO) { resolve(rel)?.delete() } }
    }

    /** Uygulama klasörüne göre yolu dosyaya çevirir; klasör dışına çıkan yolu reddeder. */
    fun resolve(relativePath: String): File? {
        val f = File(filesRoot, relativePath).canonicalFile
        return if (f.path.startsWith(filesRoot.canonicalPath + File.separator)) f else null
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(filesRoot.canonicalFile).path.replace(File.separatorChar, '/')

    fun reportsDir(structureId: String): File = File(filesRoot, "reports/${safe(structureId)}")

    fun dataDir(structureId: String): File = File(filesRoot, "data/${safe(structureId)}")

    // Kimlik her zaman UUID'dir; yine de yol dışına çıkılmasın.
    private fun safe(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' }

    suspend fun measurements(structureId: String): List<MeasurementEntity> =
        db.measurements().listForStructure(structureId)

    suspend fun saveReport(r: ReportEntity) = db.reports().insert(r)
}

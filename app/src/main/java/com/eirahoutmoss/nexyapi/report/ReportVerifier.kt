package com.eirahoutmoss.nexyapi.report

import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import com.eirahoutmoss.nexyapi.data.AppDatabase
import com.eirahoutmoss.nexyapi.data.ReportEntity
import com.eirahoutmoss.nexyapi.data.StructureRepository
import com.eirahoutmoss.nexyapi.sensors.RawDataWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VerifyResult {
    data class BadInput(val message: String) : VerifyResult
    data object NotFound : VerifyResult
    data class Ambiguous(val matches: List<ReportEntity>) : VerifyResult

    data class Checked(
        val report: ReportEntity,
        val structureName: String?,
        /** Raporun verileri bugün de aynı mı? null: eski rapor, ayrıntılı denetim yapılamadı. */
        val dataIntact: Boolean?,
        val changes: List<String>,
        /** Ham veri dosyaları: kaç tanesi denetlendi, hangileri bozuk/eksik. */
        val rawChecked: Int,
        val rawProblems: List<String>,
    ) : VerifyResult
}

/**
 * Rapor doğrulama. Rapordaki parmak izi bu cihazdaki (ya da yedekten alınmış) kayıtla eşleştirilir;
 * raporun üretildiği andaki veri kümesi bugünkü kayıtlardan yeniden hesaplanır. Ayrıca her ölçümün
 * ham veri dosyasının özeti yeniden alınıp kayıttaki özetle karşılaştırılır.
 */
class ReportVerifier(private val db: AppDatabase, private val structures: StructureRepository) {

    suspend fun verify(input: String): VerifyResult = withContext(Dispatchers.IO) {
        val hex = input.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.any { it !in "0123456789abcdef" }) return@withContext VerifyResult.BadInput("Parmak izi yalnızca 0–9 ve A–F karakterlerinden oluşur.")
        if (hex.length < 12) return@withContext VerifyResult.BadInput("En az ilk 12 karakteri girin (raporun altındaki ilk üç grup).")
        if (hex.length > 64) return@withContext VerifyResult.BadInput("Parmak izi 64 karakterden uzun olamaz.")

        val report = if (hex.length == 64) {
            db.reports().findByFingerprint(hex) ?: return@withContext VerifyResult.NotFound
        } else {
            val list = db.reports().findByPrefix(hex)
            when (list.size) {
                0 -> return@withContext VerifyResult.NotFound
                1 -> list.single()
                else -> return@withContext VerifyResult.Ambiguous(list)
            }
        }
        val structure = structures.get(report.structureId)

        val ids = report.measurementIds
        val holder = report.holder
        if (ids == null || holder == null || structure == null) {
            return@withContext VerifyResult.Checked(report, structure?.name, null, emptyList(), 0, emptyList())
        }

        val idList = ids.split(',').filter { it.isNotBlank() }
        val current = idList.mapNotNull { db.measurements().get(it) }
        val currentCanonical = ReportCanonical.build(structure, current, holder, report.appVersion, report.generatedAt)
        val intact = Sha256.hex(currentCanonical) == report.fingerprint

        val changes = if (intact) emptyList() else {
            val sidecar = File(structures.reportsDir(report.structureId), report.fileName + ".canon.txt")
            if (sidecar.exists()) {
                ReportFingerprint.diff(sidecar.readText(), currentCanonical).ifEmpty { listOf("Veriler değişmiş (ayrıntı belirlenemedi).") }
            } else {
                val missing = idList.size - current.size
                listOfNotNull(
                    if (missing > 0) "$missing ölçüm rapordan sonra silinmiş." else null,
                    "Rapordaki veriler bugünkü kayıtlarla uyuşmuyor.",
                )
            }
        }

        // Ham veri bütünlüğü
        val problems = ArrayList<String>()
        var checked = 0
        for (m in current) {
            val rel = m.dataFile ?: continue
            val f = structures.resolve(rel)
            checked++
            if (f == null || !f.exists()) { problems += "${m.moduleCode} (${m.id.take(8)}): ham veri dosyası yok."; continue }
            if (RawDataWriter.sha256(f) != m.dataSha256) problems += "${m.moduleCode} (${m.id.take(8)}): ham veri dosyası değiştirilmiş."
        }
        VerifyResult.Checked(report, structure.name, intact, changes, checked, problems)
    }
}

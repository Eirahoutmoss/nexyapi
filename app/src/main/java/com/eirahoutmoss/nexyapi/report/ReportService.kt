package com.eirahoutmoss.nexyapi.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.eirahoutmoss.nexyapi.BuildConfig
import com.eirahoutmoss.nexyapi.core.model.Plan
import com.eirahoutmoss.nexyapi.data.ReportEntity
import com.eirahoutmoss.nexyapi.data.StructureRepository
import com.eirahoutmoss.nexyapi.license.LicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class ReportService(
    private val context: Context,
    private val repository: StructureRepository,
    private val licenses: LicenseManager,
    private val images: com.eirahoutmoss.nexyapi.media.ImageStore,
    private val branding: BrandingStore,
) {
    private val generator = PdfReportGenerator()

    /** Raporu üretir, kaydını veritabanına yazar ve dosyayı döndürür. */
    suspend fun create(structureId: String): GeneratedReport = withContext(Dispatchers.IO) {
        val structure = repository.get(structureId) ?: error("Yapı bulunamadı.")
        val measurements = repository.measurements(structureId)
        val lic = licenses.state.value
        val now = System.currentTimeMillis()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        val file = File(repository.reportsDir(structureId), "NexYapi_${slug(structure.name)}_$stamp.pdf")

        val photo = structure.photoFile?.let { repository.resolve(it) }?.takeIf { it.exists() }
            ?.let { runCatching { images.loadPreview(it, 1200) }.getOrNull() }
        val result = generator.generate(
            structure = structure,
            measurements = measurements,
            holder = lic.holderLabel,
            isFreePlan = lic.plan == Plan.FREE,
            appVersion = BuildConfig.VERSION_NAME,
            generatedAt = now,
            outFile = file,
            coverPhoto = photo,
            branding = if (lic.plan == Plan.PRO) branding.forReport(lic.holderLabel) else null,
        )
        photo?.recycle()
        // Doğrulamada "neyin değiştiğini" gösterebilmek için kanonik metin raporun yanına yazılır.
        File(file.parentFile, file.name + ".canon.txt").writeText(result.canonical)
        repository.saveReport(
            ReportEntity(
                id = UUID.randomUUID().toString(),
                structureId = structureId,
                generatedAt = now,
                fingerprint = result.fingerprint,
                fileName = file.name,
                measurementCount = measurements.size,
                appVersion = BuildConfig.VERSION_NAME,
                measurementIds = measurements.joinToString(",") { it.id },
                holder = lic.holderLabel,
            ),
        )
        result
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Raporu paylaş").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Dosya adı için güvenli metin: "Evim — Kat 3" -> "Evim_Kat_3". */
    private fun slug(s: String): String {
        val tr = s.replace('ı', 'i').replace('İ', 'I')
        val ascii = Normalizer.normalize(tr, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifEmpty { "yapi" }
    }
}

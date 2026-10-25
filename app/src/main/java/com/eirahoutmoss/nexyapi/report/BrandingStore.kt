package com.eirahoutmoss.nexyapi.report

import android.content.Context
import android.net.Uri
import com.eirahoutmoss.nexyapi.media.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pro rapor için büro bilgileri. Yalnızca cihazda saklanır. */
data class BrandingSettings(
    val officeName: String,
    val preparedBy: String,
    val preparedByTitle: String,
    val contact: String,
    val hasLogo: Boolean,
)

class BrandingStore(private val context: Context, private val images: ImageStore) {
    private val prefs = context.getSharedPreferences("branding", Context.MODE_PRIVATE)
    val logoFile: File get() = File(context.filesDir, "branding/logo.jpg")

    fun load(): BrandingSettings = BrandingSettings(
        officeName = prefs.getString("office", "").orEmpty(),
        preparedBy = prefs.getString("by", "").orEmpty(),
        preparedByTitle = prefs.getString("title", "").orEmpty(),
        contact = prefs.getString("contact", "").orEmpty(),
        hasLogo = logoFile.exists(),
    )

    fun save(s: BrandingSettings) {
        prefs.edit()
            .putString("office", s.officeName.trim().take(80))
            .putString("by", s.preparedBy.trim().take(80))
            .putString("title", s.preparedByTitle.trim().take(80))
            .putString("contact", s.contact.trim().take(160))
            .apply()
    }

    /** Logoyu içe alır (en uzun kenar 600 px). Raporda beyaz bir kutuya çizildiği için JPEG yeterlidir. */
    suspend fun setLogo(uri: Uri) {
        val tmp = File(context.cacheDir, "logo_import.jpg")
        images.importImage(uri, tmp, maxDimension = 600, quality = 95)
        withContext(Dispatchers.IO) {
            logoFile.parentFile?.mkdirs()
            tmp.copyTo(logoFile, overwrite = true)
            tmp.delete()
        }
    }

    fun removeLogo() { logoFile.delete() }

    /**
     * Rapor için büro bilgisi. Büro adı boşsa [fallbackHolder] (lisans sahibi) kullanılır.
     * Hiçbir alan doldurulmamış ve logo yoksa null döner (standart NexYapı başlığı).
     */
    suspend fun forReport(fallbackHolder: String): ReportBranding? = withContext(Dispatchers.IO) {
        val s = load()
        if (s.officeName.isBlank() && s.preparedBy.isBlank() && !s.hasLogo) return@withContext null
        val logo = if (s.hasLogo) runCatching { images.loadPreview(logoFile, 600) }.getOrNull() else null
        ReportBranding(
            officeName = s.officeName.ifBlank { fallbackHolder },
            preparedBy = s.preparedBy,
            preparedByTitle = s.preparedByTitle,
            contact = s.contact,
            logo = logo,
        )
    }
}

package com.eirahoutmoss.nexyapi.core.model

/** Sürüm planı ve sınırları. Sınırlar tek yerde tanımlıdır. */
enum class Plan(val code: String, val displayName: String, val maxStructures: Int?) {
    FREE("free", "Ücretsiz", maxStructures = 1),
    PRO("pro", "Profesyonel", maxStructures = null);

    fun canAddStructure(currentCount: Int): Boolean =
        maxStructures == null || currentCount < maxStructures

    companion object {
        fun fromCode(code: String): Plan? = entries.firstOrNull { it.code == code }
    }
}

/** Her yerde aynı kelimelerle geçmesi gereken, değiştirilemez sorumluluk metni. */
object Disclaimer {
    const val SHORT = "Bu uygulama ölçüm yapar, güvenlik değerlendirmesi yapmaz."
    const val REPORT =
        "Bu belge bir ölçüm kaydıdır; yapı güvenliğine ilişkin bir değerlendirme değildir. " +
            "Sonuçların değerlendirmesi yetkili bir inşaat mühendisi tarafından yapılmalıdır."
}

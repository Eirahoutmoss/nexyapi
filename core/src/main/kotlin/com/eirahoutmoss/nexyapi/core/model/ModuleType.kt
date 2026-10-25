package com.eirahoutmoss.nexyapi.core.model

/**
 * Yapı Karnesi'ndeki ölçüm modülleri.
 *
 * [code] veritabanına yazılır; bu yüzden bir kez yayımlandıktan sonra DEĞİŞTİRİLMEMELİDİR.
 * Yeni modül eklenebilir, var olanın kodu yeniden adlandırılamaz.
 */
enum class ModuleType(
    val code: String,
    val title: String,
    val unit: String,
    val requiresAccessory: Boolean,
    /** Arayüzde ve raporda gösterilen dürüst sınır notu; yoksa null. */
    val limitationNote: String?,
) {
    VIBRATION(
        code = "Z2",
        title = "Titreşim Parmak İzi",
        unit = "Hz",
        requiresAccessory = false,
        limitationNote = "Ölçüm kalitesi telefonun ivmeölçerine bağlıdır.",
    ),
    CRACK(
        code = "V3",
        title = "Çatlak İzleyici",
        unit = "mm",
        requiresAccessory = false,
        limitationNote = "Basılı referans etiket olmadan ölçüm yapılmaz.",
    ),
    MOTION(
        code = "V11",
        title = "Görünmez Hareket",
        unit = "Hz",
        requiresAccessory = false,
        limitationNote = "Telefon sabit durmalıdır (tripod ya da bir yere dayalı); ışık titremesi sonucu etkileyebilir.",
    ),
    BEFORE_DRILL(
        code = "W1",
        title = "Matkaptan Önce",
        unit = "µT",
        requiresAccessory = false,
        limitationNote = "Demir ve boruyu iyi bulur; içinden akım geçmeyen kabloyu bulmakta zorlanır.",
    ),
    ACOUSTICS(
        code = "W3",
        title = "Oda Akustiği",
        unit = "sn",
        requiresAccessory = false,
        limitationNote = "Telefon hoparlörü 125 Hz altında yeterli ses vermez; bas için harici hoparlör gerekir.",
    ),
    HEAT_LEAK(
        code = "V12",
        title = "Isı Kaçağı",
        unit = "°C",
        requiresAccessory = true,
        limitationNote = "Harici termal sensör (ek cihaz) olmadan çalışmaz.",
    );

    companion object {
        fun fromCode(code: String): ModuleType? = entries.firstOrNull { it.code == code }
    }
}

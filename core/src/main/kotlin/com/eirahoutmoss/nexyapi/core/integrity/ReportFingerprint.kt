package com.eirahoutmoss.nexyapi.core.integrity

/**
 * Rapor parmak izi.
 *
 * Raporun içeriği (yapı bilgisi + ölçümler + üretim bilgisi) sabit, belirlenimci bir metne
 * dönüştürülür ve SHA-256 özeti alınır. Aynı veri her zaman aynı özeti verir; tek bir rakam
 * değişirse özet tamamen değişir. Böylece "bu rapor sonradan değiştirildi mi?" sorusu,
 * uygulamadaki kayıtlardan raporu yeniden üretip özetleri karşılaştırarak cevaplanır.
 *
 * Biçim sürümlüdür ([FORMAT]); biçim değişirse sürüm artırılır, eski raporlar eski kuralla doğrulanır.
 */
object ReportFingerprint {

    const val FORMAT = "NXY-RPT-1"

    data class StructurePart(
        val id: String,
        val name: String,
        val address: String,
        val floor: String,
        val buildYear: Int?,
    )

    data class MeasurementPart(
        val id: String,
        val moduleCode: String,
        val takenAtEpochMs: Long,
        val value: Double?,
        val unit: String,
        val deviceModel: String,
        val dataSha256: String,
        /** Ölçümün konusu: ölçüm yeri ya da çatlağın adı. */
        val subject: String = "",
    )

    data class GenerationPart(
        val appVersion: String,
        val generatedAtEpochMs: Long,
        val licenseHolder: String,
    )

    fun canonical(
        structure: StructurePart,
        measurements: List<MeasurementPart>,
        generation: GenerationPart,
    ): String = buildString {
        line("format", FORMAT)
        line("app", generation.appVersion)
        line("generated", generation.generatedAtEpochMs.toString())
        line("holder", generation.licenseHolder)
        line("s.id", structure.id)
        line("s.name", structure.name)
        line("s.address", structure.address)
        line("s.floor", structure.floor)
        line("s.year", structure.buildYear?.toString() ?: "")
        // Sıra veritabanına değil veriye bağlı olsun: önce zaman, sonra kimlik.
        measurements.sortedWith(compareBy({ it.takenAtEpochMs }, { it.id })).forEachIndexed { i, m ->
            line("m$i.id", m.id)
            line("m$i.module", m.moduleCode)
            line("m$i.at", m.takenAtEpochMs.toString())
            line("m$i.value", m.value?.let { formatNumber(it) } ?: "")
            line("m$i.unit", m.unit)
            line("m$i.device", m.deviceModel)
            line("m$i.data", m.dataSha256)
            line("m$i.subject", m.subject)
        }
    }

    fun compute(
        structure: StructurePart,
        measurements: List<MeasurementPart>,
        generation: GenerationPart,
    ): String = Sha256.hex(canonical(structure, measurements, generation))

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(escape(value)).append('\n')
    }

    /** Satır sonu ve ters eğik çizgi kaçışlanır; böylece bir alan başka bir alanı taklit edemez. */
    internal fun escape(v: String): String =
        v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    /** Yerel ayardan bağımsız, sabit sayı biçimi (virgül/nokta karışıklığı olmasın). */
    internal fun formatNumber(d: Double): String =
        if (d.isNaN() || d.isInfinite()) "" else java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()

    /** Kanonik metni anahtar → değer eşlemesine çevirir. */
    fun parse(canonical: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in canonical.split('\n')) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return out
    }

    /**
     * Raporun üretildiği andaki kanonik metin ile bugünkü veriden üretilen metni karşılaştırır ve
     * insanın okuyabileceği değişiklik listesi döndürür. Ölçümler kimlikleriyle eşleştirilir.
     */
    fun diff(original: String, current: String): List<String> {
        val a = parse(original); val b = parse(current)
        val out = ArrayList<String>()
        val labels = mapOf("s.name" to "Yapı adı", "s.address" to "Adres", "s.floor" to "Kat", "s.year" to "Yapım yılı")
        for ((k, label) in labels) {
            if (a[k] != b[k]) out += "$label değişmiş: \"${unescape(a[k])}\" → \"${unescape(b[k])}\""
        }
        fun measurements(m: Map<String, String>): Map<String, Map<String, String>> {
            val byIndex = HashMap<String, HashMap<String, String>>()
            for ((k, v) in m) {
                if (!k.startsWith("m")) continue
                val dot = k.indexOf('.')
                if (dot < 0) continue
                byIndex.getOrPut(k.substring(0, dot)) { HashMap() }[k.substring(dot + 1)] = v
            }
            return byIndex.values.associateBy { it["id"] ?: "" }
        }
        val ma = measurements(a); val mb = measurements(b)
        val fieldLabels = mapOf("value" to "değer", "at" to "ölçüm zamanı", "data" to "ham veri", "unit" to "birim",
            "device" to "cihaz", "subject" to "konu", "module" to "modül")
        for ((id, fa) in ma) {
            val fb = mb[id]
            val title = fa["module"]?.let { com.eirahoutmoss.nexyapi.core.model.ModuleType.fromCode(it)?.title } ?: "Bir"
            val name = "$title ölçümü (${fa["at"]?.toLongOrNull()?.let { formatTime(it) } ?: id.take(8)})"
            if (fb == null) { out += "$name silinmiş."; continue }
            for ((f, label) in fieldLabels) if (fa[f] != fb[f]) out += "$name: $label değişmiş."
        }
        return out
    }

    private fun formatTime(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm")
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

    private fun unescape(v: String?): String =
        (v ?: "").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
}

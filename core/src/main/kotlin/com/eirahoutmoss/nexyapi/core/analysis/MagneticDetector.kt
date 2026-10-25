package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Duvardaki demir / çelik boru / çivi gibi ferromanyetik cisimleri telefon manyetometresiyle bulur.
 *
 * Yöntem: boş sayılan bir noktada ortam alanının vektörü (dünya manyetik alanı + binanın genel etkisi)
 * kalibre edilir. Tarama sırasında ölçülen vektör ile bu taban vektör arasındaki farkın büyüklüğü
 * "sapma"dır. Yalnızca büyüklük değil vektör farkı kullanılır: cisim alanın yönünü de bozar.
 *
 * Sınırlar (kullanıcıya da gösterilir):
 *  - Bakır, PEX, PVC borular ve akım geçmeyen kablolar manyetik değildir; bulunamaz.
 *  - Telefon döndürülürse taban vektör geçersizleşir; tarama sırasında telefon aynı yönde tutulmalıdır.
 *  - Sinyal mesafeyle hızla düşer; telefon duvara değmelidir.
 */
class MagneticDetector(
    /** Yumuşatma katsayısı (0..1): küçük değer daha sakin, daha yavaş gösterge. */
    private val smoothing: Double = 0.25,
) {
    data class Baseline(val x: Double, val y: Double, val z: Double, val noise: Double) {
        val magnitude: Double get() = sqrt(x * x + y * y + z * z)
    }

    data class Reading(
        /** Yumuşatılmış sapma (µT). */
        val anomaly: Double,
        /** 0..1 arası gösterge değeri (eşiğe ve tavana göre ölçekli). */
        val level: Double,
        /** Sapma eşiği aştı mı? */
        val detected: Boolean,
        /** Bu taramadaki en yüksek sapma (µT). */
        val peak: Double,
        /** Sapma bir tepeyi geçip düşmeye başladı mı (kullanıcı tepenin üzerinden geçti). */
        val passedPeak: Boolean,
    )

    sealed interface Calibration {
        data class Ok(val baseline: Baseline) : Calibration
        data class Error(val message: String) : Calibration
    }

    var baseline: Baseline? = null
        private set

    private var smoothed = 0.0
    private var peak = 0.0
    private var rising = false

    /** En az ~1 saniyelik örnekle taban alanı hesaplar. Telefon hareket ettiyse reddeder. */
    fun calibrate(samples: List<DoubleArray>): Calibration {
        if (samples.size < 20) return Calibration.Error("Kalibrasyon için yeterli örnek yok; telefonu 2 saniye kıpırdatmadan tutun.")
        val mx = samples.sumOf { it[0] } / samples.size
        val my = samples.sumOf { it[1] } / samples.size
        val mz = samples.sumOf { it[2] } / samples.size
        var ss = 0.0
        for (s in samples) {
            val dx = s[0] - mx; val dy = s[1] - my; val dz = s[2] - mz
            ss += dx * dx + dy * dy + dz * dz
        }
        val noise = sqrt(ss / samples.size)
        val mag = sqrt(mx * mx + my * my + mz * mz)
        if (noise > 3.0) return Calibration.Error("Kalibrasyon sırasında alan çok oynadı; telefonu sabit tutup tekrarlayın.")
        if (mag < 15 || mag > 120) {
            return Calibration.Error("Ortam alanı olağan dışı (${mag.toInt()} µT). Büyük bir metal ya da mıknatıstan uzak, boş bir noktada kalibre edin.")
        }
        val b = Baseline(mx, my, mz, noise)
        baseline = b
        reset()
        return Calibration.Ok(b)
    }

    fun reset() {
        smoothed = 0.0; peak = 0.0; rising = false
    }

    /** Eşik: gürültünün 6 katı ama en az 2 µT. */
    val threshold: Double get() = max(2.0, (baseline?.noise ?: 0.5) * 6)

    /** Gösterge tavanı (µT): bunun üstü tam dolu gösterilir. */
    val ceiling: Double = 60.0

    fun update(x: Double, y: Double, z: Double): Reading? {
        val b = baseline ?: return null
        val dx = x - b.x; val dy = y - b.y; val dz = z - b.z
        val raw = sqrt(dx * dx + dy * dy + dz * dz)
        val prev = smoothed
        smoothed += smoothing * (raw - smoothed)
        var passed = false
        if (smoothed > prev + 0.05) rising = true
        if (rising && smoothed < prev - 0.05 && prev >= threshold && prev >= peak * 0.9) {
            passed = true
            rising = false
        }
        peak = max(peak, smoothed)
        val level = ((smoothed - threshold * 0.5) / (ceiling - threshold * 0.5)).coerceIn(0.0, 1.0)
        return Reading(smoothed, level, smoothed >= threshold, peak, passed)
    }
}

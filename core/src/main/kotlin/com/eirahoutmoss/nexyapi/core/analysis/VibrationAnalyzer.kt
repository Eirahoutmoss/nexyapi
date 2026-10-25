package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ölçüm kalitesi (veritabanındaki MeasurementQuality ile aynı adlar). */
enum class Quality { OK, LOW, UNRELIABLE }

/** Ham ivmeölçer kaydı. Zaman: sensör zaman damgası (ns, monoton). İvme: m/s². */
class AccelRecording(val tNs: LongArray, val x: FloatArray, val y: FloatArray, val z: FloatArray) {
    init {
        require(tNs.size == x.size && x.size == y.size && y.size == z.size) { "Eksen uzunlukları farklı." }
    }
    val size: Int get() = tNs.size
}

data class SamplingStats(
    val count: Int,
    val durationS: Double,
    /** Ortanca örnek aralığından hesaplanan hız (Hz). */
    val rateHz: Double,
    /** Örnek aralıklarının değişkenlik katsayısı (std / ortalama). */
    val jitter: Double,
    val maxGapMs: Double,
)

data class SpectrumPeak(val frequencyHz: Double, val prominence: Double)

data class VibrationResult(
    /** Bant içindeki en baskın frekans (Hz); bulunamadıysa null. */
    val dominantHz: Double?,
    val prominence: Double,
    /** Baskın tepe dışındaki belirgin tepeler (bilgi amaçlı). */
    val otherPeaks: List<SpectrumPeak>,
    val quality: Quality,
    /** Kalitenin gerekçeleri (Türkçe, kullanıcıya gösterilir). */
    val notes: List<String>,
    val sampling: SamplingStats,
    val analysisRateHz: Double,
    val frequencyResolutionHz: Double,
    /** Grafik için: bant içindeki frekanslar ve yatay eksenlerin toplam gücü. */
    val spectrumFreqs: DoubleArray,
    val spectrumPower: DoubleArray,
)

/**
 * Binanın baskın (doğal) titreşim frekansını bulur.
 *
 * Yöntem: telefon zemine yatay konur; yatay eksenler (x, y) bina salınımını taşır.
 *  1. Baştaki ve sondaki birkaç saniye atılır (dokunma titreşimi).
 *  2. Düzensiz zaman damgaları sabit aralığa doğrusal aradeğerlemeyle getirilir.
 *  3. Alçak geçiren süzgeçle ~50 Hz'e indirilir (yüksek frekanslı fan/motor titreşimi
 *     bant içine "katlanmasın" diye — aliasing).
 *  4. Welch yöntemiyle güç spektrumu; x ve y toplanır.
 *  5. Bant (varsayılan 0,5–15 Hz) içindeki en güçlü tepe, parabolik aradeğerlemeyle inceltilir.
 *
 * Bu bir ölçümdür; sonuç hiçbir zaman güvenlik değerlendirmesi olarak sunulmaz.
 */
object VibrationAnalyzer {

    data class Config(
        val trimStartS: Double = 3.0,
        val trimEndS: Double = 2.0,
        val bandMinHz: Double = 0.5,
        val bandMaxHz: Double = 15.0,
        val targetRateHz: Double = 50.0,
        val minRateHz: Double = 60.0,
        val maxGapMs: Double = 250.0,
        val minAnalysedSeconds: Double = 30.0,
        val okProminence: Double = 6.0,
        val lowProminence: Double = 3.0,
    )

    fun samplingStats(tNs: LongArray): SamplingStats {
        if (tNs.size < 2) return SamplingStats(tNs.size, 0.0, 0.0, Double.NaN, Double.NaN)
        val dts = DoubleArray(tNs.size - 1) { (tNs[it + 1] - tNs[it]) / 1e6 } // ms
        val positive = dts.filter { it > 0 }.toDoubleArray()
        val med = if (positive.isEmpty()) Double.NaN else Dsp.median(positive)
        val mean = positive.average()
        var v = 0.0
        for (d in positive) v += (d - mean) * (d - mean)
        val std = if (positive.size > 1) sqrt(v / (positive.size - 1)) else 0.0
        return SamplingStats(
            count = tNs.size,
            durationS = (tNs.last() - tNs.first()) / 1e9,
            rateHz = if (med > 0) 1000.0 / med else 0.0,
            jitter = if (mean > 0) std / mean else Double.NaN,
            maxGapMs = dts.maxOrNull() ?: 0.0,
        )
    }

    fun analyze(rec: AccelRecording, cfg: Config = Config()): VibrationResult {
        val notes = ArrayList<String>()
        val stats = samplingStats(rec.tNs)
        val empty = { q: Quality ->
            VibrationResult(null, 0.0, emptyList(), q, notes, stats, 0.0, 0.0, DoubleArray(0), DoubleArray(0))
        }

        if (rec.size < 100 || stats.rateHz <= 0) {
            notes += "Yeterli sensör verisi alınamadı."
            return empty(Quality.UNRELIABLE)
        }
        if (stats.rateHz < cfg.minRateHz) {
            notes += "Cihazın ivmeölçeri saniyede ${stats.rateHz.roundToInt()} örnek veriyor; en az ${cfg.minRateHz.roundToInt()} gerekir."
        }

        // 1) Kırpma ve tek düze zaman ekseni
        val t0 = rec.tNs.first() + (cfg.trimStartS * 1e9).toLong()
        val t1 = rec.tNs.last() - (cfg.trimEndS * 1e9).toLong()
        if (t1 - t0 < cfg.minAnalysedSeconds * 1e9) {
            notes += "Kayıt çok kısa; en az ${cfg.minAnalysedSeconds.roundToInt()} saniyelik temiz veri gerekir."
            return empty(Quality.UNRELIABLE)
        }
        // Kırpılmış bölgedeki en büyük boşluk
        var gapMs = 0.0
        for (i in 1 until rec.size) {
            if (rec.tNs[i] in t0..t1) gapMs = max(gapMs, (rec.tNs[i] - rec.tNs[i - 1]) / 1e6)
        }

        val highRate = min(floor(stats.rateHz), 500.0)
        val factor = max(1, floor(highRate / cfg.targetRateHz).toInt())
        val fsHigh = highRate
        val nHigh = floor((t1 - t0) / 1e9 * fsHigh).toInt()
        val ux = resample(rec.tNs, rec.x, t0, fsHigh, nHigh)
        val uy = resample(rec.tNs, rec.y, t0, fsHigh, nHigh)

        // 2) Süzgeç + seyreltme
        val dx = Dsp.decimate(ux, factor)
        val dy = Dsp.decimate(uy, factor)
        val fs = fsHigh / factor
        if (fs / 2 <= cfg.bandMaxHz) {
            notes += "Örnekleme hızı ${cfg.bandMaxHz} Hz'e kadar ölçmeye yetmiyor."
            return empty(Quality.UNRELIABLE)
        }

        // 3) Welch
        val n = dx.size
        var nseg = 1024
        while (nseg > 256 && nseg * 2 > n) nseg /= 2
        if (nseg > n) {
            notes += "Analiz için yeterli veri yok."
            return empty(Quality.UNRELIABLE)
        }
        val wx = Dsp.welch(dx, fs, nseg)
        val wy = Dsp.welch(dy, fs, nseg)
        val power = DoubleArray(wx.psd.size) { wx.psd[it] + wy.psd[it] }
        val df = fs / nseg

        val lo = max(1, (cfg.bandMinHz / df).toInt())
        val hi = min(power.size - 2, (cfg.bandMaxHz / df).toInt())
        val bandFreqs = DoubleArray(hi - lo + 1) { wx.freqs[lo + it] }
        val bandPower = DoubleArray(hi - lo + 1) { power[lo + it] }
        val floor = Dsp.median(bandPower).takeIf { it > 0 } ?: Double.MIN_VALUE

        // 4) Tepeler
        val peaks = localPeaks(power, lo, hi)
            .sortedByDescending { power[it] }
        if (peaks.isEmpty()) {
            notes += "Spektrumda belirgin bir tepe bulunamadı."
            return VibrationResult(null, 0.0, emptyList(), Quality.UNRELIABLE, notes, stats, fs, df, bandFreqs, bandPower)
        }
        val chosen = ArrayList<Int>()
        for (p in peaks) {
            if (chosen.none { abs(it - p) * df < 0.2 }) chosen += p
            if (chosen.size == 4) break
        }
        val main = chosen.first()
        val dominant = refine(power, main) * df
        val prominence = power[main] / floor
        val others = chosen.drop(1)
            .map { SpectrumPeak(refine(power, it) * df, power[it] / floor) }
            .filter { it.prominence >= cfg.lowProminence }

        // 5) Kalite
        var quality = Quality.OK
        fun lower(to: Quality) { if (to.ordinal > quality.ordinal) quality = to }

        if (stats.rateHz < cfg.minRateHz) lower(Quality.UNRELIABLE)
        if (gapMs > cfg.maxGapMs) {
            notes += "Kayıtta ${gapMs.roundToInt()} ms'lik veri boşluğu var (ekran kapanmış ya da uygulama arka plana geçmiş olabilir)."
            lower(Quality.UNRELIABLE)
        }
        when {
            prominence < cfg.lowProminence -> {
                notes += "Baskın frekans gürültüden yeterince ayrışmıyor."
                lower(Quality.UNRELIABLE)
            }
            prominence < cfg.okProminence -> {
                notes += "Baskın frekans zayıf belirgin; ölçümü daha sessiz bir zamanda tekrarlamak önerilir."
                lower(Quality.LOW)
            }
        }
        val segRms = (wx.segmentRms.zip(wy.segmentRms) { a, b -> sqrt(a * a + b * b) }).toDoubleArray()
        if (segRms.size >= 3) {
            val med = Dsp.median(segRms)
            if (med > 0 && segRms.max() > 5 * med) {
                notes += "Ölçüm sırasında ani bir sarsıntı algılandı (yürüme, kapı, darbe)."
                lower(Quality.LOW)
            }
        }
        if (others.any { it.prominence > prominence * 0.8 }) {
            notes += "Birbirine yakın güçte birden fazla tepe var; baskın frekans belirsiz olabilir."
            lower(Quality.LOW)
        }
        return VibrationResult(dominant, prominence, others, quality, notes, stats, fs, df, bandFreqs, bandPower)
    }

    /** Doğrusal aradeğerlemeyle [t0]'dan başlayan [n] örneklik tek düze dizi. */
    internal fun resample(tNs: LongArray, v: FloatArray, t0: Long, fs: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var j = 0
        val stepNs = 1e9 / fs
        for (i in 0 until n) {
            val t = t0 + (i * stepNs).toLong()
            while (j < tNs.size - 2 && tNs[j + 1] <= t) j++
            val ta = tNs[j]; val tb = tNs[j + 1]
            out[i] = if (tb == ta) {
                v[j].toDouble()
            } else {
                val f = ((t - ta).toDouble() / (tb - ta)).coerceIn(0.0, 1.0)
                v[j] + (v[j + 1] - v[j]) * f
            }
        }
        return out
    }

    private fun localPeaks(p: DoubleArray, lo: Int, hi: Int): List<Int> =
        (lo..hi).filter { k -> k > 0 && k < p.size - 1 && p[k] > p[k - 1] && p[k] >= p[k + 1] }

    /** Parabolik aradeğerleme: tepe kutusunun kesirli konumu. */
    private fun refine(p: DoubleArray, k: Int): Double {
        if (k <= 0 || k >= p.size - 1) return k.toDouble()
        val a = p[k - 1]; val b = p[k]; val c = p[k + 1]
        val den = a - 2 * b + c
        if (den == 0.0) return k.toDouble()
        val d = 0.5 * (a - c) / den
        return k + d.coerceIn(-0.5, 0.5)
    }
}

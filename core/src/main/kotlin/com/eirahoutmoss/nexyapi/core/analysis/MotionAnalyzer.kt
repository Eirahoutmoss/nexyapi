package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Küçültülmüş gri tonlu video: her kare [width]*[height] bayt (0–255), satır satır. */
class GrayVideo(val width: Int, val height: Int, val fps: Double, val frames: List<ByteArray>) {
    init {
        require(width > 0 && height > 0 && fps > 0) { "Geçersiz video boyutu." }
        require(frames.all { it.size == width * height }) { "Kare boyutları tutarsız." }
    }
}

data class MotionResult(
    /** En baskın hareket frekansı (Hz); bulunamadıysa null. */
    val dominantHz: Double?,
    val prominence: Double,
    val quality: Quality,
    val notes: List<String>,
    val spectrumFreqs: DoubleArray,
    val spectrumPower: DoubleArray,
    /** Her pikselin baskın frekanstaki hareket şiddeti, 0..1 (genişlik x yükseklik). */
    val motionMap: FloatArray,
    val width: Int,
    val height: Int,
    /** Büyütülmüş kareler (istenmişse). */
    val magnified: List<ByteArray>?,
)

/**
 * Görünmez hareket analizi (Euler yöntemiyle video büyütmenin doğrusal, sade bir hâli).
 *
 * Her pikselin parlaklığı zaman içinde bir sinyaldir. Bir kenar milimetrenin altında salınsa bile
 * o kenardaki piksellerin parlaklığı aynı frekansta küçük değişir. Bütün piksellerin spektrumları
 * toplanarak baskın titreşim frekansı bulunur; o frekans bandındaki değişim [amplification] kat
 * büyütülüp görüntüye geri eklenir.
 *
 * Telefon sabit durmalıdır (tripod ya da bir yere dayalı). Işık titremesi ve otomatik pozlama
 * bütün kareyi aynı anda etkiler; bu yüzden her kareden kare ortalaması çıkarılır ve ayrıca uyarılır.
 */
object MotionAnalyzer {

    data class Config(
        val bandMinHz: Double = 0.5,
        val bandMaxHz: Double = 15.0,
        val minFrames: Int = 64,
        val okProminence: Double = 8.0,
        val lowProminence: Double = 4.0,
        val amplification: Double = 25.0,
    )

    fun analyze(video: GrayVideo, magnify: Boolean, cfg: Config = Config()): MotionResult {
        val notes = ArrayList<String>()
        val w = video.width; val h = video.height
        val px = w * h
        val n = video.frames.size
        val emptyMap = FloatArray(px)
        fun fail(msg: String) = MotionResult(null, 0.0, Quality.UNRELIABLE, notes.also { it += msg }, DoubleArray(0), DoubleArray(0), emptyMap, w, h, null)

        if (n < cfg.minFrames) return fail("Video çok kısa; en az ${(cfg.minFrames / video.fps).roundToInt()} saniye gerekir.")
        val nyq = video.fps / 2
        val bandMax = min(cfg.bandMaxHz, nyq - 0.5)
        if (bandMax <= cfg.bandMinHz) return fail("Videonun kare hızı çok düşük.")

        // Kare ortalamaları (ışık titremesi / pozlama) ve ortalaması çıkarılmış veri
        val global = DoubleArray(n)
        for (t in 0 until n) {
            var s = 0L
            for (b in video.frames[t]) s += (b.toInt() and 0xFF)
            global[t] = s.toDouble() / px
        }

        var nfft = 1
        while (nfft < n) nfft = nfft shl 1
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val df = video.fps / nfft
        val lo = max(1, (cfg.bandMinHz / df).toInt())
        val hi = min(nfft / 2 - 1, (bandMax / df).toInt())

        val total = DoubleArray(nfft / 2 + 1)
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        // Piksel başına spektrum. Bellek için tüm spektrumlar saklanmaz; baskın frekans bulunduktan
        // sonra harita ve büyütme için ikinci geçiş yapılır.
        for (p in 0 until px) {
            pixelSignal(video, p, global, re, im, win, n)
            Dsp.fft(re, im)
            for (k in 0..nfft / 2) total[k] += re[k] * re[k] + im[k] * im[k]
        }

        val bandF = DoubleArray(hi - lo + 1) { (lo + it) * df }
        val bandP = DoubleArray(hi - lo + 1) { total[lo + it] }
        val floor = Dsp.median(bandP).takeIf { it > 0 } ?: Double.MIN_VALUE
        var k0 = lo
        for (k in lo..hi) if (total[k] > total[k0]) k0 = k
        val prominence = total[k0] / floor
        val dominant = refine(total, k0) * df

        // Işık titremesi: kare ortalamasının kendi spektrumunda belirgin tepe var mı?
        val gRe = DoubleArray(nfft); val gIm = DoubleArray(nfft)
        val gMean = global.average()
        for (t in 0 until n) gRe[t] = (global[t] - gMean) * win[t]
        Dsp.fft(gRe, gIm)
        val gP = DoubleArray(hi - lo + 1) { val k = lo + it; gRe[k] * gRe[k] + gIm[k] * gIm[k] }
        val gFloor = Dsp.median(gP).takeIf { it > 0 } ?: Double.MIN_VALUE
        if (gP.max() / gFloor > 30) {
            notes += "Görüntünün tamamında parlaklık dalgalanması var (LED/floresan ışık titremesi ya da otomatik pozlama). " +
                "Gün ışığında ya da titreşimsiz ışıkta çekmek önerilir."
        }

        // İkinci geçiş: harita (baskın banttaki genlik)
        val bw = max(1, (0.15 / df).roundToInt()) // ±0,15 Hz
        val map = FloatArray(px)
        for (p in 0 until px) {
            pixelSignal(video, p, global, re, im, win = null, n = n)
            Dsp.fft(re, im)
            var e = 0.0
            for (k in max(1, k0 - bw)..min(nfft / 2, k0 + bw)) e += re[k] * re[k] + im[k] * im[k]
            map[p] = sqrt(e).toFloat()
        }
        // Haritayı 0..1'e ölçekle (aykırı birkaç piksel ölçeği bozmasın diye %99'luk değerle)
        val sorted = map.sortedArray()
        val ref = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].takeIf { it > 0f } ?: 1f
        for (i in map.indices) map[i] = (map[i] / ref).coerceIn(0f, 1f)

        // Üçüncü geçiş (isteğe bağlı): büyütme. Yalnızca haritada hareket görülen pikseller büyütülür;
        // aksi hâlde kamera gürültüsü de büyür ve görüntü karıncalanır.
        val outFrames: List<ByteArray>? = if (magnify) List(n) { ByteArray(px) } else null
        if (outFrames != null) {
            for (p in 0 until px) {
                val weight = ((map[p] - 0.15f) / 0.25f).coerceIn(0f, 1f).toDouble()
                if (weight == 0.0) {
                    for (t in 0 until n) outFrames[t][p] = video.frames[t][p]
                    continue
                }
                pixelSignal(video, p, global, re, im, win = null, n = n)
                Dsp.fft(re, im)
                // Yalnızca baskın bandı bırak (pozitif ve negatif frekanslar), ters FFT
                for (k in 0 until nfft) {
                    val kk = if (k <= nfft / 2) k else nfft - k
                    if (kk < k0 - bw || kk > k0 + bw || kk == 0) { re[k] = 0.0; im[k] = 0.0 }
                }
                for (k in 0 until nfft) im[k] = -im[k]
                Dsp.fft(re, im)
                for (t in 0 until n) {
                    val band = re[t] / nfft
                    val orig = (video.frames[t][p].toInt() and 0xFF).toDouble()
                    val v = (orig + cfg.amplification * weight * band).roundToInt().coerceIn(0, 255)
                    outFrames[t][p] = v.toByte()
                }
            }
        }

        var q = Quality.OK
        fun lower(to: Quality) { if (to.ordinal > q.ordinal) q = to }
        when {
            prominence < cfg.lowProminence -> { notes += "Belirgin bir titreşim frekansı bulunamadı."; lower(Quality.UNRELIABLE) }
            prominence < cfg.okProminence -> { notes += "Titreşim zayıf belirgin; daha uzun ya da daha yakından çekim önerilir."; lower(Quality.LOW) }
        }
        // Hareket her yerdeyse büyük olasılıkla kamera sallanmıştır.
        val meanMap = map.average()
        if (q != Quality.UNRELIABLE && meanMap > 0.45) {
            notes += "Hareket görüntünün her yerinde benzer; telefon sallanmış olabilir. Telefonu sabitleyip tekrarlayın."
            lower(Quality.LOW)
        }
        val dom = if (q == Quality.UNRELIABLE) null else dominant
        return MotionResult(dom, prominence, q, notes, bandF, bandP, map, w, h, outFrames)
    }

    /** [p] pikselinin, kare ortalaması çıkarılmış ve ortalaması sıfırlanmış zaman dizisini re/im'e yazar. */
    private fun pixelSignal(v: GrayVideo, p: Int, global: DoubleArray, re: DoubleArray, im: DoubleArray, win: DoubleArray?, n: Int) {
        var mean = 0.0
        for (t in 0 until n) mean += (v.frames[t][p].toInt() and 0xFF) - global[t]
        mean /= n
        for (t in 0 until n) {
            val x = (v.frames[t][p].toInt() and 0xFF) - global[t] - mean
            re[t] = if (win != null) x * win[t] else x
            im[t] = 0.0
        }
        for (t in n until re.size) { re[t] = 0.0; im[t] = 0.0 }
    }

    private fun refine(p: DoubleArray, k: Int): Double {
        if (k <= 0 || k >= p.size - 1) return k.toDouble()
        val a = p[k - 1]; val b = p[k]; val c = p[k + 1]
        val den = a - 2 * b + c
        if (den == 0.0) return k.toDouble()
        return k + (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
    }
}

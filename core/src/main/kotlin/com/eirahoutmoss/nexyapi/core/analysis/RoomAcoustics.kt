package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Üstel sinüs taraması (Farina yöntemi). Taramayı çalıp odadan kaydetmek ve kaydı ters süzgeçle
 * evriştirmek odanın dürtü yanıtını verir. Hoparlörün bozulma (distorsiyon) bileşenleri dürtüden
 * ÖNCEYE düşer ve ayıklanır; gecikme (Bluetooth hoparlör vb.) sonucu bozmaz.
 */
object Sweep {
    class Signal(val samples: FloatArray, val inverse: DoubleArray, val fs: Int, val f1: Double, val f2: Double, val durationS: Double)

    fun create(fs: Int = 48_000, f1: Double = 50.0, f2: Double = 16_000.0, durationS: Double = 5.0, amplitude: Double = 0.7): Signal {
        val n = (fs * durationS).toInt()
        val k = ln(f2 / f1)
        val l = durationS / k
        val fade = (0.03 * fs).toInt()
        val s = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            var v = sin(2 * PI * f1 * l * (exp(t / l) - 1))
            // Başta ve sonda yumuşak geçiş: tıkırtı olmasın.
            if (i < fade) v *= 0.5 - 0.5 * cos(PI * i / fade)
            if (i >= n - fade) v *= 0.5 - 0.5 * cos(PI * (n - 1 - i) / fade)
            s[i] = (amplitude * v).toFloat()
        }
        // Ters süzgeç: zamanda ters çevrilmiş tarama. Tarama alçak frekanslarda daha uzun kaldığı için
        // oralarda fazla enerji taşır; zarf alçak frekansları 6 dB/oktav bastırır (f1'de f1/f2, f2'de 1).
        val inv = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            inv[n - 1 - i] = s[i] * exp((t - durationS) / l)
        }
        // Ölçek: tarama * ters süzgeç tepe değeri ~1 olsun.
        val peak = convolvePeak(s, inv)
        if (peak > 0) for (i in inv.indices) inv[i] /= peak
        return Signal(s, inv, fs, f1, f2, durationS)
    }

    private fun convolvePeak(a: FloatArray, b: DoubleArray): Double {
        val y = fftConvolve(DoubleArray(a.size) { a[it].toDouble() }, b)
        return y.maxOf { abs(it) }
    }
}

/** FFT ile doğrusal evrişim (uzunluk a+b-1). */
fun fftConvolve(a: DoubleArray, b: DoubleArray): DoubleArray {
    val outLen = a.size + b.size - 1
    var n = 1
    while (n < outLen) n = n shl 1
    val ar = DoubleArray(n); val ai = DoubleArray(n)
    val br = DoubleArray(n); val bi = DoubleArray(n)
    a.copyInto(ar); b.copyInto(br)
    Dsp.fft(ar, ai); Dsp.fft(br, bi)
    for (k in 0 until n) {
        val r = ar[k] * br[k] - ai[k] * bi[k]
        val i = ar[k] * bi[k] + ai[k] * br[k]
        ar[k] = r; ai[k] = -i // ters FFT için eşlenik
    }
    Dsp.fft(ar, ai)
    return DoubleArray(outLen) { ar[it] / n }
}

data class BandDecay(
    val centerHz: Double,
    /** Yankı süresi (sn); hesaplanamadıysa null. */
    val rt60S: Double?,
    /** Dürtü tepesi ile gürültü tabanı arasındaki fark (dB). */
    val dynamicRangeDb: Double,
    val fitR2: Double,
    val quality: Quality,
)

data class AcousticsResult(
    val bands: List<BandDecay>,
    /** Orta frekans (500 Hz + 1 kHz) ortalama yankı süresi; ölçülemediyse null. */
    val rt60MidS: Double?,
    val quality: Quality,
    val notes: List<String>,
    /** Frekans yanıtı (1/6 oktav yumuşatılmış, dB, göreli). */
    val responseHz: DoubleArray,
    val responseDb: DoubleArray,
    /** Bas bölgesinde (40–300 Hz) çevresinden belirgin yüksek tepeler: olası bas birikmesi. */
    val bassPeaksHz: List<Double>,
)

object RoomAcoustics {

    val BANDS = doubleArrayOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0)

    /** Kayıttan dürtü yanıtı: kayıt ⊛ ters süzgeç; dürtü tepesinden itibaren [lengthS] saniye. */
    fun impulseResponse(recording: FloatArray, sweep: Sweep.Signal, lengthS: Double = 2.5): DoubleArray {
        val rec = DoubleArray(recording.size) { recording[it].toDouble() }
        val full = fftConvolve(rec, sweep.inverse)
        var peak = 0
        for (i in full.indices) if (abs(full[i]) > abs(full[peak])) peak = i
        val pre = (0.005 * sweep.fs).toInt()
        val start = max(0, peak - pre)
        val end = min(full.size, start + (lengthS * sweep.fs).toInt())
        return full.copyOfRange(start, end)
    }

    /** Birden fazla taramanın dürtü yanıtlarını (tepeleri hizalı) ortalar; gürültü azalır. */
    fun average(irs: List<DoubleArray>): DoubleArray {
        val n = irs.minOf { it.size }
        return DoubleArray(n) { i -> irs.sumOf { it[i] } / irs.size }
    }

    fun analyze(ir: DoubleArray, fs: Int, externalSpeaker: Boolean): AcousticsResult {
        val notes = ArrayList<String>()
        val bands = BANDS.map { f -> bandDecay(ir, fs, f) }

        val mid = bands.filter { it.centerHz == 500.0 || it.centerHz == 1000.0 }
            .filter { it.rt60S != null && it.quality != Quality.UNRELIABLE }
        val rt60Mid = if (mid.isEmpty()) null else mid.sumOf { it.rt60S!! } / mid.size

        var q = Quality.OK
        if (rt60Mid == null) {
            q = Quality.UNRELIABLE
            notes += "Orta frekanslarda yankı süresi ölçülemedi. Ortam gürültüsü yüksek ya da ses seviyesi düşük olabilir."
        } else if (mid.any { it.quality == Quality.LOW }) {
            q = Quality.LOW
            notes += "Ölçüm dinamik aralığı sınırda. Daha sessiz bir ortamda ve daha yüksek sesle tekrarlayın."
        }
        bands.firstOrNull { it.centerHz == 125.0 }?.let {
            if (it.quality != Quality.OK && !externalSpeaker) {
                notes += "Telefon hoparlörü 125 Hz'de yeterli ses vermiyor. Bas ölçümü için harici bir hoparlör kullanın."
            }
        }

        val (fr, db) = frequencyResponse(ir, fs)
        val bass = if (externalSpeaker) bassPeaks(fr, db) else emptyList()
        if (!externalSpeaker) notes += "Bas birikmesi ancak harici hoparlörle ölçülebilir; aşağıdaki oda modları oda boyutlarından hesaplanmıştır."
        return AcousticsResult(bands, rt60Mid, q, notes, fr, db, bass)
    }

    /** Bir oktav bandında Schroeder geriye tümlemesiyle T20 → RT60. */
    internal fun bandDecay(ir: DoubleArray, fs: Int, fc: Double): BandDecay {
        var x = ir
        // 2 kademeli bant geçiren, ileri-geri (sıfır faz): dik kenarlı oktav bandı.
        repeat(2) { x = biquadBandpass(x, fs, fc) }
        x = x.reversedArray()
        repeat(2) { x = biquadBandpass(x, fs, fc) }
        x = x.reversedArray()

        // 10 ms'lik bloklarda enerji zarfı
        val block = max(1, fs / 100)
        val nb = x.size / block
        if (nb < 20) return BandDecay(fc, null, 0.0, 0.0, Quality.UNRELIABLE)
        val env = DoubleArray(nb) { b ->
            var s = 0.0
            for (i in b * block until (b + 1) * block) s += x[i] * x[i]
            s / block
        }
        val peakBlock = env.indices.maxBy { env[it] }
        val tail = env.copyOfRange((nb * 0.85).toInt(), nb)
        val noise = max(tail.average(), 1e-30)
        val dr = 10 * log10(env[peakBlock] / noise)

        // Kesme noktası: zarfın gürültü + 5 dB'ye indiği ilk blok (Lundeby yönteminin sade hâli).
        var cut = nb
        for (b in peakBlock until nb) {
            if (env[b] <= noise * 3.16) { cut = b; break }
        }
        // Schroeder: gürültü çıkarılmış enerjinin geriye toplamı
        val start = peakBlock * block
        val end = cut * block
        if (end - start < block * 10) return BandDecay(fc, null, dr, 0.0, Quality.UNRELIABLE)
        val edc = DoubleArray(end - start)
        var acc = 0.0
        for (i in end - 1 downTo start) {
            acc += max(x[i] * x[i] - noise, 0.0)
            edc[i - start] = acc
        }
        val e0 = edc[0]
        if (e0 <= 0) return BandDecay(fc, null, dr, 0.0, Quality.UNRELIABLE)

        // T20: -5 dB ile -25 dB arası doğrusal uydurma (yetmezse T15: -5..-20)
        fun fit(lo: Double, hi: Double): Pair<Double, Double>? {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var n = 0
            for (i in edc.indices step max(1, fs / 2000)) {
                val d = 10 * log10(edc[i] / e0)
                if (d <= lo && d >= hi) {
                    val t = i.toDouble() / fs
                    sx += t; sy += d; sxx += t * t; sxy += t * d; syy += d * d; n++
                }
            }
            if (n < 10) return null
            val den = n * sxx - sx * sx
            if (den == 0.0) return null
            val slope = (n * sxy - sx * sy) / den
            val r = (n * sxy - sx * sy) / sqrt(den * (n * syy - sy * sy))
            return slope to r * r
        }
        val f = fit(-5.0, -25.0) ?: fit(-5.0, -20.0)
            ?: return BandDecay(fc, null, dr, 0.0, Quality.UNRELIABLE)
        val (slope, r2) = f
        if (slope >= 0) return BandDecay(fc, null, dr, r2, Quality.UNRELIABLE)
        val rt60 = -60.0 / slope

        val quality = when {
            dr < 25 || r2 < 0.9 -> Quality.UNRELIABLE
            dr < 35 || r2 < 0.98 -> Quality.LOW
            else -> Quality.OK
        }
        return BandDecay(fc, rt60, dr, r2, quality)
    }

    /** RBJ bant geçiren biquad (1 oktav genişlik, 0 dB tepe). */
    private fun biquadBandpass(x: DoubleArray, fs: Int, fc: Double): DoubleArray {
        val w0 = 2 * PI * fc / fs
        val alpha = sin(w0) * sinh(ln(2.0) / 2 * 1.0 * w0 / sin(w0))
        val a0 = 1 + alpha
        val b0 = alpha / a0; val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0; val a2 = (1 - alpha) / a0
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }

    /** Dürtü yanıtının genlik spektrumu, 40 Hz–12 kHz, 1/6 oktav yumuşatılmış, en yüksek değer 0 dB. */
    internal fun frequencyResponse(ir: DoubleArray, fs: Int): Pair<DoubleArray, DoubleArray> {
        val len = min(ir.size, fs / 2) // ilk 0,5 sn
        var n = 1
        while (n < len) n = n shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until len) {
            val w = if (i > len * 0.8) 0.5 + 0.5 * cos(PI * (i - len * 0.8) / (len * 0.2)) else 1.0
            re[i] = ir[i] * w
        }
        Dsp.fft(re, im)
        val df = fs.toDouble() / n
        val mag = DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        val points = ArrayList<Double>(); val values = ArrayList<Double>()
        var f = 40.0
        while (f <= 12_000) {
            val lo = f / 2.0.pow(1.0 / 12); val hi = f * 2.0.pow(1.0 / 12)
            var s = 0.0; var c = 0
            for (k in max(1, (lo / df).toInt())..min(mag.size - 1, (hi / df).toInt())) { s += mag[k]; c++ }
            if (c > 0) { points += f; values += 10 * log10(max(s / c, 1e-30)) }
            f *= 2.0.pow(1.0 / 24)
        }
        val top = values.maxOrNull() ?: 0.0
        return points.toDoubleArray() to DoubleArray(values.size) { values[it] - top }
    }

    /** 40–300 Hz'de, bir oktavlık ortalamadan 6 dB'den fazla yüksek yerel tepeler. */
    internal fun bassPeaks(f: DoubleArray, db: DoubleArray): List<Double> {
        val out = ArrayList<Double>()
        for (i in 1 until f.size - 1) {
            if (f[i] < 40 || f[i] > 300) continue
            if (db[i] <= db[i - 1] || db[i] < db[i + 1]) continue
            val lo = f[i] / sqrt(2.0); val hi = f[i] * sqrt(2.0)
            val around = f.indices.filter { f[it] in lo..hi && it != i }.map { db[it] }
            if (around.isNotEmpty() && db[i] - around.average() > 6) out += f[i]
        }
        return out
    }
}

/** Dikdörtgen oda için eksenel oda modları (bas birikmesinin en güçlü kaynağı). */
object RoomModes {
    const val SPEED_OF_SOUND = 343.0

    data class Mode(val frequencyHz: Double, val axis: String, val order: Int)

    /** [l], [w], [h] metre. 300 Hz'e kadar eksenel modlar, frekansa göre sıralı. */
    fun axial(l: Double, w: Double, h: Double, maxHz: Double = 300.0): List<Mode> {
        val out = ArrayList<Mode>()
        for ((dim, name) in listOf(l to "uzunluk", w to "genişlik", h to "yükseklik")) {
            if (dim <= 0) continue
            var n = 1
            while (true) {
                val f = SPEED_OF_SOUND * n / (2 * dim)
                if (f > maxHz) break
                out += Mode(f, name, n)
                n++
            }
        }
        return out.sortedBy { it.frequencyHz }
    }

    /**
     * Farklı eksenlerden birbirine %5'ten yakın düşen modlar: bu frekanslarda bas belirgin şekilde
     * birikir (ör. kare odalar ya da boyutları birbirinin katı olan odalar).
     */
    fun coincidences(modes: List<Mode>, tolerance: Double = 0.05): List<Pair<Mode, Mode>> {
        val out = ArrayList<Pair<Mode, Mode>>()
        for (i in modes.indices) for (j in i + 1 until modes.size) {
            val a = modes[i]; val b = modes[j]
            if (a.axis != b.axis && abs(a.frequencyHz - b.frequencyHz) / a.frequencyHz < tolerance) out += a to b
        }
        return out
    }
}

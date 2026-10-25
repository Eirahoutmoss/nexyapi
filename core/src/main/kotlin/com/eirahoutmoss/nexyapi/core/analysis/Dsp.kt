package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Sinyal işleme yardımcıları. Saf Kotlin; dış kütüphane yok. */
object Dsp {

    /** Yerinde, radix-2 karmaşık FFT. Boyut 2'nin kuvveti olmalı. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "FFT boyutu 2'nin kuvveti olmalı: $n" }
        // bit ters sıralama
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    /** Doğrusal eğilimi (ortalama + eğim) çıkarır. */
    fun detrend(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return x.copyOf()
        val mt = (n - 1) / 2.0
        var mx = 0.0
        for (v in x) mx += v
        mx /= n
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { val dt = i - mt; num += dt * (x[i] - mx); den += dt * dt }
        val slope = num / den
        return DoubleArray(n) { x[it] - mx - slope * (it - mt) }
    }

    /**
     * Welch güç spektral yoğunluğu (tek taraflı). Segmentler Hann pencereli, %50 örtüşmeli,
     * her segmentte doğrusal eğilim çıkarılır.
     * @return frekans ekseni (Hz), güç değerleri ve kullanılan segmentlerin RMS değerleri
     */
    fun welch(x: DoubleArray, fs: Double, nseg: Int): Welch {
        require(nseg <= x.size) { "Veri segmentten kısa." }
        val step = nseg / 2
        val w = hann(nseg)
        var wPow = 0.0
        for (v in w) wPow += v * v
        val bins = nseg / 2 + 1
        val psd = DoubleArray(bins)
        val segRms = ArrayList<Double>()
        var start = 0
        var count = 0
        val re = DoubleArray(nseg); val im = DoubleArray(nseg)
        while (start + nseg <= x.size) {
            val seg = detrend(x.copyOfRange(start, start + nseg))
            var ss = 0.0
            for (i in 0 until nseg) { ss += seg[i] * seg[i]; re[i] = seg[i] * w[i]; im[i] = 0.0 }
            segRms.add(sqrt(ss / nseg))
            fft(re, im)
            for (k in 0 until bins) {
                var p = (re[k] * re[k] + im[k] * im[k]) / (fs * wPow)
                if (k != 0 && k != nseg / 2) p *= 2.0
                psd[k] += p
            }
            count++
            start += step
        }
        for (k in 0 until bins) psd[k] /= count
        val freqs = DoubleArray(bins) { it * fs / nseg }
        return Welch(freqs, psd, count, segRms)
    }

    class Welch(val freqs: DoubleArray, val psd: DoubleArray, val segments: Int, val segmentRms: List<Double>)

    /** Pencereli-sinc alçak geçiren FIR (Hamming). [cutoff] Nyquist'e oranla (0..1). */
    fun lowPassFir(taps: Int, cutoff: Double): DoubleArray {
        require(taps % 2 == 1) { "Musluk sayısı tek olmalı." }
        val m = taps / 2
        val h = DoubleArray(taps) { i ->
            val k = i - m
            val sinc = if (k == 0) cutoff else sin(PI * cutoff * k) / (PI * k)
            val win = 0.54 - 0.46 * cos(2 * PI * i / (taps - 1))
            sinc * win
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        return h
    }

    /** Alçak geçiren süzgeç uygulayıp her [factor] örnekten birini alır (kenar etkisi olmadan). */
    fun decimate(x: DoubleArray, factor: Int): DoubleArray {
        if (factor <= 1) return x.copyOf()
        val h = lowPassFir(taps = 16 * factor + 1, cutoff = 0.8 / factor)
        val m = h.size / 2
        val out = ArrayList<Double>(x.size / factor + 1)
        var i = m
        while (i + m < x.size) {
            var acc = 0.0
            for (k in h.indices) acc += h[k] * x[i - m + k]
            out.add(acc)
            i += factor
        }
        return out.toDoubleArray()
    }

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}

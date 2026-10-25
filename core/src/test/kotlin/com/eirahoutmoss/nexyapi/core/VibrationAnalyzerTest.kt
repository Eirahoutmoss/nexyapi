package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.analysis.AccelRecording
import com.eirahoutmoss.nexyapi.core.analysis.Dsp
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import com.eirahoutmoss.nexyapi.core.analysis.VibrationAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class VibrationAnalyzerTest {

    /** Gerçekçi sentetik kayıt: düzensiz zaman damgaları, yerçekimi, gürültü. */
    private fun synth(
        seconds: Double = 120.0,
        rateHz: Double = 200.0,
        jitter: Double = 0.15,
        tones: List<Pair<Double, Double>> = listOf(2.8 to 0.004), // (Hz, genlik m/s²)
        noise: Double = 0.002,
        seed: Int = 1,
        gapAtS: Double? = null,
    ): AccelRecording {
        val r = Random(seed)
        val ts = ArrayList<Long>(); val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>()
        var t = 1_000_000_000L
        val end = t + (seconds * 1e9).toLong()
        val meanDt = 1e9 / rateHz
        var gapDone = false
        while (t < end) {
            val ts0 = (t - 1_000_000_000L) / 1e9
            var sx = 0.0; var sy = 0.0
            for ((f, a) in tones) {
                sx += a * sin(2 * PI * f * ts0)
                sy += 0.6 * a * sin(2 * PI * f * ts0 + 0.7)
            }
            ts += t
            xs += (sx + r.nextDouble(-1.0, 1.0) * noise * 1.7).toFloat()
            ys += (sy + r.nextDouble(-1.0, 1.0) * noise * 1.7).toFloat()
            zs += (9.81 + r.nextDouble(-1.0, 1.0) * noise).toFloat()
            var dt = meanDt * (1 + r.nextDouble(-jitter, jitter))
            if (gapAtS != null && !gapDone && ts0 > gapAtS) { dt += 600e6; gapDone = true }
            t += dt.toLong()
        }
        return AccelRecording(ts.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray())
    }

    @Test fun fftOfSineHasSinglePeak() {
        val n = 256
        val re = DoubleArray(n) { sin(2 * PI * 8 * it / n) }
        val im = DoubleArray(n)
        Dsp.fft(re, im)
        val mags = DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(8, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2.0, kotlin.math.sqrt(mags[8]), 1e-6)
    }

    @Test fun findsDominantFrequency() {
        val r = VibrationAnalyzer.analyze(synth())
        assertNotNull(r.dominantHz)
        assertEquals(2.8, r.dominantHz!!, 0.03)
        assertEquals(Quality.OK, r.quality)
        assertEquals(200.0, r.sampling.rateHz, 5.0)
    }

    @Test fun worksAtLowerSamplingRates() {
        val r = VibrationAnalyzer.analyze(synth(rateHz = 100.0, tones = listOf(1.35 to 0.004)))
        assertEquals(1.35, r.dominantHz!!, 0.03)
    }

    @Test fun highFrequencyMachineNoiseDoesNotAliasIntoBand() {
        // 200 Hz örneklemede 47 Hz'lik güçlü fan titreşimi, 50 Hz'e süzgeçsiz inilseydi 3 Hz'e katlanırdı.
        val r = VibrationAnalyzer.analyze(synth(tones = listOf(2.2 to 0.004, 47.0 to 0.05)))
        assertEquals(2.2, r.dominantHz!!, 0.03)
        assertTrue("Katlanma tepesi olmamalı: ${r.otherPeaks}", r.otherPeaks.none { abs(it.frequencyHz - 3.0) < 0.2 })
    }

    @Test fun pureNoiseIsNotReportedAsReliable() {
        val r = VibrationAnalyzer.analyze(synth(tones = emptyList()))
        assertNotEquals(Quality.OK, r.quality)
    }

    @Test fun tooSlowSensorIsUnreliable() {
        val r = VibrationAnalyzer.analyze(synth(rateHz = 40.0))
        assertEquals(Quality.UNRELIABLE, r.quality)
    }

    @Test fun tooShortRecordingIsUnreliable() {
        val r = VibrationAnalyzer.analyze(synth(seconds = 20.0))
        assertEquals(Quality.UNRELIABLE, r.quality)
    }

    @Test fun dataGapIsFlagged() {
        val r = VibrationAnalyzer.analyze(synth(gapAtS = 40.0))
        assertEquals(Quality.UNRELIABLE, r.quality)
        assertTrue(r.notes.any { "boşluğu" in it })
    }

    @Test fun suddenShockIsFlagged() {
        val rec = synth()
        // 60. saniyede 1 saniyelik sert darbe
        for (i in 0 until rec.size) {
            val s = (rec.tNs[i] - rec.tNs[0]) / 1e9
            if (s in 60.0..61.0) rec.x[i] = rec.x[i] + 0.5f * sin(2 * PI * 9 * s).toFloat()
        }
        val r = VibrationAnalyzer.analyze(rec)
        assertTrue(r.notes.joinToString(), r.notes.any { "sarsıntı" in it })
    }

    @Test fun repeatedMeasurementsAreConsistent() {
        val fs = (1..5).map { VibrationAnalyzer.analyze(synth(seed = it)).dominantHz!! }
        assertTrue("Tekrarlar arası fark: $fs", fs.max() - fs.min() < 0.03)
    }
}

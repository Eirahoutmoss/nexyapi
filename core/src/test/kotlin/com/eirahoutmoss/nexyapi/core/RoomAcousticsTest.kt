package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.analysis.Quality
import com.eirahoutmoss.nexyapi.core.analysis.RoomAcoustics
import com.eirahoutmoss.nexyapi.core.analysis.RoomModes
import com.eirahoutmoss.nexyapi.core.analysis.Sweep
import com.eirahoutmoss.nexyapi.core.analysis.fftConvolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class RoomAcousticsTest {
    private val fs = 48_000

    /** Bilinen yankı süresine sahip yapay oda: doğrudan ses + üstel sönen gürültü. */
    private fun syntheticRoom(rt60: Double, seconds: Double = 2.0, seed: Int = 3): DoubleArray {
        val r = Random(seed)
        val n = (fs * seconds).toInt()
        val decay = 3 * ln(10.0) / rt60 // genlik için: 60 dB / rt60
        val h = DoubleArray(n)
        h[0] = 1.0
        for (i in (0.002 * fs).toInt() until n) {
            val t = i.toDouble() / fs
            h[i] = r.nextDouble(-1.0, 1.0) * 0.3 * exp(-decay * t)
        }
        return h
    }

    private fun record(ir: DoubleArray, sweep: Sweep.Signal, noise: Double, delayS: Double = 0.12, seed: Int = 9): FloatArray {
        val s = DoubleArray(sweep.samples.size) { sweep.samples[it].toDouble() }
        val y = fftConvolve(s, ir)
        val r = Random(seed)
        val delay = (delayS * fs).toInt()
        val out = FloatArray(delay + y.size + fs / 2)
        for (i in out.indices) {
            val v = if (i >= delay && i - delay < y.size) y[i - delay] else 0.0
            out[i] = (v + r.nextDouble(-1.0, 1.0) * noise).toFloat()
        }
        return out
    }

    private val sweep = Sweep.create(fs = fs, durationS = 3.0)

    @Test fun sweepDeconvolvesToImpulse() {
        val ir = RoomAcoustics.impulseResponse(record(doubleArrayOf(1.0), sweep, noise = 0.0), sweep, lengthS = 0.2)
        val peak = ir.indices.maxBy { abs(ir[it]) }
        assertEquals(1.0, abs(ir[peak]), 0.05)
        // Tepe dışında enerji çok küçük olmalı (bant sınırlı olduğu için ufak sızıntı normal).
        val side = ir.indices.filter { abs(it - peak) > 200 }.maxOf { abs(ir[it]) }
        assertTrue("yan tepe $side", side < 0.02)
    }

    /**
     * Yankı süresi istatistiksel bir büyüklüktür: tek bir dürtü yanıtında bant değeri ±%10-15 oynar
     * (ISO 3382 bu yüzden birden fazla konumda ölçmeyi önerir). Bu test birkaç "konumun" ortalamasını sınar
     * ve tek konumlu sonucun makul aralıkta kaldığını doğrular.
     */
    @Test fun recoversReverberationTime() {
        for (rt in listOf(0.35, 0.6, 1.1)) {
            val values = (1..4).map { seed ->
                val rec = record(syntheticRoom(rt, seed = seed), sweep, noise = 1e-4, seed = seed + 50)
                val r = RoomAcoustics.analyze(RoomAcoustics.impulseResponse(rec, sweep), fs, externalSpeaker = true)
                assertNotNull("rt=$rt", r.rt60MidS)
                assertEquals("tek konum rt=$rt", rt, r.rt60MidS!!, rt * 0.18)
                r.rt60MidS!!
            }
            assertEquals("ortalama rt=$rt $values", rt, values.average(), rt * 0.08)
        }
    }

    @Test fun delayFromBluetoothSpeakerDoesNotMatter() {
        val a = RoomAcoustics.analyze(RoomAcoustics.impulseResponse(record(syntheticRoom(0.6), sweep, 1e-4, delayS = 0.02), sweep), fs, true)
        val b = RoomAcoustics.analyze(RoomAcoustics.impulseResponse(record(syntheticRoom(0.6), sweep, 1e-4, delayS = 0.35), sweep), fs, true)
        assertEquals(a.rt60MidS!!, b.rt60MidS!!, 0.03)
    }

    @Test fun loudBackgroundNoiseIsFlagged() {
        // Tarama yöntemi gürültüye karşı çok dayanıklıdır (~50 dB işlem kazancı); bu yüzden gürültü çok yüksek seçildi.
        val rec = record(syntheticRoom(0.6), sweep, noise = 4.0)
        val r = RoomAcoustics.analyze(RoomAcoustics.impulseResponse(rec, sweep), fs, false)
        assertTrue("kalite ${r.quality} ${r.bands}", r.quality != Quality.OK)
    }

    @Test fun silenceGivesNoResult() {
        val rec = FloatArray(fs * 4) { (Random(it).nextDouble(-1.0, 1.0) * 1e-3).toFloat() }
        val r = RoomAcoustics.analyze(RoomAcoustics.impulseResponse(rec, sweep), fs, false)
        assertNull(r.rt60MidS)
        assertEquals(Quality.UNRELIABLE, r.quality)
    }

    @Test fun roomModesForTypicalRoom() {
        val modes = RoomModes.axial(5.0, 4.0, 2.7)
        assertEquals(34.3, modes.first().frequencyHz, 0.1) // 343 / (2*5)
        assertTrue(modes.all { it.frequencyHz <= 300 })
        // Kare oda: uzunluk ve genişlik modları çakışır
        assertTrue(RoomModes.coincidences(RoomModes.axial(4.0, 4.0, 2.7)).isNotEmpty())
    }
}

class AcousticsAdviceTest {
    @Test fun verdictBoundaries() {
        assertEquals(com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.Verdict.DEAD, com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.verdict(0.25))
        assertEquals(com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.Verdict.BALANCED, com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.verdict(0.6))
        assertEquals(com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.Verdict.LIVE, com.eirahoutmoss.nexyapi.core.analysis.AcousticsAdvice.verdict(1.0))
    }
}

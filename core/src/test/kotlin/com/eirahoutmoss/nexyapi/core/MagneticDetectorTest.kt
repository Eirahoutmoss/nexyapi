package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.analysis.MagneticDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

class MagneticDetectorTest {
    private val earth = doubleArrayOf(20.0, -5.0, -40.0)
    private val r = Random(4)
    private fun noisy(v: DoubleArray, n: Double = 0.3) = doubleArrayOf(v[0] + r.nextDouble(-n, n), v[1] + r.nextDouble(-n, n), v[2] + r.nextDouble(-n, n))

    @Test fun calibratesOnQuietSpot() {
        val d = MagneticDetector()
        val c = d.calibrate(List(50) { noisy(earth) })
        assertTrue(c is MagneticDetector.Calibration.Ok)
        assertEquals(45.0, d.baseline!!.magnitude, 1.0)
    }

    @Test fun rejectsMovingPhoneDuringCalibration() {
        val d = MagneticDetector()
        val c = d.calibrate(List(50) { i -> noisy(doubleArrayOf(earth[0] + i, earth[1], earth[2])) })
        assertTrue(c is MagneticDetector.Calibration.Error)
    }

    @Test fun rejectsCalibrationNextToMagnet() {
        val d = MagneticDetector()
        assertTrue(d.calibrate(List(50) { noisy(doubleArrayOf(300.0, 0.0, 0.0)) }) is MagneticDetector.Calibration.Error)
    }

    @Test fun noReadingBeforeCalibration() {
        assertNull(MagneticDetector().update(1.0, 2.0, 3.0))
    }

    /** Telefon duvarda bir demir çubuğun üzerinden geçiyor: dipol benzeri tepe. */
    @Test fun detectsRebarAndReportsPassingThePeak() {
        val d = MagneticDetector()
        d.calibrate(List(50) { noisy(earth) })
        var detected = false
        var passedAt = -1
        for (i in 0 until 200) {
            val pos = (i - 100) / 20.0 // cm cinsinden, 0'da demir
            val strength = 35.0 / (1 + pos * pos).pow(1.5)
            val v = noisy(doubleArrayOf(earth[0] + strength * 0.4, earth[1], earth[2] - strength))
            val rd = d.update(v[0], v[1], v[2])!!
            if (rd.detected) detected = true
            if (rd.passedPeak && passedAt < 0) passedAt = i
        }
        assertTrue(detected)
        assertTrue("tepe geçişi: $passedAt", passedAt in 95..115)
    }

    @Test fun quietWallStaysBelowThreshold() {
        val d = MagneticDetector()
        d.calibrate(List(50) { noisy(earth) })
        repeat(300) {
            val v = noisy(earth)
            assertFalse(d.update(v[0], v[1], v[2])!!.detected)
        }
    }
}

class OrientationCompensationTest {
    /** Telefon eğilip dönerken sabit dünya alanı: dünya eksenine çevrilince sapma çıkmamalı. */
    @Test fun tiltingThePhoneIsNotMistakenForMetal() {
        val world = doubleArrayOf(0.0, 22.0, -40.0)
        fun quat(axis: DoubleArray, angle: Double): FloatArray {
            val n = kotlin.math.sqrt(axis.sumOf { it * it })
            val s = kotlin.math.sin(angle / 2)
            return floatArrayOf((axis[0] / n * s).toFloat(), (axis[1] / n * s).toFloat(), (axis[2] / n * s).toFloat(), kotlin.math.cos(angle / 2).toFloat())
        }
        fun deviceReading(q: FloatArray): DoubleArray {
            // cihaz = Rᵀ · dünya
            val r = com.eirahoutmoss.nexyapi.core.analysis.Orientation.matrixFromRotationVector(q)
            return doubleArrayOf(
                r[0] * world[0] + r[3] * world[1] + r[6] * world[2],
                r[1] * world[0] + r[4] * world[1] + r[7] * world[2],
                r[2] * world[0] + r[5] * world[1] + r[8] * world[2],
            )
        }
        val base = quat(doubleArrayOf(0.0, 0.0, 1.0), 0.0)
        val compensated = MagneticDetector()
        val naive = MagneticDetector()
        val cal = List(40) { com.eirahoutmoss.nexyapi.core.analysis.Orientation.toWorld(
            com.eirahoutmoss.nexyapi.core.analysis.Orientation.matrixFromRotationVector(base), deviceReading(base)) }
        compensated.calibrate(cal)
        naive.calibrate(List(40) { deviceReading(base) })
        var naiveHit = false
        for (i in 0..100) {
            val q = quat(doubleArrayOf(1.0, 0.3, 0.2), Math.toRadians(25.0 * kotlin.math.sin(i / 10.0)))
            val dev = deviceReading(q)
            val w = com.eirahoutmoss.nexyapi.core.analysis.Orientation.toWorld(com.eirahoutmoss.nexyapi.core.analysis.Orientation.matrixFromRotationVector(q), dev)
            assertFalse("dengelenmiş ölçüm metal sanmamalı (i=$i)", compensated.update(w[0], w[1], w[2])!!.detected)
            if (naive.update(dev[0], dev[1], dev[2])!!.detected) naiveHit = true
        }
        assertTrue("dengelenmemiş ölçüm eğilmeyi metal sanıyordu", naiveHit)
    }
}

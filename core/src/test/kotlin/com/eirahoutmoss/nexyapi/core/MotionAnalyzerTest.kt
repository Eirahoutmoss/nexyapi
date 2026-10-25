package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.analysis.GrayVideo
import com.eirahoutmoss.nexyapi.core.analysis.MotionAnalyzer
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class MotionAnalyzerTest {
    private val w = 64
    private val h = 36
    private val fps = 30.0

    /**
     * Sol yarıda yumuşak bir dikey kenar, [freq] Hz'de [ampPx] piksel salınıyor (gözle görülmez).
     * Sağ yarı durağan doku. İsteğe bağlı bütün kareye parlaklık titremesi.
     */
    private fun video(
        freq: Double = 3.0,
        ampPx: Double = 0.15,
        frames: Int = 300,
        flickerHz: Double? = null,
        noise: Double = 1.5,
        seed: Int = 1,
    ): GrayVideo {
        val r = Random(seed)
        val list = (0 until frames).map { t ->
            val time = t / fps
            val shift = ampPx * sin(2 * PI * freq * time)
            val fl = flickerHz?.let { 6 * sin(2 * PI * it * time) } ?: 0.0
            ByteArray(w * h) { i ->
                val x = i % w; val y = i / w
                val base = if (x < w / 2) {
                    val edge = 16.0 + shift
                    40 + 160 / (1 + kotlin.math.exp(-(x - edge) / 1.2))
                } else {
                    120 + 40 * sin(x * 0.7 + y * 0.4)
                }
                (base + fl + r.nextDouble(-noise, noise)).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayVideo(w, h, fps, list)
    }

    @Test fun findsInvisibleVibration() {
        val r = MotionAnalyzer.analyze(video(), magnify = false)
        assertNotNull(r.notes.toString(), r.dominantHz)
        assertEquals(3.0, r.dominantHz!!, 0.05)
        assertEquals(Quality.OK, r.quality)
        // Harita: kenar sütunlarında hareket, sağ yarıda yok
        val edgeMean = (0 until h).map { y -> (14..18).map { x -> r.motionMap[y * w + x] }.average() }.average()
        val staticMean = (0 until h).map { y -> (40..60).map { x -> r.motionMap[y * w + x] }.average() }.average()
        assertTrue("kenar=$edgeMean durağan=$staticMean", edgeMean > 5 * staticMean)
    }

    @Test fun magnificationEnlargesTheMotion() {
        val v = video()
        val r = MotionAnalyzer.analyze(v, magnify = true)
        val m = r.magnified!!
        fun swing(frames: List<ByteArray>, x: Int, y: Int) =
            frames.maxOf { it[y * w + x].toInt() and 0xFF } - frames.minOf { it[y * w + x].toInt() and 0xFF }
        val before = swing(v.frames, 16, 18)
        val after = swing(m, 16, 18)
        assertTrue("önce=$before sonra=$after", after > 3 * before)
        // Durağan bölge neredeyse aynı kalmalı
        val d = (0 until v.frames.size).maxOf { abs((m[it][18 * w + 50].toInt() and 0xFF) - (v.frames[it][18 * w + 50].toInt() and 0xFF)) }
        assertTrue("durağan bölge değişimi $d", d < 15)
    }

    @Test fun lightingFlickerIsWarnedAndNotMistakenForMotion() {
        val r = MotionAnalyzer.analyze(video(ampPx = 0.0, flickerHz = 10.0), magnify = false)
        assertTrue(r.notes.toString(), r.notes.any { "titremesi" in it })
        assertTrue("titreme hareket sanılmamalı: ${r.dominantHz}", r.dominantHz == null || abs(r.dominantHz!! - 10.0) > 0.3)
    }

    @Test fun staticSceneHasNoDominantFrequency() {
        val r = MotionAnalyzer.analyze(video(ampPx = 0.0), magnify = false)
        assertEquals(Quality.UNRELIABLE, r.quality)
    }

    @Test fun tooShortVideoIsRejected() {
        val r = MotionAnalyzer.analyze(video(frames = 40), magnify = false)
        assertEquals(Quality.UNRELIABLE, r.quality)
    }
}

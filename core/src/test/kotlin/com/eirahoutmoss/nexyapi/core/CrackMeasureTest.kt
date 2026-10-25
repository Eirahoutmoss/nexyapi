package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.analysis.CrackMeasure
import com.eirahoutmoss.nexyapi.core.analysis.Homography
import com.eirahoutmoss.nexyapi.core.analysis.Pt
import com.eirahoutmoss.nexyapi.core.analysis.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrackMeasureTest {

    /**
     * Düzlem (mm) -> görüntü (px): hafif eğik çekim benzetimi. 12 MP bir telefonla
     * ~10 cm genişliğinde bir alan çekildiğinde yaklaşık 40 piksel/mm elde edilir.
     */
    private val camera = Homography.fromPoints(
        listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)),
        listOf(Pt(100.0, 80.0), Pt(3900.0, 200.0), Pt(4000.0, 3950.0), Pt(60.0, 3800.0)),
    )!!

    private fun img(xMm: Double, yMm: Double) = camera.map(Pt(xMm, yMm))

    /** 20 mm referans kare (40,40)-(60,60) mm, çatlak kenarları (65,50)-(65,50.6). */
    private val corners = listOf(img(40.0, 40.0), img(60.0, 40.0), img(60.0, 60.0), img(40.0, 60.0))

    @Test fun measuresThroughPerspective() {
        val o = CrackMeasure.measure(corners, 20.0, img(65.0, 50.0), img(65.0, 50.6))
        val r = (o as CrackMeasure.Outcome.Ok).result
        assertEquals(0.6, r.widthMm, 0.005)
        assertEquals(Quality.OK, r.quality)
        assertTrue("±${r.uncertaintyMm}", r.uncertaintyMm < 0.08)
    }

    @Test fun tapOrderOfCornersDoesNotMatter() {
        val shuffled = listOf(corners[2], corners[0], corners[3], corners[1])
        val r = (CrackMeasure.measure(shuffled, 20.0, img(65.0, 50.0), img(65.0, 50.6)) as CrackMeasure.Outcome.Ok).result
        assertEquals(0.6, r.widthMm, 0.005)
    }

    @Test fun smallReferenceLowersQuality() {
        // Aynı sahne 5 kat uzaktan: referans ~150 piksellik sınırın altında
        val far = corners.map { Pt(it.x / 5, it.y / 5) }
        val r = (CrackMeasure.measure(far, 20.0, Pt(img(65.0, 50.0).x / 5, img(65.0, 50.0).y / 5),
            Pt(img(65.0, 50.6).x / 5, img(65.0, 50.6).y / 5)) as CrackMeasure.Outcome.Ok).result
        assertTrue(r.quality != Quality.OK)
    }

    @Test fun degenerateCornersAreRejected() {
        val line = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(200.0, 0.0), Pt(300.0, 0.0))
        assertTrue(CrackMeasure.measure(line, 20.0, Pt(1.0, 1.0), Pt(2.0, 2.0)) is CrackMeasure.Outcome.Error)
        assertNull(Homography.fromPoints(line, line))
    }

    @Test fun distantReferenceIsFlagged() {
        val r = (CrackMeasure.measure(corners, 20.0, img(98.0, 98.0), img(98.0, 98.5)) as CrackMeasure.Outcome.Ok).result
        // 98,98 noktası referansa ~2 kenar mesafede: uyarı yok; daha uzak bir sahne kur:
        assertTrue(r.widthMm in 0.45..0.55)
        val small = listOf(img(0.0, 0.0), img(5.0, 0.0), img(5.0, 5.0), img(0.0, 5.0))
        val r2 = (CrackMeasure.measure(small, 5.0, img(90.0, 90.0), img(90.0, 90.5)) as CrackMeasure.Outcome.Ok).result
        assertTrue(r2.notes.joinToString(), r2.notes.any { "uzak" in it })
    }
}

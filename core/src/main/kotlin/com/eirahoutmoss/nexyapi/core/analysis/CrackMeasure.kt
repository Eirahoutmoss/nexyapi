package com.eirahoutmoss.nexyapi.core.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class Pt(val x: Double, val y: Double)

/** 3x3 izdüşüm dönüşümü (homografi): görüntü pikseli -> düzlemde milimetre. */
class Homography private constructor(private val h: DoubleArray) {

    fun map(p: Pt): Pt {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        return Pt((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
    }

    companion object {
        /** Dört nokta eşlemesinden homografi; noktalar dejenereyse (üçü aynı doğrudaysa) null. */
        fun fromPoints(src: List<Pt>, dst: List<Pt>): Homography? {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]
                val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val sol = solve(a) ?: return null
            return Homography(doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0))
        }

        /** Kısmi pivotlamalı Gauss eliminasyonu; son sütun sağ taraftır. */
        private fun solve(m: Array<DoubleArray>): DoubleArray? {
            val n = m.size
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
                if (abs(m[piv][c]) < 1e-12) return null
                val t = m[c]; m[c] = m[piv]; m[piv] = t
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = m[r][c] / m[c][c]
                    if (f == 0.0) continue
                    for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}

data class CrackResult(
    val widthMm: Double,
    /** Tahmini belirsizlik (±mm), dokunma hatasından. */
    val uncertaintyMm: Double,
    /** Referans karenin görüntüdeki ortalama kenar uzunluğu (piksel). */
    val referenceSidePx: Double,
    val quality: Quality,
    val notes: List<String>,
)

/**
 * Yarı otomatik çatlak genişliği ölçümü.
 *
 * Kullanıcı fotoğrafta (1) bilinen boyuttaki referans karenin dört köşesine,
 * (2) çatlağın iki kenarına (genişlik boyunca, karşılıklı) dokunur. Dört köşe ile
 * perspektif düzeltilir; böylece fotoğraf hafif eğik çekilse bile ölçü korunur.
 * Varsayım: referans etiket çatlakla aynı düzlemde (aynı duvar yüzeyinde) ve ona yakındır.
 */
object CrackMeasure {

    data class Config(
        /** Büyüteçle dokunmada beklenen hata (piksel). */
        val tapErrorPx: Double = 1.5,
        val minReferenceSidePx: Double = 150.0,
        val okUncertaintyMm: Double = 0.08,
        val lowUncertaintyMm: Double = 0.2,
        /** Referans ile çatlak arasındaki uzaklık bu kadar kenar boyunu aşarsa uyarılır. */
        val maxDistanceInSides: Double = 4.0,
    )

    sealed interface Outcome {
        data class Ok(val result: CrackResult) : Outcome
        data class Error(val message: String) : Outcome
    }

    /** Köşeleri ağırlık merkezine göre açıyla sıralar; dokunma sırası önemli olmasın. */
    fun orderCorners(c: List<Pt>): List<Pt> {
        val cx = c.sumOf { it.x } / c.size
        val cy = c.sumOf { it.y } / c.size
        return c.sortedBy { atan2(it.y - cy, it.x - cx) }
    }

    fun measure(
        corners: List<Pt>,
        referenceSideMm: Double,
        edgeA: Pt,
        edgeB: Pt,
        cfg: Config = Config(),
    ): Outcome {
        if (corners.size != 4) return Outcome.Error("Referans karenin dört köşesi gerekli.")
        val ordered = orderCorners(corners)
        val sides = (0 until 4).map { i -> dist(ordered[i], ordered[(i + 1) % 4]) }
        val minSide = sides.min()
        val maxSide = sides.max()
        if (minSide < 10) return Outcome.Error("Köşeler birbirine çok yakın.")
        if (maxSide / minSide > 3.0) return Outcome.Error("Köşeler bir kare oluşturmuyor; yeniden yerleştirin.")
        if (!isConvex(ordered)) return Outcome.Error("Köşeler bir kare oluşturmuyor; yeniden yerleştirin.")

        val s = referenceSideMm
        val world = listOf(Pt(0.0, 0.0), Pt(s, 0.0), Pt(s, s), Pt(0.0, s))
        val h = Homography.fromPoints(ordered, world) ?: return Outcome.Error("Köşelerden ölçek hesaplanamadı.")

        val a = h.map(edgeA)
        val b = h.map(edgeB)
        val width = dist(a, b)

        // Belirsizlik: iki kenar noktasının dokunma hatası + referans köşelerinin ölçek hatası.
        val mid = Pt((edgeA.x + edgeB.x) / 2, (edgeA.y + edgeB.y) / 2)
        val mmPerPx = localScale(h, mid)
        val pointErr = sqrt(2.0) * cfg.tapErrorPx * mmPerPx
        val sidePx = sides.average()
        val scaleErr = width * (sqrt(2.0) * cfg.tapErrorPx / sidePx)
        val uncertainty = sqrt(pointErr * pointErr + scaleErr * scaleErr)

        val notes = ArrayList<String>()
        var q = Quality.OK
        fun lower(to: Quality) { if (to.ordinal > q.ordinal) q = to }

        if (sidePx < cfg.minReferenceSidePx) {
            notes += "Referans etiket fotoğrafta küçük kalmış; telefonu yaklaştırarak çekin."
            lower(Quality.LOW)
        }
        when {
            uncertainty > cfg.lowUncertaintyMm -> {
                notes += "Ölçüm belirsizliği yüksek: telefonu yaklaştırın; çatlak ile etiket kareyi dolduracak kadar (yaklaşık 10 cm'lik alan) yakın çekin."
                lower(Quality.UNRELIABLE)
            }
            uncertainty > cfg.okUncertaintyMm -> {
                notes += "Ölçüm belirsizliği orta düzeyde; daha yakından çekmek sonucu iyileştirir."
                lower(Quality.LOW)
            }
        }
        val refCenter = Pt(ordered.sumOf { it.x } / 4, ordered.sumOf { it.y } / 4)
        if (dist(refCenter, mid) > cfg.maxDistanceInSides * sidePx) {
            notes += "Referans etiket çatlaktan uzak; etiketi çatlağın hemen yanına yapıştırın."
            lower(Quality.LOW)
        }
        val distortion = maxSide / minSide
        if (distortion > 1.6) {
            notes += "Fotoğraf duvara çok eğik çekilmiş; mümkünse karşıdan çekin."
            lower(Quality.LOW)
        }
        return Outcome.Ok(CrackResult(width, uncertainty, sidePx, q, notes))
    }

    private fun localScale(h: Homography, p: Pt): Double {
        val o = h.map(p)
        val dx = dist(o, h.map(Pt(p.x + 1, p.y)))
        val dy = dist(o, h.map(Pt(p.x, p.y + 1)))
        return (dx + dy) / 2
    }

    private fun isConvex(p: List<Pt>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun dist(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)
}

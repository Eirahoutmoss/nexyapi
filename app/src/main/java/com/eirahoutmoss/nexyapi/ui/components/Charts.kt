package com.eirahoutmoss.nexyapi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

private fun num(v: Double, decimals: Int) = String.format(Locale.forLanguageTag("tr"), "%.${decimals}f", v)

/**
 * Güç spektrumu: yatay eksen frekans (Hz), dikey eksen logaritmik güç.
 * [peakHz] dikey kesikli çizgiyle işaretlenir.
 */
@Composable
fun SpectrumChart(
    freqs: DoubleArray,
    power: DoubleArray,
    peakHz: Double?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (freqs.size < 2) return@Canvas
        val left = 8f; val bottom = size.height - 22f; val top = 8f; val right = size.width - 8f
        val fMin = freqs.first(); val fMax = freqs.last()
        val logs = DoubleArray(power.size) { log10(max(power[it], 1e-20)) }
        val lMin = logs.min(); val lMax = logs.max().let { if (it - lMin < 1e-9) lMin + 1 else it }
        fun x(f: Double) = (left + (f - fMin) / (fMax - fMin) * (right - left)).toFloat()
        fun y(l: Double) = (bottom - (l - lMin) / (lMax - lMin) * (bottom - top)).toFloat()

        // dikey ızgara: tam sayı Hz
        var g = kotlin.math.ceil(fMin)
        val step = if (fMax - fMin > 10) 2.0 else 1.0
        while (g <= fMax) {
            drawLine(grid, Offset(x(g), top), Offset(x(g), bottom), strokeWidth = 1f)
            val t = measurer.measure(num(g, 0), labelStyle)
            drawText(t, topLeft = Offset(x(g) - t.size.width / 2f, bottom + 4f))
            g += step
        }
        val path = Path()
        for (i in freqs.indices) {
            val px = x(freqs[i]); val py = y(logs[i])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.5f))
        if (peakHz != null && peakHz in fMin..fMax) {
            drawLine(
                color, Offset(x(peakHz), top), Offset(x(peakHz), bottom), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            val t = measurer.measure("${num(peakHz, 2)} Hz", labelStyle.copy(color = color, fontSize = 12.sp))
            val tx = (x(peakHz) + 6f).coerceAtMost(right - t.size.width)
            drawText(t, topLeft = Offset(tx, top))
        }
    }
}

/** Zaman içindeki değerler (geçmiş grafiği). [points]: (epoch ms, değer). */
@Composable
fun HistoryChart(
    points: List<Pair<Long, Double>>,
    unit: String,
    decimals: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        if (points.isEmpty()) return@Canvas
        val sorted = points.sortedBy { it.first }
        val left = 8f; val right = size.width - 8f; val top = 22f; val bottom = size.height - 22f
        val tMin = sorted.first().first; val tMax = sorted.last().first
        var vMin = sorted.minOf { it.second }; var vMax = sorted.maxOf { it.second }
        // Küçük farklar abartılı görünmesin: aralığı en az değerin ±%5'i kadar tut.
        val minSpan = max(kotlin.math.abs(vMax) * 0.1, 1e-6)
        if (vMax - vMin < minSpan) { val mid = (vMax + vMin) / 2; vMin = mid - minSpan / 2; vMax = mid + minSpan / 2 }
        fun x(t: Long) = if (tMax == tMin) (left + right) / 2 else (left + (t - tMin).toFloat() / (tMax - tMin) * (right - left))
        fun y(v: Double) = (bottom - (v - vMin) / (vMax - vMin) * (bottom - top)).toFloat()

        listOf(vMin, (vMin + vMax) / 2, vMax).forEach { v ->
            drawLine(grid, Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1f)
            val t = measurer.measure("${num(v, decimals)} $unit", labelStyle)
            drawText(t, topLeft = Offset(left, y(v) - t.size.height - 1f))
        }
        val path = Path()
        sorted.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        if (sorted.size > 1) drawPath(path, color, style = Stroke(width = 2.5f))
        sorted.forEach { (t, v) -> drawCircle(color, radius = 5f, center = Offset(x(t), y(v))) }
        val first = measurer.measure(formatDate(tMin), labelStyle)
        drawText(first, topLeft = Offset(left, bottom + 4f))
        if (tMax != tMin) {
            val last = measurer.measure(formatDate(tMax), labelStyle)
            drawText(last, topLeft = Offset(right - last.size.width, bottom + 4f))
        }
    }
}

/** Frekans yanıtı: logaritmik frekans ekseni (Hz), dB. [marks] dikey kesikli çizgilerle işaretlenir. */
@Composable
fun ResponseChart(
    freqs: DoubleArray,
    db: DoubleArray,
    color: Color,
    marks: List<Double> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (freqs.size < 2) return@Canvas
        val left = 8f; val right = size.width - 8f; val top = 8f; val bottom = size.height - 22f
        val lfMin = log10(freqs.first()); val lfMax = log10(freqs.last())
        val dMax = 0.0; val dMin = max(db.min(), -50.0)
        fun x(f: Double) = (left + (log10(f) - lfMin) / (lfMax - lfMin) * (right - left)).toFloat()
        fun y(d: Double) = (bottom - (d.coerceAtLeast(dMin) - dMin) / (dMax - dMin) * (bottom - top)).toFloat()
        listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0).filter { it in freqs.first()..freqs.last() }.forEach { f ->
            drawLine(grid, Offset(x(f), top), Offset(x(f), bottom), strokeWidth = 1f)
            val t = measurer.measure(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", labelStyle)
            drawText(t, topLeft = Offset(x(f) - t.size.width / 2f, bottom + 4f))
        }
        marks.forEach { f ->
            if (f in freqs.first()..freqs.last()) drawLine(
                Color(0xFFE5484D), Offset(x(f), top), Offset(x(f), bottom), strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        val path = Path()
        for (i in freqs.indices) { val px = x(freqs[i]); val py = y(db[i]); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        drawPath(path, color, style = Stroke(width = 2.5f))
    }
}

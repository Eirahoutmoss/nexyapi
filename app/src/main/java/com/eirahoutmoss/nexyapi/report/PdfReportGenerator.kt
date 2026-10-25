package com.eirahoutmoss.nexyapi.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import com.eirahoutmoss.nexyapi.core.model.Disclaimer
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.MeasurementEntity
import com.eirahoutmoss.nexyapi.data.MeasurementQuality
import com.eirahoutmoss.nexyapi.data.StructureEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GeneratedReport(
    val file: File,
    val fingerprint: String,
    val generatedAt: Long,
    /** Parmak izinin hesaplandığı kanonik metin; doğrulamada neyin değiştiğini göstermek için saklanır. */
    val canonical: String,
)

/** Pro raporda başlık ve kapakta görünen büro bilgileri. */
data class ReportBranding(
    val officeName: String,
    val preparedBy: String,
    val preparedByTitle: String,
    val contact: String,
    val logo: Bitmap?,
)

/** Rapor verisinden kanonik metin (üretimde ve doğrulamada aynı kural). */
object ReportCanonical {
    fun build(
        structure: StructureEntity,
        measurements: List<MeasurementEntity>,
        holder: String,
        appVersion: String,
        generatedAt: Long,
    ): String = ReportFingerprint.canonical(
        structure = ReportFingerprint.StructurePart(
            id = structure.id,
            name = structure.name,
            address = structure.address,
            floor = structure.floor,
            buildYear = structure.buildYear,
        ),
        measurements = measurements.map {
            ReportFingerprint.MeasurementPart(
                id = it.id,
                moduleCode = it.moduleCode,
                takenAtEpochMs = it.takenAt,
                value = it.value,
                unit = it.unit,
                deviceModel = it.deviceModel,
                dataSha256 = it.dataSha256,
                subject = it.subject ?: "",
            )
        },
        generation = ReportFingerprint.GenerationPart(appVersion, generatedAt, holder),
    )
}

/**
 * A4 PDF rapor üretir. Her sayfanın altında sorumluluk notu ve rapor parmak izi bulunur.
 * Sayfa sayısını doğru yazmak için yerleşim iki kez yapılır: önce sayım, sonra çizim.
 */
class PdfReportGenerator {

    fun generate(
        structure: StructureEntity,
        measurements: List<MeasurementEntity>,
        holder: String,
        isFreePlan: Boolean,
        appVersion: String,
        generatedAt: Long,
        outFile: File,
        coverPhoto: Bitmap? = null,
        branding: ReportBranding? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): GeneratedReport {
        val canonical = ReportCanonical.build(structure, measurements, holder, appVersion, generatedAt)
        val fingerprint = Sha256.hex(canonical)

        val ctx = Ctx(structure, measurements, holder, isFreePlan, appVersion, generatedAt, fingerprint, zone, coverPhoto, branding)
        val pageCount = Layout(null, ctx, totalPages = 0).run()

        outFile.parentFile?.mkdirs()
        val tmp = File(outFile.parentFile, outFile.name + ".part")
        val doc = PdfDocument()
        try {
            Layout(doc, ctx, totalPages = pageCount).run()
            tmp.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        outFile.delete()
        check(tmp.renameTo(outFile)) { "Rapor dosyası kaydedilemedi." }
        return GeneratedReport(outFile, fingerprint, generatedAt, canonical)
    }

    private class Ctx(
        val structure: StructureEntity,
        val measurements: List<MeasurementEntity>,
        val holder: String,
        val isFreePlan: Boolean,
        val appVersion: String,
        val generatedAt: Long,
        val fingerprint: String,
        val zone: ZoneId,
        val photo: Bitmap?,
        val branding: ReportBranding?,
    )

    /** doc == null ise yalnızca sayfa sayar. */
    private class Layout(private val doc: PdfDocument?, private val c: Ctx, private val totalPages: Int) {
        private val pageW = 595
        private val pageH = 842
        private val margin = 40f
        private val contentW = pageW - 2 * margin
        private val headerH = 56f
        private val footerTop = pageH - 62f

        private val navy = Color.rgb(0x1E, 0x3A, 0x5F)
        private val orange = Color.rgb(0xE0, 0x7A, 0x3F)
        private val amber = Color.rgb(0xF2, 0xB1, 0x34)
        private val grey = Color.rgb(0x5F, 0x66, 0x6D)
        private val line = Color.rgb(0xD5, 0xD8, 0xDC)

        private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val mono = Typeface.MONOSPACE

        private fun paint(size: Float, color: Int = Color.BLACK, tf: Typeface = sans) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf }

        private val body = paint(10f)
        private val bodyBold = paint(10f, tf = sansBold)
        private val small = paint(8f, grey)
        private val h1 = paint(16f, navy, sansBold)
        private val h2 = paint(12f, navy, sansBold)

        private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr"))

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNo = 0
        private var y = 0f

        fun run(): Int {
            newPage()
            cover()
            measurementsSection()
            finishPage()
            return pageNo
        }

        // ---------- sayfa yönetimi ----------

        private fun newPage() {
            pageNo++
            if (doc != null) {
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
                page = doc.startPage(info)
                canvas = page!!.canvas
            }
            header()
            y = headerH + 28f
        }

        private fun finishPage() {
            footer()
            page?.let { doc?.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensure(space: Float) {
            if (y + space > footerTop - 10f) {
                finishPage()
                newPage()
            }
        }

        // ---------- çizim yardımcıları ----------

        private fun text(s: String, x: Float, yy: Float, p: Paint) {
            canvas?.drawText(s, x, yy, p)
        }

        private fun ellipsize(s: String, p: Paint, maxW: Float): String {
            if (p.measureText(s) <= maxW) return s
            var e = s
            while (e.length > 1 && p.measureText("$e…") > maxW) e = e.dropLast(1)
            return "$e…"
        }

        /** Metni genişliğe göre satırlara böler; çok uzun tek kelimeyi de böler. */
        private fun wrap(s: String, p: Paint, width: Float): List<String> {
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                if (para.isBlank()) { out.add(""); continue }
                var cur = StringBuilder()
                for (word in para.split(' ')) {
                    var w = word
                    while (p.measureText(w) > width && w.length > 1) {
                        var cut = w.length - 1
                        while (cut > 1 && p.measureText(w, 0, cut) > width) cut--
                        if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                        out.add(w.substring(0, cut))
                        w = w.substring(cut)
                    }
                    val trial = if (cur.isEmpty()) w else "$cur $w"
                    if (p.measureText(trial) <= width) {
                        cur = StringBuilder(trial)
                    } else {
                        out.add(cur.toString())
                        cur = StringBuilder(w)
                    }
                }
                out.add(cur.toString())
            }
            return out
        }

        private fun paragraph(s: String, p: Paint, x: Float = margin, width: Float = contentW, lineGap: Float = 1.35f) {
            val lh = p.textSize * lineGap
            for (l in wrap(s, p, width)) {
                ensure(lh)
                y += lh
                text(l, x, y, p)
            }
        }

        private fun hr(color: Int = line) {
            canvas?.drawLine(margin, y, pageW - margin, y, Paint().apply { this.color = color; strokeWidth = 0.7f })
        }

        private fun header() {
            canvas?.drawRect(0f, 0f, pageW.toFloat(), headerH, Paint().apply { color = navy })
            canvas?.drawRect(0f, headerH, pageW.toFloat(), headerH + 3f, Paint().apply { color = orange })
            val b = c.branding
            if (b != null) {
                var x = margin
                b.logo?.takeIf { it.width > 0 && it.height > 0 }?.let { logo ->
                    // Logo beyaz bir kutu içinde: koyu başlıkta her renkte logo okunur.
                    val h = 40f
                    val w = (h * logo.width / logo.height).coerceAtMost(120f)
                    canvas?.drawRoundRect(RectF(x - 3f, 8f - 3f, x + w + 3f, 8f + h + 3f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                    canvas?.drawBitmap(logo, null, RectF(x, 8f, x + w, 8f + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    x += w + 12f
                }
                text(ellipsize(b.officeName, paint(15f, Color.WHITE, sansBold), 300f), x, 34f, paint(15f, Color.WHITE, sansBold))
            } else {
                text("NexYapı", margin, 35f, paint(18f, Color.WHITE, sansBold))
            }
            val t = "Yapı Ölçüm Raporu"
            val tp = paint(11f, Color.WHITE)
            text(t, pageW - margin - tp.measureText(t), 34f, tp)
        }

        private fun footer() {
            val top = footerTop
            canvas?.drawLine(margin, top, pageW - margin, top, Paint().apply { color = line; strokeWidth = 0.7f })
            text("Bu belge bir ölçüm kaydıdır; yapı güvenliğine ilişkin bir değerlendirme değildir.", margin, top + 13f, small)
            text("Rapor parmak izi (SHA-256, ${ReportFingerprint.FORMAT}):", margin, top + 25f, small)
            text(Sha256.grouped(c.fingerprint), margin, top + 36f, paint(7.5f, Color.BLACK, mono))
            val left = when {
                c.isFreePlan -> "NexYapı Ücretsiz sürüm · v${c.appVersion}"
                c.branding != null -> "NexYapı ile hazırlanmıştır · v${c.appVersion}"
                else -> "NexYapı Profesyonel · v${c.appVersion}"
            }
            text(left, margin, top + 48f, small)
            val pg = if (totalPages > 0) "Sayfa $pageNo / $totalPages" else "Sayfa $pageNo"
            text(pg, pageW - margin - small.measureText(pg), top + 48f, small)
        }

        private fun fmt(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(c.zone))

        // ---------- içerik ----------

        private fun cover() {
            y += 4f
            text(c.structure.name, margin, y, h1)
            y += 8f

            c.photo?.takeIf { it.width > 0 && it.height > 0 }?.let { bmp ->
                val maxH = 210f
                var w = contentW
                var h = w * bmp.height / bmp.width
                if (h > maxH) { h = maxH; w = h * bmp.width / bmp.height }
                y += 6f
                val left = margin + (contentW - w) / 2
                canvas?.drawBitmap(bmp, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                y += h + 4f
            }

            val rows = buildList {
                if (c.structure.address.isNotBlank()) add("Adres" to c.structure.address)
                if (c.structure.floor.isNotBlank()) add("Kat" to c.structure.floor)
                c.structure.buildYear?.let { add("Yapım yılı" to it.toString()) }
                add("Rapor tarihi" to "${fmt(c.generatedAt)} (${c.zone.id})")
                val b = c.branding
                if (b != null) {
                    add("Hazırlayan" to listOf(b.preparedBy, b.preparedByTitle).filter { it.isNotBlank() }.joinToString(", ").ifBlank { b.officeName })
                    if (b.preparedBy.isNotBlank()) add("Büro" to b.officeName)
                    if (b.contact.isNotBlank()) add("İletişim" to b.contact)
                } else {
                    add("Hazırlayan" to c.holder)
                }
                add("Ölçüm sayısı" to c.measurements.size.toString())
            }
            for ((k, v) in rows) {
                val lines = wrap(v, body, contentW - 110f)
                ensure(lines.size * 14f + 2f)
                y += 14f
                text(k, margin, y, bodyBold)
                lines.forEachIndexed { i, l ->
                    if (i > 0) y += 14f
                    text(l, margin + 110f, y, body)
                }
            }
            if (c.structure.notes.isNotBlank()) {
                y += 10f
                ensure(30f)
                y += 12f
                text("Notlar", margin, y, bodyBold)
                paragraph(c.structure.notes, body)
            }

            // Sorumluluk kutusu: kapakta her zaman, kaldırılamaz.
            y += 16f
            val boxLines = wrap(Disclaimer.REPORT, body, contentW - 24f)
            val boxH = 22f + boxLines.size * 13.5f + 8f
            ensure(boxH + 4f)
            val r = RectF(margin, y, pageW - margin, y + boxH)
            canvas?.drawRoundRect(r, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0xF6, 0xE0) })
            canvas?.drawRoundRect(r, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = amber; style = Paint.Style.STROKE; strokeWidth = 1.2f
            })
            text("Önemli", margin + 12f, y + 17f, bodyBold)
            var by = y + 17f
            for (l in boxLines) { by += 13.5f; text(l, margin + 12f, by, body) }
            y += boxH + 8f
        }

        private fun measurementsSection() {
            y += 14f
            ensure(40f)
            y += 14f
            text("Ölçümler", margin, y, h2)
            y += 6f

            if (c.measurements.isEmpty()) {
                paragraph("Bu yapı için henüz ölçüm kaydı yok.", body)
                return
            }

            val groups = c.measurements.groupBy { it.moduleCode }
            // Modüller her zaman aynı sırada (ModuleType sırası); bilinmeyen kodlar en sonda.
            val ordered = ModuleType.entries.mapNotNull { m -> groups[m.code]?.let { m.code to it } } +
                groups.filterKeys { ModuleType.fromCode(it) == null }.toList()

            val colDate = margin
            val colSummary = margin + 92f
            val colQuality = margin + 330f
            val colDevice = margin + 395f
            val summaryW = colQuality - colSummary - 8f
            val deviceW = pageW - margin - colDevice

            for ((code, items) in ordered) {
                val module = ModuleType.fromCode(code)
                ensure(60f)
                y += 18f
                text(module?.let { "${it.code} · ${it.title}" } ?: code, margin, y, bodyBold)
                module?.limitationNote?.let { paragraph(it, small, lineGap = 1.3f) }
                y += 6f
                hr()
                y += 11f
                text("Tarih", colDate, y, small)
                text("Sonuç", colSummary, y, small)
                text("Kalite", colQuality, y, small)
                text("Cihaz", colDevice, y, small)
                y += 4f
                hr()

                for (m in items.sortedBy { it.takenAt }) {
                    val sumText = m.summary.ifBlank { "—" } + (m.subject?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                    val sum = wrap(sumText, body, summaryW)
                    val dev = wrap(m.deviceModel, small, deviceW)
                    val rowLines = maxOf(sum.size, dev.size)
                    val rowH = rowLines * 13f + 6f
                    ensure(rowH)
                    val top = y
                    text(fmt(m.takenAt), colDate, top + 13f, body)
                    sum.forEachIndexed { i, l -> text(l, colSummary, top + 13f + i * 13f, body) }
                    text(qualityLabel(m.quality), colQuality, top + 13f, body)
                    dev.forEachIndexed { i, l -> text(l, colDevice, top + 13f + i * 13f, small) }
                    y = top + rowH
                    hr()
                }
            }
        }

        private fun qualityLabel(q: MeasurementQuality) = when (q) {
            MeasurementQuality.OK -> "Normal"
            MeasurementQuality.LOW -> "Düşük"
            MeasurementQuality.UNRELIABLE -> "Güvenilmez"
        }
    }
}

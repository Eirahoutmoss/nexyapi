package com.eirahoutmoss.nexyapi.report

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.IOException

/**
 * Çatlak İzleyici (V3) için yazdırılabilir referans etiket sayfası (A4).
 *
 * Her etiket, kenarı tam [SIDE_MM] mm olan siyah bir karedir; kullanıcı fotoğrafta bu karenin
 * dört köşesine dokunur. Sayfada 100 mm'lik bir kontrol cetveli vardır: yazıcı ölçeği
 * bozarsa (ör. "sayfaya sığdır") cetvelle fark edilir.
 *
 * PDF birimi 1/72 inçtir; 1 mm = 72 / 25,4 nokta.
 */
object ReferenceLabelPdf {
    const val SIDE_MM = 20.0
    private const val PT_PER_MM = 72.0 / 25.4

    private fun mm(v: Double) = (v * PT_PER_MM).toFloat()

    fun generate(target: File) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val c = page.canvas
            val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
            val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x99, 0x99, 0x99); style = Paint.Style.STROKE; strokeWidth = 0.4f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f) }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; typeface = Typeface.SANS_SERIF }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f; typeface = Typeface.SANS_SERIF; color = Color.DKGRAY }

            c.drawText("NexYapı · Çatlak İzleyici referans etiketleri", mm(15.0), mm(18.0), title)
            listOf(
                "1. Bu sayfayı \"Gerçek boyut\" / \"%100\" ölçekle yazdırın (\"sayfaya sığdır\" SEÇMEYİN).",
                "2. Aşağıdaki 100 mm çizgisini bir cetvelle ölçün. 99–101 mm değilse etiketleri kullanmayın.",
                "3. Bir etiketi kesin ve çatlağın hemen yanına, aynı duvar yüzeyine düz yapıştırın.",
                "4. Etiketi her ölçümde yerinde bırakın; fotoğrafı duvara mümkün olduğunca karşıdan çekin.",
            ).forEachIndexed { i, l -> c.drawText(l, mm(15.0), mm(27.0 + i * 5.5), text) }

            // 100 mm kontrol cetveli
            val ry = mm(58.0)
            val rx0 = mm(15.0)
            c.drawRect(rx0, ry, rx0 + mm(100.0), ry + mm(0.6), black)
            for (k in 0..100) {
                val h = when { k % 10 == 0 -> 4.0; k % 5 == 0 -> 2.8; else -> 1.6 }
                c.drawRect(rx0 + mm(k.toDouble()) - mm(0.12), ry - mm(h), rx0 + mm(k.toDouble()) + mm(0.12), ry, black)
                if (k % 10 == 0) c.drawText("$k", rx0 + mm(k.toDouble()) - 3f, ry - mm(5.0), small)
            }
            c.drawText("100 mm kontrol cetveli", rx0 + mm(104.0), ry + 2f, small)

            // Etiketler: 3 sütun x 5 satır; her hücre 55 x 42 mm, kesim çizgili
            val cols = 3; val rows = 5
            val cellW = 55.0; val cellH = 42.0
            val ox = (210.0 - cols * cellW) / 2; val oy = 72.0
            for (r in 0 until rows) for (col in 0 until cols) {
                val x0 = ox + col * cellW; val y0 = oy + r * cellH
                c.drawRect(mm(x0), mm(y0), mm(x0 + cellW), mm(y0 + cellH), thin)
                val sx = x0 + (cellW - SIDE_MM) / 2; val sy = y0 + 7.0
                c.drawRect(mm(sx), mm(sy), mm(sx + SIDE_MM), mm(sy + SIDE_MM), black)
                c.drawText("NexYapı · ${SIDE_MM.toInt()} mm referans", mm(x0 + 6.0), mm(sy + SIDE_MM + 6.0), small)
            }
            c.drawText(
                "Etiketin kenarı tam ${SIDE_MM.toInt()},0 mm'dir. Yazıcı ölçeği bozulursa ölçümler yanlış çıkar.",
                mm(15.0), mm(287.0), small,
            )
            doc.finishPage(page)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.outputStream().use { doc.writeTo(it) }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Etiket dosyası kaydedilemedi.")
        } finally {
            doc.close()
        }
    }
}

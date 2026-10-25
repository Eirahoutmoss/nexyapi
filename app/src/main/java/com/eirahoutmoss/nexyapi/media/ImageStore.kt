package com.eirahoutmoss.nexyapi.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/**
 * Fotoğraf alma ve saklama.
 *  - Kamera/galeri görüntüsü EXIF yönüne göre döndürülür (yan yatık fotoğraf kalmaz).
 *  - En uzun kenar [maxDimension]'a indirilir, JPEG olarak önce geçici dosyaya yazılır, sonra yerine taşınır.
 *  - Bellek taşmasın diye çözümleme örneklenerek (inSampleSize) yapılır.
 */
class ImageStore(private val context: Context) {

    /** Kamera uygulamasının fotoğrafı yazacağı geçici dosya ve ona verilecek Uri. */
    fun newCaptureTarget(): Pair<File, Uri> {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val f = File(dir, "cap_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        return f to uri
    }

    /** [source] görüntüsünü düzeltip [target] dosyasına yazar. Başarısızsa IOException. */
    suspend fun importImage(source: Uri, target: File, maxDimension: Int, quality: Int = 90): File =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Not: inJustDecodeBounds ile decodeStream HER ZAMAN null döner (yalnızca boyutları doldurur);
            // bu yüzden akışın açılıp açılmadığını ayrıca denetliyoruz.
            val opened = cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
            if (!opened) throw IOException("Fotoğraf açılamadı.")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Dosya bir fotoğraf değil.")

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IOException("Fotoğraf çözümlenemedi.")

            val orientation = runCatching {
                cr.openInputStream(source)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            bmp = applyOrientation(bmp, orientation)

            val longest = max(bmp.width, bmp.height)
            if (longest > maxDimension) {
                val s = maxDimension.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }

            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                tmp.outputStream().use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("Fotoğraf kaydedilemedi.")
                }
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Fotoğraf kaydedilemedi.")
            } finally {
                tmp.delete()
                bmp.recycle()
            }
            target
        }

    /** Ekranda göstermek için en uzun kenarı en fazla [maxDimension] olan küçültülmüş görüntü. */
    suspend fun loadPreview(file: File, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Görüntünün gerçek piksel boyutu (çözümlemeden). */
    fun sizeOf(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0) o.outWidth to o.outHeight else null
    }

    /** Büyüteç için tam çözünürlükte bölge okuyucu. Kullanımdan sonra [BitmapRegionDecoder.recycle]. */
    @Suppress("DEPRECATION")
    fun regionDecoder(file: File): BitmapRegionDecoder? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(file.path)
        else BitmapRegionDecoder.newInstance(file.path, false)
    }.getOrNull()

    companion object {
        /** Tam çözünürlükte, [cx],[cy] merkezli [size] piksellik kare bölge. */
        fun decodeRegion(decoder: BitmapRegionDecoder, cx: Int, cy: Int, size: Int): Bitmap? {
            val half = size / 2
            val r = Rect(cx - half, cy - half, cx + half, cy + half)
            if (!r.intersect(0, 0, decoder.width, decoder.height)) return null
            return runCatching { decoder.decodeRegion(r, BitmapFactory.Options()) }.getOrNull()
        }

        private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return src
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            return out
        }
    }
}

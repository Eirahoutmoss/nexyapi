package com.eirahoutmoss.nexyapi.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.eirahoutmoss.nexyapi.core.analysis.Dsp
import com.eirahoutmoss.nexyapi.core.analysis.GrayVideo
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DecodedVideo(
    val gray: GrayVideo,
    /** Gösterim ve ısı haritası zemini için tek bir renkli kare. */
    val preview: Bitmap,
    val durationS: Double,
    /** Kare aralıklarının değişkenlik katsayısı (değişken kare hızlı videolarda büyür). */
    val frameJitter: Double,
    val videoSha256: String,
)

/**
 * Videodan analiz için küçültülmüş gri kareler çıkarır. Kare zaman damgaları MediaExtractor ile
 * (çözmeden) okunur; kareler MediaMetadataRetriever ile sırayla çözülür (Android 9+).
 * Bellek: kareler tek tek çözülüp hemen küçültülür; tam çözünürlüklü kare biriktirilmez.
 */
object VideoFrames {
    const val ANALYSIS_LONG_SIDE = 160
    const val MAX_FRAMES = 480

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit): DecodedVideo = withContext(Dispatchers.IO) {
        val times = frameTimesUs(context, uri)
        if (times.size < 2) throw IOException("Videodaki kareler okunamadı.")
        val dts = DoubleArray(times.size - 1) { (times[it + 1] - times[it]).toDouble() }
        val medianDt = Dsp.median(dts)
        if (medianDt <= 0) throw IOException("Videonun kare hızı okunamadı.")
        val fps = 1e6 / medianDt
        val mean = dts.average()
        val jitter = sqrt(dts.fold(0.0) { a, d -> a + (d - mean) * (d - mean) } / dts.size) / mean

        // Baştaki 1 sn (kayıt düğmesine dokunma sarsıntısı) ve sondaki 0,5 sn atılır.
        val skipStart = (fps * 1.0).roundToInt()
        val skipEnd = (fps * 0.5).roundToInt()
        val usable = (times.size - skipStart - skipEnd).coerceAtLeast(0)
        val count = minOf(usable, MAX_FRAMES)
        if (count < 2) throw IOException("Video çok kısa.")

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val total = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: times.size
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }

            var tw = 0; var th = 0
            val frames = ArrayList<ByteArray>(count)
            var preview: Bitmap? = null
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val index = (skipStart + i).coerceAtMost(total - 1)
                var bmp = mmr.getFrameAtIndex(index, params) ?: throw IOException("Kare $index çözülemedi.")
                // Bazı cihazlar döndürmeyi uygulamaz: kare hâlâ ham yöndeyse biz döndürürüz.
                if ((rotation == 90 || rotation == 270) && bmp.width == vw && bmp.width > bmp.height) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (r !== bmp) bmp.recycle()
                    bmp = r
                } else if (rotation == 180 && bmp.width == vw) {
                    val m = Matrix().apply { postRotate(180f) }
                    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (r !== bmp) bmp.recycle()
                    bmp = r
                }
                if (tw == 0) {
                    val s = ANALYSIS_LONG_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
                    tw = (bmp.width * s).roundToInt().coerceAtLeast(8); th = (bmp.height * s).roundToInt().coerceAtLeast(8)
                }
                if (preview == null) {
                    val s = 720f / maxOf(bmp.width, bmp.height)
                    preview = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
                        .copy(Bitmap.Config.ARGB_8888, false)
                }
                val small = Bitmap.createScaledBitmap(bmp, tw, th, true)
                if (small !== bmp) bmp.recycle()
                val px = IntArray(tw * th)
                small.getPixels(px, 0, tw, 0, 0, tw, th)
                small.recycle()
                frames += ByteArray(tw * th) { k ->
                    val c = px[k]
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
                onProgress((i + 1f) / count)
            }
            val sha = context.contentResolver.openInputStream(uri)?.use { Sha256.hex(it) } ?: ""
            DecodedVideo(GrayVideo(tw, th, fps, frames), preview!!, count / fps, jitter, sha)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun frameTimesUs(context: Context, uri: Uri): LongArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("Dosyada video yok.")
            ex.selectTrack(track)
            val out = ArrayList<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                out += t
                if (!ex.advance()) break
            }
            out.sort() // B-kareler nedeniyle örnek sırası gösterim sırası olmayabilir
            return out.toLongArray()
        } finally {
            ex.release()
        }
    }
}

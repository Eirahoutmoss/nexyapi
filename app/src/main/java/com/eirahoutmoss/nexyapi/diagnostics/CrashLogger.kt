package com.eirahoutmoss.nexyapi.diagnostics

import android.content.Context
import android.os.Build
import com.eirahoutmoss.nexyapi.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Çökmeleri cihazda bir metin dosyasına yazar (dışarıya hiçbir şey göndermez).
 * Kullanıcı Ayarlar > "Hata kaydını paylaş" ile dosyayı kendi isteğiyle iletir.
 */
object CrashLogger {
    private const val FILE = "crash_log.txt"
    private const val MAX_BYTES = 256 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Sistemin kendi davranışı (uygulamayı kapatma) bozulmasın.
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, "logs/$FILE")

    fun hasLog(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        // Dosya şişmesin: sınırı aşarsa en eski kayıtlar atılır.
        if (f.exists() && f.length() > MAX_BYTES) {
            val tail = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText(tail)
        }
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        f.appendText(
            buildString {
                append("=== ").append(ts).append(" ===\n")
                append("Uygulama: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("İş parçacığı: ").append(thread.name).append('\n')
                append(sw.toString()).append('\n')
            },
        )
    }
}

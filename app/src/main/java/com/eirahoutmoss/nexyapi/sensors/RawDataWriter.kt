package com.eirahoutmoss.nexyapi.sensors

import com.eirahoutmoss.nexyapi.core.analysis.AccelRecording
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Ham ölçüm verisini kalıcı dosyaya yazar ve dosyanın SHA-256 özetini döndürür.
 * Ham veri saklanır ki sonuç ileride (daha iyi bir yöntemle ya da bir mühendis tarafından)
 * yeniden hesaplanabilsin.
 */
object RawDataWriter {

    /** Sıkıştırılmış CSV: t_ns,ax,ay,az (m/s²). Başta '#' ile başlayan açıklama satırları. */
    fun writeAccel(target: File, rec: AccelRecording, header: List<String>): String {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(tmp.outputStream().buffered(), md).use { digest ->
                GZIPOutputStream(digest).use { gz ->
                    BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { w ->
                        header.forEach { w.write("# "); w.write(it.replace('\n', ' ')); w.write("\n") }
                        w.write("t_ns,ax,ay,az\n")
                        for (i in 0 until rec.size) {
                            w.write(rec.tNs[i].toString()); w.write(",")
                            w.write(rec.x[i].toString()); w.write(",")
                            w.write(rec.y[i].toString()); w.write(",")
                            w.write(rec.z[i].toString()); w.write("\n")
                        }
                    }
                }
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Ham veri dosyası kaydedilemedi.")
        } finally {
            tmp.delete()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Sıkıştırılmış CSV: tek sütun sayı dizisi (ör. dürtü yanıtı). Başta '#' açıklama satırları. */
    fun writeSeries(target: File, values: DoubleArray, columnName: String, header: List<String>): String {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(tmp.outputStream().buffered(), md).use { digest ->
                GZIPOutputStream(digest).use { gz ->
                    BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8)).use { w ->
                        header.forEach { w.write("# "); w.write(it.replace('\n', ' ')); w.write("\n") }
                        w.write(columnName); w.write("\n")
                        for (v in values) { w.write(v.toFloat().toString()); w.write("\n") }
                    }
                }
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Ham veri dosyası kaydedilemedi.")
        } finally {
            tmp.delete()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = FileInputStream(file).use {
        com.eirahoutmoss.nexyapi.core.integrity.Sha256.hex(it)
    }
}

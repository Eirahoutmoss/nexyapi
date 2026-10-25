package com.eirahoutmoss.nexyapi.core.integrity

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    fun hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    /** Uzun özeti insanların okuyup karşılaştırabileceği gruplara böler: "AB12 CD34 ..." */
    fun grouped(hex: String, group: Int = 4): String =
        hex.uppercase().chunked(group).joinToString(" ")

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

package com.eirahoutmoss.nexyapi.update

import com.eirahoutmoss.nexyapi.core.update.ReleaseAsset
import com.eirahoutmoss.nexyapi.core.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UpdateNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** GitHub'ın herkese açık sürüm API'si. Kimlik doğrulama gerektirmez (depo herkese açık olmalı). */
class GitHubReleaseClient(
    private val owner: String,
    private val repo: String,
    private val userAgent: String,
) {

    suspend fun latestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$owner/$repo/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(conn.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null // henüz yayımlanmış sürüm yok
                403, 429 -> throw UpdateNetworkException("GitHub şu an çok fazla istek aldı. Biraz sonra tekrar deneyin.")
                else -> throw UpdateNetworkException("Sunucu yanıtı beklenmedik: HTTP $code")
            }
        } catch (e: UpdateNetworkException) {
            throw e
        } catch (e: IOException) {
            throw UpdateNetworkException("İnternete bağlanılamadı.", e)
        } catch (e: org.json.JSONException) {
            throw UpdateNetworkException("Sürüm bilgisi okunamadı.", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Küçük metin dosyası indirir (ör. .sha256). */
    suspend fun downloadText(url: String, maxBytes: Int = 4096): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateNetworkException("Doğrulama dosyası indirilemedi: HTTP ${conn.responseCode}")
            }
            val bytes = conn.inputStream.use { it.readNBytesCompat(maxBytes) }
            String(bytes, Charsets.UTF_8)
        } catch (e: UpdateNetworkException) {
            throw e
        } catch (e: IOException) {
            throw UpdateNetworkException("İndirme yarıda kesildi.", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Büyük dosyayı ilerleme bildirerek indirir. Önce geçici dosyaya yazar, bitince yerine taşır. */
    suspend fun downloadFile(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateNetworkException("Güncelleme indirilemedi: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (expectedSize > 0 && done != expectedSize) {
                throw UpdateNetworkException("İndirilen dosya eksik ($done / $expectedSize bayt).")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw UpdateNetworkException("İndirilen dosya kaydedilemedi.")
        } catch (e: UpdateNetworkException) {
            tmp.delete(); throw e
        } catch (e: IOException) {
            tmp.delete(); throw UpdateNetworkException("İndirme yarıda kesildi.", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // sürüm dosyaları GitHub'ın depolama sunucusuna yönlenir
            setRequestProperty("User-Agent", userAgent)
        }

    private fun parse(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val assetsJson = o.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (i in 0 until assetsJson.length()) {
                    val a = assetsJson.getJSONObject(i)
                    add(
                        ReleaseAsset(
                            name = a.getString("name"),
                            downloadUrl = a.getString("browser_download_url"),
                            sizeBytes = a.optLong("size", -1),
                        ),
                    )
                }
            }
        }
        return ReleaseInfo(
            tagName = o.getString("tag_name"),
            title = o.optString("name", ""),
            notes = o.optString("body", ""),
            isPreRelease = o.optBoolean("prerelease", false),
            isDraft = o.optBoolean("draft", false),
            assets = assets,
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

package com.eirahoutmoss.nexyapi.core.license

import com.eirahoutmoss.nexyapi.core.model.Plan
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Çevrimdışı doğrulanan lisans anahtarı.
 *
 * Biçim:  NXY1-<base64url(yük)>.<base64url(imza)>
 * Yük:    UTF-8 metin, her satır "anahtar=değer":
 *           v=1
 *           id=<lisans no>
 *           holder=<firma / kişi adı>
 *           plan=pro
 *           issued=YYYY-MM-DD
 *           expires=YYYY-MM-DD   (boş = süresiz)
 * İmza:   ECDSA P-256 + SHA-256 (DER), yükün TAM baytları üzerinden.
 *
 * Özel anahtar yalnızca lisans aracında (tools/license) durur; uygulamada yalnızca açık anahtar vardır.
 * Bu sayede uygulamayı açan biri bile yeni lisans üretemez.
 */
data class License(
    val id: String,
    val holder: String,
    val plan: Plan,
    val issued: LocalDate,
    val expires: LocalDate?,
)

sealed interface LicenseCheck {
    data class Valid(val license: License) : LicenseCheck
    data class Expired(val license: License) : LicenseCheck
    data class Invalid(val reason: String) : LicenseCheck
}

class LicenseVerifier(publicKeyBase64: String) {

    private val publicKey: PublicKey? = runCatching {
        val der = Base64.getDecoder().decode(publicKeyBase64.filterNot { it.isWhitespace() })
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }.getOrNull()

    val isConfigured: Boolean get() = publicKey != null

    fun verify(token: String, today: LocalDate): LicenseCheck {
        val key = publicKey ?: return LicenseCheck.Invalid("Uygulamada lisans açık anahtarı tanımlı değil.")
        val clean = token.filterNot { it.isWhitespace() }
        if (!clean.startsWith(PREFIX)) return LicenseCheck.Invalid("Bu bir NexYapı lisans anahtarı değil.")
        val body = clean.removePrefix(PREFIX)
        val dot = body.indexOf('.')
        if (dot <= 0 || dot == body.lastIndex || body.indexOf('.', dot + 1) != -1) {
            return LicenseCheck.Invalid("Lisans anahtarı eksik ya da bozuk.")
        }
        val payloadBytes: ByteArray
        val signature: ByteArray
        try {
            payloadBytes = B64URL_DEC.decode(body.substring(0, dot))
            signature = B64URL_DEC.decode(body.substring(dot + 1))
        } catch (e: IllegalArgumentException) {
            return LicenseCheck.Invalid("Lisans anahtarı eksik ya da bozuk.")
        }

        val signatureOk = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payloadBytes)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!signatureOk) return LicenseCheck.Invalid("Lisans imzası geçersiz.")

        val license = parsePayload(String(payloadBytes, Charsets.UTF_8))
            ?: return LicenseCheck.Invalid("Lisans içeriği okunamadı.")
        if (license.expires != null && today.isAfter(license.expires)) return LicenseCheck.Expired(license)
        return LicenseCheck.Valid(license)
    }

    private fun parsePayload(text: String): License? {
        val map = HashMap<String, String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val k = line.substring(0, eq)
            if (map.containsKey(k)) return null // aynı anahtar iki kez: reddet
            map[k] = line.substring(eq + 1)
        }
        if (map["v"] != "1") return null
        val id = map["id"]?.takeIf { it.isNotBlank() } ?: return null
        val holder = map["holder"]?.takeIf { it.isNotBlank() } ?: return null
        val plan = map["plan"]?.let { Plan.fromCode(it) } ?: return null
        return try {
            val issued = LocalDate.parse(map["issued"] ?: return null)
            val expires = map["expires"]?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
            License(id, holder, plan, issued, expires)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        const val PREFIX = "NXY1-"
        private val B64URL_DEC: Base64.Decoder = Base64.getUrlDecoder()
    }
}

package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.license.LicenseCheck
import com.eirahoutmoss.nexyapi.core.license.LicenseVerifier
import com.eirahoutmoss.nexyapi.core.model.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64

class LicenseVerifierTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val verifier = LicenseVerifier(Base64.getEncoder().encodeToString(keys.public.encoded))
    private val today = LocalDate.of(2026, 10, 25)

    private fun sign(payload: String, pair: KeyPair = keys): String {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(bytes); sign()
        }
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "NXY1-" + enc.encodeToString(bytes) + "." + enc.encodeToString(sig)
    }

    private val good = "v=1\nid=L-0001\nholder=Örnek Mühendislik Ltd.\nplan=pro\nissued=2026-10-01\nexpires=2027-10-01\n"

    @Test fun validLicenseIsAccepted() {
        val r = verifier.verify(sign(good), today)
        assertTrue(r is LicenseCheck.Valid)
        val lic = (r as LicenseCheck.Valid).license
        assertEquals(Plan.PRO, lic.plan)
        assertEquals("Örnek Mühendislik Ltd.", lic.holder)
    }

    @Test fun whitespaceFromCopyPasteIsTolerated() {
        val token = sign(good).chunked(20).joinToString("\n  ")
        assertTrue(verifier.verify(token, today) is LicenseCheck.Valid)
    }

    @Test fun expiredLicenseIsReported() {
        assertTrue(verifier.verify(sign(good), LocalDate.of(2027, 10, 2)) is LicenseCheck.Expired)
        // Son gün hâlâ geçerli.
        assertTrue(verifier.verify(sign(good), LocalDate.of(2027, 10, 1)) is LicenseCheck.Valid)
    }

    @Test fun noExpiryMeansPerpetual() {
        val p = good.replace("expires=2027-10-01", "expires=")
        assertTrue(verifier.verify(sign(p), LocalDate.of(2099, 1, 1)) is LicenseCheck.Valid)
    }

    @Test fun tamperedPayloadIsRejected() {
        val token = sign(good)
        val enc = Base64.getUrlEncoder().withoutPadding()
        val forged = good.replace("plan=pro", "plan=pro").replace("2027", "2099")
        val sigPart = token.substringAfter('.')
        val tampered = "NXY1-" + enc.encodeToString(forged.toByteArray()) + "." + sigPart
        assertTrue(verifier.verify(tampered, today) is LicenseCheck.Invalid)
    }

    @Test fun licenseSignedByAnotherKeyIsRejected() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertTrue(verifier.verify(sign(good, other), today) is LicenseCheck.Invalid)
    }

    @Test fun malformedInputIsRejectedWithoutCrashing() {
        listOf("", "NXY1-", "NXY1-abc", "NXY1-.x", "NXY1-a.b.c", "XYZ-a.b", "NXY1-!!!.???").forEach {
            assertTrue("girdi: $it", verifier.verify(it, today) is LicenseCheck.Invalid)
        }
    }

    @Test fun duplicateKeysAreRejected() {
        assertTrue(verifier.verify(sign(good + "plan=pro\n"), today) is LicenseCheck.Invalid)
    }

    @Test fun unconfiguredKeyIsReported() {
        val v = LicenseVerifier("")
        assertFalse(v.isConfigured)
        assertTrue(v.verify(sign(good), today) is LicenseCheck.Invalid)
    }
}

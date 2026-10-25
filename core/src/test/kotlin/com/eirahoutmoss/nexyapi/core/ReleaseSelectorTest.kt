package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.update.ReleaseAsset
import com.eirahoutmoss.nexyapi.core.update.ReleaseInfo
import com.eirahoutmoss.nexyapi.core.update.ReleaseSelector
import com.eirahoutmoss.nexyapi.core.update.SemVer
import com.eirahoutmoss.nexyapi.core.update.UpdateDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectorTest {
    private val current = SemVer(0, 1, 0)
    private val apk = ReleaseAsset("NexYapi-0.2.0.apk", "https://x/a.apk", 10)
    private val sha = ReleaseAsset("NexYapi-0.2.0.apk.sha256", "https://x/a.sha256", 80)

    private fun rel(tag: String, assets: List<ReleaseAsset>, pre: Boolean = false, draft: Boolean = false) =
        ReleaseInfo(tag, tag, "notlar", pre, draft, assets)

    @Test fun newerReleaseWithChecksumIsAvailable() {
        val d = ReleaseSelector.decide(current, rel("v0.2.0", listOf(apk, sha)))
        assertTrue(d is UpdateDecision.Available)
        assertEquals("NexYapi-0.2.0.apk", (d as UpdateDecision.Available).candidate.apk.name)
    }

    @Test fun sameOrOlderIsUpToDate() {
        assertEquals(UpdateDecision.UpToDate, ReleaseSelector.decide(current, rel("v0.1.0", listOf(apk, sha))))
        assertEquals(UpdateDecision.UpToDate, ReleaseSelector.decide(current, rel("v0.0.9", listOf(apk, sha))))
    }

    @Test fun preReleaseAndDraftAreIgnored() {
        assertEquals(UpdateDecision.UpToDate, ReleaseSelector.decide(current, rel("v0.2.0", listOf(apk, sha), pre = true)))
        assertEquals(UpdateDecision.UpToDate, ReleaseSelector.decide(current, rel("v0.2.0", listOf(apk, sha), draft = true)))
        assertEquals(UpdateDecision.UpToDate, ReleaseSelector.decide(current, rel("v0.2.0-rc.1", listOf(apk, sha))))
    }

    @Test fun missingChecksumBlocksInstall() {
        assertTrue(ReleaseSelector.decide(current, rel("v0.2.0", listOf(apk))) is UpdateDecision.Unusable)
    }

    @Test fun twoApksAreAmbiguous() {
        val other = ReleaseAsset("debug.apk", "u", 1)
        assertTrue(ReleaseSelector.decide(current, rel("v0.2.0", listOf(apk, other, sha))) is UpdateDecision.Unusable)
    }

    @Test fun parsesSha256SumOutput() {
        val h = "A".repeat(64)
        assertEquals("a".repeat(64), ReleaseSelector.parseSha256File("$h  NexYapi-0.2.0.apk\n"))
        assertEquals("a".repeat(64), ReleaseSelector.parseSha256File(h))
        assertNull(ReleaseSelector.parseSha256File("bozuk"))
    }
}

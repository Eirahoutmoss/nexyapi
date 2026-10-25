package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.update.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(s: String) = SemVer.parse(s)!!

    @Test fun parsesWithAndWithoutPrefix() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 2, 0), v("1.2"))
        assertEquals(listOf("beta", "1"), v("1.0.0-beta.1").preRelease)
        assertEquals(SemVer(1, 0, 0), v("1.0.0+build.7"))
    }

    @Test fun rejectsGarbage() {
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("1"))
        assertNull(SemVer.parse("1.2.3.4"))
        assertNull(SemVer.parse(""))
    }

    @Test fun ordersNumericallyNotAlphabetically() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
    }

    @Test fun preReleaseIsLowerThanRelease() {
        assertTrue(v("1.0.0-beta.1") < v("1.0.0"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.10"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
    }
}

package com.eirahoutmoss.nexyapi.core

import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint
import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint.GenerationPart
import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint.MeasurementPart
import com.eirahoutmoss.nexyapi.core.integrity.ReportFingerprint.StructurePart
import com.eirahoutmoss.nexyapi.core.integrity.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReportFingerprintTest {
    private val s = StructurePart("s1", "Evim", "İndere Mh.", "3", 2018)
    private val g = GenerationPart("0.1.0", 1_761_400_000_000, "Ücretsiz")
    private val m1 = MeasurementPart("a", "Z2", 1000, 2.8, "Hz", "SM-G998B", "x".repeat(64))
    private val m2 = MeasurementPart("b", "V3", 2000, 0.4, "mm", "SM-G998B", "y".repeat(64))

    @Test fun sameDataSameHashRegardlessOfInputOrder() {
        assertEquals(ReportFingerprint.compute(s, listOf(m1, m2), g), ReportFingerprint.compute(s, listOf(m2, m1), g))
    }

    @Test fun anyChangeChangesHash() {
        val base = ReportFingerprint.compute(s, listOf(m1, m2), g)
        assertNotEquals(base, ReportFingerprint.compute(s, listOf(m1.copy(value = 2.7), m2), g))
        assertNotEquals(base, ReportFingerprint.compute(s.copy(floor = "4"), listOf(m1, m2), g))
        assertNotEquals(base, ReportFingerprint.compute(s, listOf(m1), g))
    }

    @Test fun newlineInjectionCannotForgeAField() {
        val a = ReportFingerprint.canonical(s.copy(name = "Evim\ns.address=Sahte"), emptyList(), g)
        val b = ReportFingerprint.canonical(s.copy(name = "Evim", address = "Sahte"), emptyList(), g)
        assertNotEquals(a, b)
    }

    @Test fun numbersAreLocaleIndependent() {
        assertEquals("2.8", ReportFingerprint.formatNumber(2.8))
        assertEquals("3", ReportFingerprint.formatNumber(3.0))
        assertEquals("0.0001", ReportFingerprint.formatNumber(0.0001))
    }

    @Test fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc"),
        )
    }
}

class ReportDiffTest {
    private val s = ReportFingerprint.StructurePart("s1", "Evim", "İndere Mh.", "3", 2018)
    private val g = ReportFingerprint.GenerationPart("0.4.0", 1000, "Örnek Büro")
    private val m1 = ReportFingerprint.MeasurementPart("aaaaaaaa-1", "Z2", 1000, 2.8, "Hz", "X", "h1", "Salon")
    private val m2 = ReportFingerprint.MeasurementPart("bbbbbbbb-2", "V3", 2000, 0.4, "mm", "X", "h2", "Kuzey")

    @Test fun identicalDataHasNoChanges() {
        val c = ReportFingerprint.canonical(s, listOf(m1, m2), g)
        org.junit.Assert.assertTrue(ReportFingerprint.diff(c, c).isEmpty())
    }

    @Test fun reportsEditedValueDeletedMeasurementAndRenamedStructure() {
        val before = ReportFingerprint.canonical(s, listOf(m1, m2), g)
        val after = ReportFingerprint.canonical(s.copy(name = "Evim 2"), listOf(m1.copy(value = 2.5)), g)
        val d = ReportFingerprint.diff(before, after)
        org.junit.Assert.assertTrue(d.toString(), d.any { "Yapı adı" in it && "Evim 2" in it })
        org.junit.Assert.assertTrue(d.toString(), d.any { "Z2" in it && "değer" in it })
        org.junit.Assert.assertTrue(d.toString(), d.any { "V3" in it && "silinmiş" in it })
    }
}

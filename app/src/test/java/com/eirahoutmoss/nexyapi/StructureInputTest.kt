package com.eirahoutmoss.nexyapi

import com.eirahoutmoss.nexyapi.data.StructureInput
import com.eirahoutmoss.nexyapi.data.StructureInput.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureInputTest {
    private fun input(name: String = "Evim", year: String = "") = StructureInput(name, "", "", year, "")

    @Test fun nameIsRequired() {
        val v = input(name = "   ").validate(2026)
        assertTrue(v is Validation.Error && v.field == StructureInput.Field.NAME)
    }

    @Test fun emptyYearIsAllowed() {
        assertEquals(Validation.Ok(null), input().validate(2026))
    }

    @Test fun yearMustBeInRange() {
        assertTrue(input(year = "1700").validate(2026) is Validation.Error)
        assertTrue(input(year = "2027").validate(2026) is Validation.Error)
        assertEquals(Validation.Ok(2018), input(year = " 2018 ").validate(2026))
    }
}

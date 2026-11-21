package com.skeler.scanely.core.lookup.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What openFDA is asked for a scanned barcode. Checked against the live API on 2026-11-21: openFDA
 * keeps `openfda.upc` as 13 digits (`0342126631011`) and `openfda.package_ndc` with dashes
 * (`42126-6310-1`); the 12-digit UPC-A a scanner reports and the undashed NDC both answered 404.
 */
class OpenFdaSearchTest {

    @Test
    fun `a 12-digit UPC-A is also searched as openFDA's 13-digit form`() {
        val upc = OpenFdaSearch.queries("342126631011").first()
        assertEquals("openfda.upc:(\"342126631011\"+\"0342126631011\")", upc)
    }

    @Test
    fun `a 13-digit GTIN with a leading 0 is also searched as the 12-digit UPC-A`() {
        val upc = OpenFdaSearch.queries("0342126631011").first()
        assertEquals("openfda.upc:(\"0342126631011\"+\"342126631011\")", upc)
    }

    @Test
    fun `the NDC inside a US drug barcode is searched with dashes, in every layout it can have`() {
        val expected = "openfda.package_ndc:(\"4212-6631-01\"+\"42126-631-01\"+\"42126-6310-1\")"
        assertEquals(expected, OpenFdaSearch.queries("342126631011")[1])
        assertEquals(expected, OpenFdaSearch.queries("0342126631011")[1])
    }

    @Test
    fun `a bare 10-digit NDC is dashed in its three layouts`() {
        assertEquals(
            listOf("4212-6631-01", "42126-631-01", "42126-6310-1"),
            OpenFdaSearch.dashedNdcs("4212663101")
        )
    }

    @Test
    fun `an 11-digit billing NDC drops its padding zero to the label's own dashed NDC`() {
        // 42126-6310-01 (5-4-2) is the billing form of 42126-6310-1 (5-4-1).
        assertTrue("42126-6310-1" in OpenFdaSearch.dashedNdcs("42126631001"))
        // 00591-0405-01 is 0591-0405-01 (4-4-2).
        assertTrue("0591-0405-01" in OpenFdaSearch.dashedNdcs("00591040501"))
    }

    @Test
    fun `a dashed or spaced barcode is read as its digits`() {
        assertEquals(OpenFdaSearch.queries("342126631011"), OpenFdaSearch.queries("3-42126-63101-1"))
    }

    @Test
    fun `a non-US 13-digit barcode is searched exactly as before, with no NDC guess`() {
        assertEquals(listOf("openfda.upc:\"8907460005526\""), OpenFdaSearch.queries("8907460005526"))
    }

    @Test
    fun `a 12-digit UPC-A outside the drug range is searched in both spellings but carries no NDC`() {
        assertEquals(
            listOf("openfda.upc:(\"012345678905\"+\"0012345678905\")"),
            OpenFdaSearch.queries("012345678905")
        )
    }
}

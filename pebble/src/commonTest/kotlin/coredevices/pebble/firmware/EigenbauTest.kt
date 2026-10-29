package coredevices.pebble.firmware

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EigenbauTest {
    @Test
    fun boulderFassungenSindKeineEigenbauten() {
        assertFalse(testFwVersion("v4.43.0-b42").istEigenbau())
        assertFalse(testFwVersion("v4.38.1-b3").istEigenbau())
    }

    @Test
    fun coresFassungOhneSuffixIstKeinEigenbau() {
        assertFalse(testFwVersion("v4.43.0").istEigenbau())
    }

    @Test
    fun entwicklungsbautenUndFremdeSuffixeSindEigenbauten() {
        assertTrue(testFwVersion("v4.43.0-b42-2-gabcdef12").istEigenbau())
        assertTrue(testFwVersion("v4.43.0-rc1").istEigenbau())
        assertTrue(testFwVersion("v4.43.0-dirty").istEigenbau())
    }
}

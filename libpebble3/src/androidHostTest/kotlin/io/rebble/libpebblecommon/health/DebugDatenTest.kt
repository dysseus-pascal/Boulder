package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Das Befuellen mit Spieldaten loeschte vorher alle echten Minuten- und
 * Sitzungsdaten. Es hatte keinen Aufrufer und gehoert nicht in die
 * Schnittstelle, ueber die Boulder an die Gesundheitsdaten kommt.
 */
class DebugDatenTest {
    @Test
    fun keineSchnittstelleLoeschtEchteDatenFuerSpieldaten() {
        val namen = (LibPebble::class.java.methods + HealthDataApi::class.java.methods + Health::class.java.methods)
            .map { it.name }
            .filter { it.contains("populateDebugHealthData") }
        assertEquals(emptyList(), namen)
    }
}

package io.rebble.libpebblecommon.database

import io.rebble.libpebblecommon.database.boulder.DATENBANK_VERSION
import io.rebble.libpebblecommon.database.boulder.UPSTREAM_VERSATZ
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Waechter fuer die Teilung der Datenbanken: libpebble3.db ist Upstream plus die
 * zwei bekannten Reste (spo2_readings, overlay_data.manual), alles Uebrige von
 * Boulder liegt in boulder.db. Ein Core-Merge oder eine neue Boulder-Tabelle
 * an der falschen Stelle faellt hier auf.
 *
 * Grundlage sind die eingecheckten Exporte; Room schreibt sie beim Bauen neu.
 * Upstream N ist Cores N.json im selben Ordner, Boulder zaehlt ab UPSTREAM_VERSATZ.
 * Boulders alte Zaehlung vor 0.50 (46..50) liegt in den Testdaten, weil ihre
 * Nummern mit Cores kollidieren.
 */
class SchemaWaechterTest {
    private fun schema(datei: File): JsonObject =
        Json.parseToJsonElement(datei.readText()).jsonObject.getValue("database").jsonObject

    private fun haupt(fassung: Int) = schema(File("schema/io.rebble.libpebblecommon.database.Database/$fassung.json"))

    private fun alt(fassung: Int) = schema(File("src/androidHostTest/resources/boulder-alte-schemas/$fassung.json"))

    // Ohne fieldPath: das ist der Name im Kotlin-Code, nicht in der Datei (Room rechnet ihn nicht in den identityHash).
    private fun JsonObject.tabellen(): Map<String, JsonObject> =
        getValue("entities").jsonArray.associate { e ->
            val felder = e.jsonObject.getValue("fields").jsonArray.map { JsonObject(it.jsonObject - "fieldPath") }
            e.jsonObject.getValue("tableName").jsonPrimitive.content to JsonObject(e.jsonObject + ("fields" to JsonArray(felder)))
        }

    private fun pruefeUpstreamPlusReste(upstreamFassung: Int) {
        val upstream = haupt(upstreamFassung).tabellen()
        val jetzt = haupt(UPSTREAM_VERSATZ + upstreamFassung).tabellen()
        assertEquals(upstream.keys + "spo2_readings", jetzt.keys)
        for (name in upstream.keys - "overlay_data") {
            assertEquals(upstream.getValue(name), jetzt.getValue(name), "Tabelle $name weicht von Upstream $upstreamFassung ab")
        }
        // SpO2 so, wie es mit Boulders Fassung 46 kam.
        assertEquals(alt(46).tabellen().getValue("spo2_readings"), jetzt.getValue("spo2_readings"))
        // overlay_data: Upstream plus die Spalte manual, sonst nichts.
        val vorher = upstream.getValue("overlay_data")
        val nachher = jetzt.getValue("overlay_data")
        val vorherFelder = vorher.getValue("fields").jsonArray
        val nachherFelder = nachher.getValue("fields").jsonArray
        assertEquals(vorherFelder, JsonArray(nachherFelder.filter { it.jsonObject.getValue("columnName").jsonPrimitive.content != "manual" }))
        assertEquals(listOf("manual"), (nachherFelder - vorherFelder.toSet()).map { it.jsonObject.getValue("columnName").jsonPrimitive.content })
        assertEquals(vorher.getValue("primaryKey"), nachher.getValue("primaryKey"))
        assertEquals(
            vorher.getValue("createSql").jsonPrimitive.content,
            nachher.getValue("createSql").jsonPrimitive.content.replace(", `manual` INTEGER NOT NULL DEFAULT 0", ""),
        )
    }

    @Test
    fun hauptDbIstUpstreamPlusDieBekanntenReste() {
        assertEquals(UPSTREAM_VERSATZ + 50, DATENBANK_VERSION)
        pruefeUpstreamPlusReste(DATENBANK_VERSION - UPSTREAM_VERSATZ)
    }

    @Test
    fun boulder053WarUpstream45PlusDieBekanntenReste() {
        // Der Ausgangspunkt der AutoMigration nach DATENBANK_VERSION: die Fassung von 0.50 bis 0.53.
        pruefeUpstreamPlusReste(45)
    }

    @Test
    fun dieNachtTabellenZiehenUnveraendertUm() {
        // boulder.db legt sie genau so an wie libpebble3.db in Fassung 50: der Umzug kopiert Spalte fuer Spalte.
        val vorher = alt(50).tabellen()
        val boulder = schema(File("schema/io.rebble.libpebblecommon.database.boulder.BoulderDatenbank/1.json")).tabellen()
        assertEquals(setOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv", "boulder_umzug"), boulder.keys)
        for (name in listOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv")) {
            assertEquals(vorher.getValue(name), boulder.getValue(name), name)
        }
    }
}

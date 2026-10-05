package io.rebble.libpebblecommon.database

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
 * Boulder liegt in boulder.db. Ein Upstream-Merge oder eine neue Boulder-Tabelle
 * an der falschen Stelle faellt hier auf.
 *
 * Grundlage sind die eingecheckten Exporte; Room schreibt sie beim Bauen neu.
 * Upstream 45 ist Boulders 45.json (beide Linien sind bis dahin gleich).
 */
class SchemaWaechterTest {
    private fun schema(ordner: String, fassung: Int): JsonObject =
        Json.parseToJsonElement(File("schema/$ordner/$fassung.json").readText()).jsonObject.getValue("database").jsonObject

    private fun haupt(fassung: Int) = schema("io.rebble.libpebblecommon.database.Database", fassung)

    // Ohne fieldPath: das ist der Name im Kotlin-Code, nicht in der Datei (Room rechnet ihn nicht in den identityHash).
    private fun JsonObject.tabellen(): Map<String, JsonObject> =
        getValue("entities").jsonArray.associate { e ->
            val felder = e.jsonObject.getValue("fields").jsonArray.map { JsonObject(it.jsonObject - "fieldPath") }
            e.jsonObject.getValue("tableName").jsonPrimitive.content to JsonObject(e.jsonObject + ("fields" to JsonArray(felder)))
        }

    @Test
    fun hauptDbIstUpstreamPlusDieBekanntenReste() {
        val upstream = haupt(45).tabellen()
        val jetzt = haupt(DATENBANK_VERSION).tabellen()
        assertEquals(DATENBANK_VERSION, UPSTREAM_VERSATZ + 45)
        assertEquals(upstream.keys + "spo2_readings", jetzt.keys)
        for (name in upstream.keys - "overlay_data") {
            assertEquals(upstream.getValue(name), jetzt.getValue(name), "Tabelle $name weicht von Upstream 45 ab")
        }
        // SpO2 so, wie es mit Fassung 46 kam.
        assertEquals(haupt(46).tabellen().getValue("spo2_readings"), jetzt.getValue("spo2_readings"))
        // overlay_data: Upstream plus die Spalte manual, sonst nichts.
        val alt = upstream.getValue("overlay_data")
        val neu = jetzt.getValue("overlay_data")
        val altFelder = alt.getValue("fields").jsonArray
        val neuFelder = neu.getValue("fields").jsonArray
        assertEquals(altFelder, JsonArray(neuFelder.filter { it.jsonObject.getValue("columnName").jsonPrimitive.content != "manual" }))
        assertEquals(listOf("manual"), (neuFelder - altFelder.toSet()).map { it.jsonObject.getValue("columnName").jsonPrimitive.content })
        assertEquals(alt.getValue("primaryKey"), neu.getValue("primaryKey"))
        assertEquals(
            alt.getValue("createSql").jsonPrimitive.content,
            neu.getValue("createSql").jsonPrimitive.content.replace(", `manual` INTEGER NOT NULL DEFAULT 0", ""),
        )
    }

    @Test
    fun dieNachtTabellenZiehenUnveraendertUm() {
        // boulder.db legt sie genau so an wie libpebble3.db in Fassung 50: der Umzug kopiert Spalte fuer Spalte.
        val vorher = haupt(50).tabellen()
        val boulder = schema("io.rebble.libpebblecommon.database.boulder.BoulderDatenbank", 1).tabellen()
        assertEquals(setOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv", "boulder_umzug"), boulder.keys)
        for (name in listOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv")) {
            assertEquals(vorher.getValue(name), boulder.getValue(name), name)
        }
    }
}

package io.rebble.libpebblecommon.database.boulder

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Die Ordner, die Boulder vom Cloud-Backup ausnimmt, stehen in den Regeln der App unter
 * demselben Namen wie im Code. Wer einen umbenennt, ohne die Regeln mitzuziehen, sichert
 * ganze Datenbankkopien mit, bis die 25-MB-Grenze kippt und das System gar nichts mehr sichert.
 */
class BackupRegelnTest {
    private val res = File("../androidApp/src/main/res/xml")

    private fun ausschluesse(datei: String, abschnitt: String? = null): Set<Pair<String, String>> {
        val dokument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, datei))
        val wurzel = abschnitt?.let { dokument.getElementsByTagName(it).item(0) as Element } ?: dokument.documentElement
        val knoten = wurzel.getElementsByTagName("exclude")
        return (0 until knoten.length).map { knoten.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
    }

    @Test
    fun beiseiteGelegteDatenbankenBleibenAusDemCloudBackup() {
        val erwartet = "database" to BEISEITE_ORDNER
        assertTrue(erwartet in ausschluesse("backup_rules.xml"), "backup_rules.xml (API 26-30)")
        assertTrue(erwartet in ausschluesse("data_extraction_rules.xml", "cloud-backup"), "data_extraction_rules.xml (ab API 31)")
    }

    @Test
    fun dieDlsQuarantaeneBleibtAusDemCloudBackup() {
        val erwartet = "file" to "dls-quarantaene"
        assertTrue(erwartet in ausschluesse("backup_rules.xml"), "backup_rules.xml (API 26-30)")
        assertTrue(erwartet in ausschluesse("data_extraction_rules.xml", "cloud-backup"), "data_extraction_rules.xml (ab API 31)")
    }
}

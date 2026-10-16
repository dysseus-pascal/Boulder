package coredevices.pebble.kiesel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boulder: das Sync-Token des eigenen Servers bleibt auf dem Telefon. Die App sichert sich ins
 * Cloud-Backup und zieht beim Geraeteumzug mit; die Datei des Spiegels nehmen beide Regelwerke
 * aus, in jedem ihrer Teile.
 */
class SpiegelGeheimTest {
    private val xml = File("../androidApp/src/main/res/xml")
    private val ausnahme = """<exclude domain="sharedpref" path="${Spiegel.DATEI}.xml" />"""

    private fun teile(datei: String, teil: String): List<String> =
        Regex("<$teil>(.*?)</$teil>", RegexOption.DOT_MATCHES_ALL).findAll(File(xml, datei).readText()).map { it.groupValues[1] }.toList()

    @Test
    fun keinBackupNimmtDasTokenMit() {
        val teile = teile("backup_rules.xml", "full-backup-content") +
            teile("data_extraction_rules.xml", "cloud-backup") + teile("data_extraction_rules.xml", "device-transfer")
        assertEquals(3, teile.size)
        assertEquals(listOf(true, true, true), teile.map { ausnahme in it })
    }
}

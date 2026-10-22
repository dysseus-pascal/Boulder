package boulder.server

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Quittung
import boulder.austausch.Stand
import boulder.austausch.Tag
import boulder.austausch.Training
import boulder.austausch.austauschJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Der Spiegel von Boulders Daten in SQLite. Jede Lieferung ersetzt, was sie mitbringt: eine Nacht
 * oder ein Training kommt neu, solange es sich auf dem Telefon noch aendert (ein spaet bestaetigtes
 * Training) - darum Upsert. Einen Tag fuehrt sie mit dem gespeicherten zusammen ([Tag]). Dazu die
 * Clients und Zugaenge der Anmeldung (Anmeldung.kt), damit Claude nach einem Neustart angemeldet
 * bleibt.
 *
 * EINE VERBINDUNG, ALLES SYNCHRONISIERT: Boulder schickt hoechstens alle paar Minuten, Claude
 * fragt selten. Eine Verbindung darf nie von zwei Faeden zugleich benutzt werden.
 */
class Speicher private constructor(private val db: SQLiteConnection) : AutoCloseable {

    @Synchronized
    fun uebernimm(l: Lieferung, jetzt: Long, minutenTage: Int): Quittung {
        transaktion {
            l.tage.forEach(::uebernimmTag)
            jeEintrag("INSERT INTO nacht(beginn, daten) VALUES(?, ?) ON CONFLICT(beginn) DO UPDATE SET daten = excluded.daten", l.naechte) { n ->
                bindLong(1, n.beginn)
                bindText(2, austauschJson.encodeToString(Nacht.serializer(), n))
            }
            // Erst entfernen, dann uebernehmen: was eine Lieferung mitbringt, steht danach da.
            jeEintrag("DELETE FROM training WHERE kennung = ?", l.trainingsEntfernt) { bindText(1, it) }
            jeEintrag(
                "INSERT INTO training(kennung, beginn, daten) VALUES(?, ?, ?) " +
                    "ON CONFLICT(kennung) DO UPDATE SET beginn = excluded.beginn, daten = excluded.daten",
                l.trainings,
            ) { t ->
                bindText(1, t.kennung)
                bindLong(2, t.beginn)
                bindText(3, austauschJson.encodeToString(Training.serializer(), t))
            }
            jeEintrag(
                "INSERT INTO minute(zeit, schritte, vmc, puls, lage) VALUES(?, ?, ?, ?, ?) ON CONFLICT(zeit) DO UPDATE SET " +
                    "schritte = excluded.schritte, vmc = excluded.vmc, puls = excluded.puls, lage = excluded.lage",
                l.minuten,
            ) { m ->
                bindLong(1, m.zeit)
                bindInt(2, m.schritte)
                bindInt(3, m.vmc)
                bindInt(4, m.puls)
                bindInt(5, m.lage)
            }
            setze("zone", l.zone)
            setze("app", l.app)
            setze("letzte_lieferung", jetzt.toString())
            l.stand?.let { setze("stand", austauschJson.encodeToString(Stand.serializer(), it)) }
            aendere("DELETE FROM minute WHERE zeit < ?") { bindLong(1, jetzt - minutenTage * 86_400L) }
        }
        return Quittung(l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size)
    }

    /**
     * Neue Werte ersetzen gespeicherte gleichen Namens, [Tag.entfernt] faellt weg, der Rest bleibt:
     * schickt Boulder einen Tag ohne Wasser, weil es die Akte gerade nicht lesen kann, bleibt das
     * Wasser hier stehen. Bleibt gar nichts, faellt der Tag weg.
     */
    private fun uebernimmTag(t: Tag) {
        val alt = zeilen("SELECT werte FROM tag WHERE datum = ?", { bindText(1, t.datum) }) {
            austauschJson.decodeFromString(WERTE, getText(0))
        }.firstOrNull().orEmpty()
        val werte = alt - t.entfernt.toSet() + t.werte
        if (werte.isEmpty()) {
            aendere("DELETE FROM tag WHERE datum = ?") { bindText(1, t.datum) }
            return
        }
        aendere("INSERT INTO tag(datum, werte) VALUES(?, ?) ON CONFLICT(datum) DO UPDATE SET werte = excluded.werte") {
            bindText(1, t.datum)
            bindText(2, austauschJson.encodeToString(WERTE, werte))
        }
    }

    private fun setze(schluessel: String, wert: String) =
        aendere("INSERT INTO stand(schluessel, wert) VALUES(?, ?) ON CONFLICT(schluessel) DO UPDATE SET wert = excluded.wert") {
            bindText(1, schluessel)
            bindText(2, wert)
        }

    /** Die Tage von [von] bis [bis] einschliesslich, Datum als "JJJJ-MM-TT". */
    @Synchronized
    fun tage(von: String, bis: String): List<Tag> =
        zeilen("SELECT datum, werte FROM tag WHERE datum >= ? AND datum <= ? ORDER BY datum", {
            bindText(1, von)
            bindText(2, bis)
        }) { Tag(getText(0), austauschJson.decodeFromString(WERTE, getText(1))) }

    /** Die Naechte, die in [von, bis) beginnen. */
    @Synchronized
    fun naechte(von: Long, bis: Long): List<Nacht> =
        zeilen("SELECT daten FROM nacht WHERE beginn >= ? AND beginn < ? ORDER BY beginn", zeitraum(von, bis)) {
            austauschJson.decodeFromString(Nacht.serializer(), getText(0))
        }

    /** Die Trainings, die in [von, bis) beginnen. */
    @Synchronized
    fun trainings(von: Long, bis: Long): List<Training> =
        zeilen("SELECT daten FROM training WHERE beginn >= ? AND beginn < ? ORDER BY beginn", zeitraum(von, bis)) {
            austauschJson.decodeFromString(Training.serializer(), getText(0))
        }

    @Synchronized
    fun minuten(von: Long, bis: Long): List<Minute> =
        zeilen("SELECT zeit, schritte, vmc, puls, lage FROM minute WHERE zeit >= ? AND zeit < ? ORDER BY zeit", zeitraum(von, bis)) {
            Minute(getLong(0), getInt(1), getInt(2), getInt(3), getInt(4))
        }

    @Synchronized
    fun stand(schluessel: String): String? =
        zeilen("SELECT wert FROM stand WHERE schluessel = ?", { bindText(1, schluessel) }) { getText(0) }.firstOrNull()

    /** Erster und letzter Tag, Zahl der Naechte, Trainings und Minuten - fuer das Werkzeug "stand". */
    @Synchronized
    fun umfang(): Umfang {
        // MIN und MAX einer leeren Tabelle sind NULL.
        fun text(sql: String) = zeilen(sql, {}) { if (isNull(0)) null else getText(0) }.first()
        fun zahl(sql: String) = zeilen(sql, {}) { getLong(0) }.first()
        return Umfang(
            ersterTag = text("SELECT MIN(datum) FROM tag"),
            letzterTag = text("SELECT MAX(datum) FROM tag"),
            naechte = zahl("SELECT COUNT(*) FROM nacht"),
            trainings = zahl("SELECT COUNT(*) FROM training"),
            minuten = zahl("SELECT COUNT(*) FROM minute"),
            ersteMinute = zahl("SELECT COALESCE(MIN(zeit), 0) FROM minute").takeIf { it > 0 },
        )
    }

    /** Legt einen Client der Anmeldung an; die aeltesten ohne Zugang fallen, sobald es mehr als [HOECHSTENS_CLIENTS] sind. */
    @Synchronized
    fun legeClientAn(c: Client, jetzt: Long) {
        aendere("INSERT INTO client(kennung, name, umleitungen, angelegt) VALUES(?, ?, ?, ?)") {
            bindText(1, c.kennung)
            bindText(2, c.name)
            bindText(3, austauschJson.encodeToString(LISTE, c.umleitungen))
            bindLong(4, jetzt)
        }
        // Registrieren darf jeder (so verlangt es Claude); ohne Grenze fuellte ein Fremder die Datenbank.
        // Ein Client mit Zugang bleibt: er gehoert zu einer gelungenen Anmeldung.
        vergissAbgelaufene(jetzt)
        aendere(
            "DELETE FROM client WHERE kennung IN (SELECT kennung FROM client WHERE kennung NOT IN (SELECT client FROM zugang) " +
                "ORDER BY angelegt DESC, rowid DESC LIMIT -1 OFFSET ?)"
        ) { bindInt(1, HOECHSTENS_CLIENTS) }
    }

    @Synchronized
    fun client(kennung: String): Client? =
        zeilen("SELECT name, umleitungen FROM client WHERE kennung = ?", { bindText(1, kennung) }) {
            Client(kennung, getText(0), austauschJson.decodeFromString(LISTE, getText(1)))
        }.firstOrNull()

    /** Merkt sich einen Zugang der Anmeldung; [pruefwert] statt des Tokens, siehe Anmeldung. */
    @Synchronized
    fun speichereZugang(pruefwert: String, art: String, client: String, ablauf: Long, jetzt: Long) {
        vergissAbgelaufene(jetzt)
        aendere("INSERT INTO zugang(pruefwert, art, client, ablauf) VALUES(?, ?, ?, ?)") {
            bindText(1, pruefwert)
            bindText(2, art)
            bindText(3, client)
            bindLong(4, ablauf)
        }
    }

    @Synchronized
    fun zugangGilt(pruefwert: String, art: String, jetzt: Long): Boolean = besitzer(pruefwert, art, jetzt) != null

    /** Loest einen Zugang ein: er gilt danach nicht mehr. Der Client, wenn er noch galt. */
    @Synchronized
    fun nimmZugang(pruefwert: String, art: String, jetzt: Long): String? {
        val client = besitzer(pruefwert, art, jetzt)
        aendere("DELETE FROM zugang WHERE pruefwert = ?") { bindText(1, pruefwert) }
        return client
    }

    /** Der Client eines Zugangs, solange er gilt. */
    private fun besitzer(pruefwert: String, art: String, jetzt: Long): String? =
        zeilen("SELECT client FROM zugang WHERE pruefwert = ? AND art = ? AND ablauf >= ?", {
            bindText(1, pruefwert)
            bindText(2, art)
            bindLong(3, jetzt)
        }) { getText(0) }.firstOrNull()

    private fun vergissAbgelaufene(jetzt: Long) = aendere("DELETE FROM zugang WHERE ablauf < ?") { bindLong(1, jetzt) }

    override fun close() = db.close()

    private fun zeitraum(von: Long, bis: Long): SQLiteStatement.() -> Unit = {
        bindLong(1, von)
        bindLong(2, bis)
    }

    private fun aendere(sql: String, binde: SQLiteStatement.() -> Unit) {
        db.prepare(sql).use { s ->
            s.binde()
            s.step()
        }
    }

    /** Eine vorbereitete Anweisung fuer alle Eintraege von [liste], je Eintrag gebunden und ausgefuehrt. */
    private fun <T> jeEintrag(sql: String, liste: List<T>, binde: SQLiteStatement.(T) -> Unit) {
        if (liste.isEmpty()) return
        db.prepare(sql).use { s ->
            for (x in liste) {
                s.binde(x)
                s.step()
                s.reset()
            }
        }
    }

    private fun <T> zeilen(sql: String, binde: SQLiteStatement.() -> Unit, lies: SQLiteStatement.() -> T): List<T> =
        db.prepare(sql).use { s ->
            s.binde()
            buildList { while (s.step()) add(s.lies()) }
        }

    private inline fun transaktion(block: () -> Unit) {
        db.execSQL("BEGIN IMMEDIATE")
        try {
            block()
            db.execSQL("COMMIT")
        } catch (e: Throwable) {
            // Nach manchen Fehlern (voller Datentraeger, I/O) hat SQLite selbst schon zurueckgerollt.
            if (db.inTransaction()) db.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Ein Programm, das sich bei der Anmeldung registriert hat (bei Claude eines je Verbindung). */
    data class Client(val kennung: String, val name: String, val umleitungen: List<String>)

    data class Umfang(
        val ersterTag: String?,
        val letzterTag: String?,
        val naechte: Long,
        val trainings: Long,
        val minuten: Long,
        val ersteMinute: Long?,
    )

    companion object {
        private val WERTE = MapSerializer(String.serializer(), Double.serializer())
        private val LISTE = ListSerializer(String.serializer())
        const val HOECHSTENS_CLIENTS = 100

        /** Oeffnet oder legt an; ":memory:" fuer Tests. */
        fun oeffne(pfad: String): Speicher {
            val db = BundledSQLiteDriver().open(pfad)
            // Wartet auf eine Sperre von aussen (etwa sqlite3 auf derselben Datei), statt gleich abzubrechen.
            db.execSQL("PRAGMA busy_timeout = 3000")
            db.execSQL("CREATE TABLE IF NOT EXISTS tag(datum TEXT PRIMARY KEY, werte TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS nacht(beginn INTEGER PRIMARY KEY, daten TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS training(kennung TEXT PRIMARY KEY, beginn INTEGER NOT NULL, daten TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS training_beginn ON training(beginn)")
            db.execSQL("CREATE TABLE IF NOT EXISTS minute(zeit INTEGER PRIMARY KEY, schritte INTEGER, vmc INTEGER, puls INTEGER, lage INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS stand(schluessel TEXT PRIMARY KEY, wert TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS client(kennung TEXT PRIMARY KEY, name TEXT NOT NULL, umleitungen TEXT NOT NULL, angelegt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS zugang(pruefwert TEXT PRIMARY KEY, art TEXT NOT NULL, client TEXT NOT NULL, ablauf INTEGER NOT NULL)")
            return Speicher(db)
        }
    }
}

package boulder.server

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import boulder.austausch.Akteneintrag
import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Probe
import boulder.austausch.Quittung
import boulder.austausch.Sauerstoff
import boulder.austausch.Spanne
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
 * Zugaenge der Anmeldung (Anmeldung.kt), damit Claude nach einem Neustart angemeldet bleibt, und
 * die Clients, die sich bei 0.55.0 registrierten; neue Clients stehen nur in ihrer Kennung.
 *
 * DIE AKTE (Health Connect) steht allgemein in zwei Tabellen: je Satz eine Zeile in `akte`, seine
 * Messreihe Wert fuer Wert in `akte_probe`. So fasst eine Abfrage ueber Tage die Reihen in SQLite
 * zusammen; ein Jahr Puls aus einer fremden App sind Millionen Werte, mehr als der Heap fasst.
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
            jeEintrag("DELETE FROM akte_probe WHERE id = ?", l.akteEntfernt) { bindText(1, it) }
            jeEintrag("DELETE FROM akte WHERE id = ?", l.akteEntfernt) { bindText(1, it) }
            uebernimmAkte(l.akte)
            // Die Vorgeschichte aendert sich nicht mehr: ein Tag ersetzt den gespeicherten ganz.
            jeEintrag("INSERT INTO vortag(datum, werte) VALUES(?, ?) ON CONFLICT(datum) DO UPDATE SET werte = excluded.werte", l.vorgeschichte) { t ->
                bindText(1, t.datum)
                bindText(2, austauschJson.encodeToString(WERTE, t.werte))
            }
            jeEintrag(
                "INSERT INTO sauerstoff(zeit, prozent, qualitaet) VALUES(?, ?, ?) " +
                    "ON CONFLICT(zeit) DO UPDATE SET prozent = excluded.prozent, qualitaet = excluded.qualitaet",
                l.sauerstoff,
            ) { o ->
                bindLong(1, o.zeit)
                bindInt(2, o.prozent)
                bindInt(3, o.qualitaet)
            }
            setze("zone", l.zone)
            setze("app", l.app)
            setze("letzte_lieferung", jetzt.toString())
            l.stand?.let { setze("stand", austauschJson.encodeToString(Stand.serializer(), it)) }
            aendere("DELETE FROM minute WHERE zeit < ?") { bindLong(1, jetzt - minutenTage * 86_400L) }
        }
        return Quittung(
            l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size, l.trainingsEntfernt.size,
            l.akte.size, l.akteEntfernt.size, l.vorgeschichte.size, l.sauerstoff.size,
        )
    }

    /**
     * Ein Satz ersetzt den gespeicherten gleicher Kennung samt Messreihe: Health Connect meldet einen
     * geaenderten Satz ganz. Die drei Anweisungen gelten fuer die ganze Lieferung; je Satz neu
     * vorbereitet kosteten zehntausend Saetze mehr als das Schreiben selbst.
     */
    private fun uebernimmAkte(l: List<Akteneintrag>) {
        if (l.isEmpty()) return
        db.prepare("DELETE FROM akte_probe WHERE id = ?").use { weg ->
            db.prepare(
                "INSERT INTO akte(id, art, beginn, ende, versatz, quelle, werte, texte, abschnitte) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET art = excluded.art, beginn = excluded.beginn, ende = excluded.ende, " +
                    "versatz = excluded.versatz, quelle = excluded.quelle, werte = excluded.werte, texte = excluded.texte, " +
                    "abschnitte = excluded.abschnitte",
            ).use { satz ->
                db.prepare("INSERT INTO akte_probe(id, art, t, w) VALUES(?, ?, ?, ?)").use { probe ->
                    for (e in l) {
                        weg.bindText(1, e.id)
                        weg.step()
                        weg.reset()
                        satz.bindText(1, e.id)
                        satz.bindText(2, e.art)
                        satz.bindLong(3, e.beginn)
                        e.ende?.let { satz.bindLong(4, it) } ?: satz.bindNull(4)
                        e.versatz?.let { satz.bindInt(5, it) } ?: satz.bindNull(5)
                        satz.bindText(6, e.quelle)
                        satz.bindText(7, austauschJson.encodeToString(WERTE, e.werte))
                        satz.bindText(8, austauschJson.encodeToString(TEXTE, e.texte))
                        satz.bindText(9, austauschJson.encodeToString(SPANNEN, e.abschnitte))
                        satz.step()
                        satz.reset()
                        for (w in e.proben) {
                            probe.bindText(1, e.id)
                            probe.bindText(2, e.art)
                            probe.bindLong(3, w.t)
                            probe.bindDouble(4, w.w)
                            probe.step()
                            probe.reset()
                        }
                    }
                }
            }
        }
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

    /** Die Tage der Vorgeschichte von [von] bis [bis] einschliesslich. */
    @Synchronized
    fun vortage(von: String, bis: String): List<Tag> =
        zeilen("SELECT datum, werte FROM vortag WHERE datum >= ? AND datum <= ? ORDER BY datum", {
            bindText(1, von)
            bindText(2, bis)
        }) { Tag(getText(0), austauschJson.decodeFromString(WERTE, getText(1))) }

    /** Je Spalte der erste Tag mit einem Wert von Boulder selbst; davor spricht die Vorgeschichte. */
    @Synchronized
    fun ersteEigeneTage(): Map<String, String> =
        zeilen("SELECT j.key, MIN(t.datum) FROM tag t, json_each(t.werte) j GROUP BY j.key", {}) { getText(0) to getText(1) }.toMap()

    @Synchronized
    fun sauerstoff(von: Long, bis: Long): List<Sauerstoff> =
        zeilen("SELECT zeit, prozent, qualitaet FROM sauerstoff WHERE zeit >= ? AND zeit < ? ORDER BY zeit", zeitraum(von, bis)) {
            Sauerstoff(getLong(0), getInt(1), getInt(2))
        }

    /** Je Satzart der Akte: Zahl, erster Beginn, letztes Ende und die Zahl je Quelle. */
    @Synchronized
    fun akteArten(): List<Artumfang> {
        val quellen = zeilen("SELECT art, quelle, COUNT(*) FROM akte GROUP BY art, quelle", {}) { Triple(getText(0), getText(1), getLong(2)) }
            .groupBy({ it.first }, { it.second to it.third })
        return zeilen("SELECT art, COUNT(*), MIN(beginn), MAX(COALESCE(ende, beginn)) FROM akte GROUP BY art ORDER BY art", {}) {
            Artumfang(getText(0), getLong(1), getLong(2), getLong(3), quellen[getText(0)].orEmpty().sortedWith(compareBy({ -it.second }, { it.first })).toMap())
        }
    }

    /**
     * Die Saetze einer Art, die in [von, bis) beginnen, ohne ihre Messreihe; hoechstens [hoechstens].
     * Ein Satz mehr zeigt dem Aufrufer, dass es mehr gibt.
     */
    @Synchronized
    fun akte(art: String, von: Long, bis: Long, hoechstens: Int): List<Akteneintrag> =
        zeilen("SELECT $AKTE_SPALTEN FROM akte WHERE art = ? AND beginn >= ? AND beginn < ? ORDER BY beginn, id LIMIT ?", {
            bindText(1, art)
            bindLong(2, von)
            bindLong(3, bis)
            bindInt(4, hoechstens + 1)
        }) { eintrag() }

    /** Ein Satz samt Messreihe, oder null. */
    @Synchronized
    fun akteEintrag(id: String): Akteneintrag? {
        val e = zeilen("SELECT $AKTE_SPALTEN FROM akte WHERE id = ?", { bindText(1, id) }) { eintrag() }.firstOrNull() ?: return null
        val proben = zeilen("SELECT t, w FROM akte_probe WHERE id = ? ORDER BY t", { bindText(1, id) }) {
            Probe(getLong(0), getDouble(1))
        }
        return e.copy(proben = proben)
    }

    /** Zahl, Kleinster, Mittel und Groesster der Messreihe eines Satzes; null ohne Reihe. */
    @Synchronized
    fun probenUmfang(id: String): Probenumfang? =
        zeilen("SELECT COUNT(*), MIN(w), AVG(w), MAX(w) FROM akte_probe WHERE id = ?", { bindText(1, id) }) {
            if (getLong(0) == 0L) null else Probenumfang(getLong(0), getDouble(1), getDouble(2), getDouble(3))
        }.first()

    /**
     * Die Werte einer Art in Eimern zu [breite] Sekunden ab [von], je Quelle und Name: aus [Akteneintrag.werte],
     * dazu [SAETZE] fuer die Zahl der Saetze und [MINUTEN] fuer ihre Dauer. Die Summe rechnet SQLite - ein
     * Jahr Schritte aus einer fremden App sind Hunderttausende Saetze.
     */
    @Synchronized
    fun akteEimer(art: String, von: Long, bis: Long, breite: Long): List<Eimer> {
        fun binde(s: SQLiteStatement) {
            s.bindLong(1, von)
            s.bindLong(2, breite)
            s.bindText(3, art)
            s.bindLong(4, von)
            s.bindLong(5, bis)
        }
        val werte = zeilen(
            "SELECT a.quelle, (a.beginn - ?) / ? AS e, j.key, COUNT(*), SUM(j.value), MIN(j.value), MAX(j.value) " +
                "FROM akte a, json_each(a.werte) j WHERE a.art = ? AND a.beginn >= ? AND a.beginn < ? GROUP BY a.quelle, e, j.key",
            { binde(this) },
        ) { Eimer(getText(0), getLong(1), getText(2), getLong(3), getDouble(4), getDouble(5), getDouble(6)) }
        val saetze = zeilen(
            "SELECT a.quelle, (a.beginn - ?) / ? AS e, COUNT(*), SUM(COALESCE(a.ende - a.beginn, 0)) / 60.0 " +
                "FROM akte a WHERE a.art = ? AND a.beginn >= ? AND a.beginn < ? GROUP BY a.quelle, e",
            { binde(this) },
        ) {
            listOf(
                Eimer(getText(0), getLong(1), SAETZE, getLong(2), getLong(2).toDouble(), 1.0, 1.0),
                Eimer(getText(0), getLong(1), MINUTEN, getLong(2), getDouble(3), 0.0, 0.0),
            )
        }.flatten()
        return werte + saetze
    }

    /** Die Messreihen einer Art in Eimern zu [breite] Sekunden ab [von], je Quelle; Name [PROBEN]. */
    @Synchronized
    fun probenEimer(art: String, von: Long, bis: Long, breite: Long): List<Eimer> =
        zeilen(
            "SELECT a.quelle, (p.t - ?) / ? AS e, COUNT(*), SUM(p.w), MIN(p.w), MAX(p.w) " +
                "FROM akte_probe p JOIN akte a ON a.id = p.id WHERE p.art = ? AND p.t >= ? AND p.t < ? GROUP BY a.quelle, e",
            {
                bindLong(1, von)
                bindLong(2, breite)
                bindText(3, art)
                bindLong(4, von)
                bindLong(5, bis)
            },
        ) { Eimer(getText(0), getLong(1), PROBEN, getLong(2), getDouble(3), getDouble(4), getDouble(5)) }

    private fun SQLiteStatement.eintrag() = Akteneintrag(
        id = getText(0), art = getText(1), beginn = getLong(2),
        ende = if (isNull(3)) null else getLong(3),
        versatz = if (isNull(4)) null else getInt(4),
        quelle = getText(5),
        werte = austauschJson.decodeFromString(WERTE, getText(6)),
        texte = austauschJson.decodeFromString(TEXTE, getText(7)),
        abschnitte = austauschJson.decodeFromString(SPANNEN, getText(8)),
    )

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
            ersterVortag = text("SELECT MIN(datum) FROM vortag"),
            letzterVortag = text("SELECT MAX(datum) FROM vortag"),
            vortage = zahl("SELECT COUNT(*) FROM vortag"),
            sauerstoff = zahl("SELECT COUNT(*) FROM sauerstoff"),
            ersterSauerstoff = zahl("SELECT COALESCE(MIN(zeit), 0) FROM sauerstoff").takeIf { it > 0 },
        )
    }

    /** Ein Client aus 0.55.0 (Anmeldung.client). */
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
        val ersterVortag: String? = null,
        val letzterVortag: String? = null,
        val vortage: Long = 0,
        val sauerstoff: Long = 0,
        val ersterSauerstoff: Long? = null,
    )

    data class Artumfang(val art: String, val anzahl: Long, val erster: Long, val letzter: Long, val quellen: Map<String, Long>)

    data class Probenumfang(val anzahl: Long, val kleinster: Double, val mittel: Double, val groesster: Double)

    /** Ein Eimer: [n] Werte namens [name] aus [quelle] im Eimer Nummer [nummer] (ab dem Anfang der Abfrage). */
    data class Eimer(
        val quelle: String,
        val nummer: Long,
        val name: String,
        val n: Long,
        val summe: Double,
        val kleinster: Double,
        val groesster: Double,
    )

    companion object {
        private val WERTE = MapSerializer(String.serializer(), Double.serializer())
        private val TEXTE = MapSerializer(String.serializer(), String.serializer())
        private val SPANNEN = ListSerializer(Spanne.serializer())
        private val LISTE = ListSerializer(String.serializer())
        private const val AKTE_SPALTEN = "id, art, beginn, ende, versatz, quelle, werte, texte, abschnitte"

        // Namen der Eimer, die nicht aus Akteneintrag.werte kommen; "#" steht in keinem Wertnamen.
        const val SAETZE = "#saetze"
        const val MINUTEN = "#minuten"
        const val PROBEN = "#proben"

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
            db.execSQL("CREATE TABLE IF NOT EXISTS vortag(datum TEXT PRIMARY KEY, werte TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sauerstoff(zeit INTEGER PRIMARY KEY, prozent INTEGER NOT NULL, qualitaet INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS akte(id TEXT PRIMARY KEY, art TEXT NOT NULL, beginn INTEGER NOT NULL, ende INTEGER, " +
                    "versatz INTEGER, quelle TEXT NOT NULL, werte TEXT NOT NULL, texte TEXT NOT NULL, abschnitte TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS akte_art_beginn ON akte(art, beginn)")
            db.execSQL("CREATE TABLE IF NOT EXISTS akte_probe(id TEXT NOT NULL, art TEXT NOT NULL, t INTEGER NOT NULL, w REAL NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS akte_probe_art_t ON akte_probe(art, t)")
            db.execSQL("CREATE INDEX IF NOT EXISTS akte_probe_id ON akte_probe(id)")
            return Speicher(db)
        }
    }
}

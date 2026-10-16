package boulder.server

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
import java.sql.Connection
import java.sql.DriverManager

/**
 * Der Spiegel von Boulders Daten in SQLite. Jede Lieferung ersetzt, was sie mitbringt: ein Tag,
 * eine Nacht oder ein Training kommt neu, solange er sich auf dem Telefon noch aendert (der
 * laufende Tag, ein spaet bestaetigtes Training) - darum Upsert statt Anfuegen. Dazu die Clients
 * und Zugaenge der Anmeldung (Anmeldung.kt), damit Claude nach einem Neustart angemeldet bleibt.
 *
 * EINE VERBINDUNG, ALLES SYNCHRONISIERT: Boulder schickt hoechstens alle paar Minuten, Claude
 * fragt selten; ein Pool brauchte nur zusaetzliche Regeln fuer SQLite.
 */
class Speicher private constructor(private val db: Connection) : AutoCloseable {

    @Synchronized
    fun uebernimm(l: Lieferung, jetzt: Long, minutenTage: Int): Quittung {
        db.autoCommit = false
        try {
            db.prepareStatement(
                "INSERT INTO tag(datum, werte) VALUES(?, ?) ON CONFLICT(datum) DO UPDATE SET werte = excluded.werte"
            ).use { s ->
                for (t in l.tage) {
                    s.setString(1, t.datum)
                    s.setString(2, austauschJson.encodeToString(WERTE, t.werte))
                    s.addBatch()
                }
                s.executeBatch()
            }
            db.prepareStatement(
                "INSERT INTO nacht(beginn, daten) VALUES(?, ?) ON CONFLICT(beginn) DO UPDATE SET daten = excluded.daten"
            ).use { s ->
                for (n in l.naechte) {
                    s.setLong(1, n.beginn)
                    s.setString(2, austauschJson.encodeToString(Nacht.serializer(), n))
                    s.addBatch()
                }
                s.executeBatch()
            }
            db.prepareStatement(
                "INSERT INTO training(kennung, beginn, daten) VALUES(?, ?, ?) " +
                    "ON CONFLICT(kennung) DO UPDATE SET beginn = excluded.beginn, daten = excluded.daten"
            ).use { s ->
                for (t in l.trainings) {
                    s.setString(1, t.kennung)
                    s.setLong(2, t.beginn)
                    s.setString(3, austauschJson.encodeToString(Training.serializer(), t))
                    s.addBatch()
                }
                s.executeBatch()
            }
            db.prepareStatement(
                "INSERT INTO minute(zeit, schritte, vmc, puls, lage) VALUES(?, ?, ?, ?, ?) ON CONFLICT(zeit) DO UPDATE SET " +
                    "schritte = excluded.schritte, vmc = excluded.vmc, puls = excluded.puls, lage = excluded.lage"
            ).use { s ->
                for (m in l.minuten) {
                    s.setLong(1, m.zeit)
                    s.setInt(2, m.schritte)
                    s.setInt(3, m.vmc)
                    s.setInt(4, m.puls)
                    s.setInt(5, m.lage)
                    s.addBatch()
                }
                s.executeBatch()
            }
            setze("zone", l.zone)
            setze("app", l.app)
            setze("letzte_lieferung", jetzt.toString())
            l.stand?.let { setze("stand", austauschJson.encodeToString(Stand.serializer(), it)) }
            db.prepareStatement("DELETE FROM minute WHERE zeit < ?").use { s ->
                s.setLong(1, jetzt - minutenTage * 86_400L)
                s.executeUpdate()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        return Quittung(l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size)
    }

    private fun setze(schluessel: String, wert: String) {
        db.prepareStatement(
            "INSERT INTO stand(schluessel, wert) VALUES(?, ?) ON CONFLICT(schluessel) DO UPDATE SET wert = excluded.wert"
        ).use { s ->
            s.setString(1, schluessel)
            s.setString(2, wert)
            s.executeUpdate()
        }
    }

    /** Die Tage von [von] bis [bis] einschliesslich, Datum als "JJJJ-MM-TT". */
    @Synchronized
    fun tage(von: String, bis: String): List<Tag> =
        db.prepareStatement("SELECT datum, werte FROM tag WHERE datum >= ? AND datum <= ? ORDER BY datum").use { s ->
            s.setString(1, von)
            s.setString(2, bis)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(Tag(r.getString(1), austauschJson.decodeFromString(WERTE, r.getString(2)))) }
            }
        }

    /** Die Naechte, die in [von, bis) beginnen. */
    @Synchronized
    fun naechte(von: Long, bis: Long): List<Nacht> =
        db.prepareStatement("SELECT daten FROM nacht WHERE beginn >= ? AND beginn < ? ORDER BY beginn").use { s ->
            s.setLong(1, von)
            s.setLong(2, bis)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(austauschJson.decodeFromString(Nacht.serializer(), r.getString(1))) }
            }
        }

    /** Die Trainings, die in [von, bis) beginnen. */
    @Synchronized
    fun trainings(von: Long, bis: Long): List<Training> =
        db.prepareStatement("SELECT daten FROM training WHERE beginn >= ? AND beginn < ? ORDER BY beginn").use { s ->
            s.setLong(1, von)
            s.setLong(2, bis)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(austauschJson.decodeFromString(Training.serializer(), r.getString(1))) }
            }
        }

    @Synchronized
    fun minuten(von: Long, bis: Long): List<Minute> =
        db.prepareStatement("SELECT zeit, schritte, vmc, puls, lage FROM minute WHERE zeit >= ? AND zeit < ? ORDER BY zeit").use { s ->
            s.setLong(1, von)
            s.setLong(2, bis)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(Minute(r.getLong(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5))) }
            }
        }

    @Synchronized
    fun stand(schluessel: String): String? =
        db.prepareStatement("SELECT wert FROM stand WHERE schluessel = ?").use { s ->
            s.setString(1, schluessel)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }

    /** Erster und letzter Tag, Zahl der Naechte, Trainings und Minuten - fuer das Werkzeug "stand". */
    @Synchronized
    fun umfang(): Umfang = db.createStatement().use { s ->
        fun zahl(sql: String) = s.executeQuery(sql).use { r -> if (r.next()) r.getLong(1) else 0L }
        fun text(sql: String) = s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null }
        Umfang(
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
        db.prepareStatement("INSERT INTO client(kennung, name, umleitungen, angelegt) VALUES(?, ?, ?, ?)").use { s ->
            s.setString(1, c.kennung)
            s.setString(2, c.name)
            s.setString(3, austauschJson.encodeToString(LISTE, c.umleitungen))
            s.setLong(4, jetzt)
            s.executeUpdate()
        }
        // Registrieren darf jeder (so verlangt es Claude); ohne Grenze fuellte ein Fremder die Datenbank.
        // Ein Client mit Zugang bleibt: er gehoert zu einer gelungenen Anmeldung.
        db.prepareStatement("DELETE FROM zugang WHERE ablauf < ?").use { s ->
            s.setLong(1, jetzt)
            s.executeUpdate()
        }
        db.prepareStatement(
            "DELETE FROM client WHERE kennung IN (SELECT kennung FROM client WHERE kennung NOT IN (SELECT client FROM zugang) " +
                "ORDER BY angelegt DESC, rowid DESC LIMIT -1 OFFSET ?)"
        ).use { s ->
            s.setInt(1, HOECHSTENS_CLIENTS)
            s.executeUpdate()
        }
    }

    @Synchronized
    fun client(kennung: String): Client? =
        db.prepareStatement("SELECT name, umleitungen FROM client WHERE kennung = ?").use { s ->
            s.setString(1, kennung)
            s.executeQuery().use { r ->
                if (r.next()) Client(kennung, r.getString(1), austauschJson.decodeFromString(LISTE, r.getString(2))) else null
            }
        }

    /** Merkt sich einen Zugang der Anmeldung; [pruefwert] statt des Tokens, siehe Anmeldung. */
    @Synchronized
    fun speichereZugang(pruefwert: String, art: String, client: String, ablauf: Long, jetzt: Long) {
        db.prepareStatement("DELETE FROM zugang WHERE ablauf < ?").use { s ->
            s.setLong(1, jetzt)
            s.executeUpdate()
        }
        db.prepareStatement("INSERT INTO zugang(pruefwert, art, client, ablauf) VALUES(?, ?, ?, ?)").use { s ->
            s.setString(1, pruefwert)
            s.setString(2, art)
            s.setString(3, client)
            s.setLong(4, ablauf)
            s.executeUpdate()
        }
    }

    @Synchronized
    fun zugangGilt(pruefwert: String, art: String, jetzt: Long): Boolean =
        db.prepareStatement("SELECT 1 FROM zugang WHERE pruefwert = ? AND art = ? AND ablauf >= ?").use { s ->
            s.setString(1, pruefwert)
            s.setString(2, art)
            s.setLong(3, jetzt)
            s.executeQuery().use { it.next() }
        }

    /** Loest einen Zugang ein: er gilt danach nicht mehr. Der Client, wenn er noch galt. */
    @Synchronized
    fun nimmZugang(pruefwert: String, art: String, jetzt: Long): String? {
        val client = db.prepareStatement("SELECT client FROM zugang WHERE pruefwert = ? AND art = ? AND ablauf >= ?").use { s ->
            s.setString(1, pruefwert)
            s.setString(2, art)
            s.setLong(3, jetzt)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
        db.prepareStatement("DELETE FROM zugang WHERE pruefwert = ?").use { s ->
            s.setString(1, pruefwert)
            s.executeUpdate()
        }
        return client
    }

    override fun close() = db.close()

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
            val db = DriverManager.getConnection("jdbc:sqlite:$pfad")
            db.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS tag(datum TEXT PRIMARY KEY, werte TEXT NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS nacht(beginn INTEGER PRIMARY KEY, daten TEXT NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS training(kennung TEXT PRIMARY KEY, beginn INTEGER NOT NULL, daten TEXT NOT NULL)")
                s.execute("CREATE INDEX IF NOT EXISTS training_beginn ON training(beginn)")
                s.execute("CREATE TABLE IF NOT EXISTS minute(zeit INTEGER PRIMARY KEY, schritte INTEGER, vmc INTEGER, puls INTEGER, lage INTEGER)")
                s.execute("CREATE TABLE IF NOT EXISTS stand(schluessel TEXT PRIMARY KEY, wert TEXT NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS client(kennung TEXT PRIMARY KEY, name TEXT NOT NULL, umleitungen TEXT NOT NULL, angelegt INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS zugang(pruefwert TEXT PRIMARY KEY, art TEXT NOT NULL, client TEXT NOT NULL, ablauf INTEGER NOT NULL)")
            }
            return Speicher(db)
        }
    }
}

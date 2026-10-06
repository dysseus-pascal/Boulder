package coredevices.pebble.kiesel.ansicht

import coredevices.pebble.kiesel.Blutdruck
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Koerpermessung
import coredevices.pebble.kiesel.Koerperrechnung
import coredevices.pebble.kiesel.Koerperstand
import coredevices.pebble.kiesel.Koerperzeile
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Heart & Body zum Zeichnen, nach den Beispielwerten des Entwurfs
 * "Erlebnis-Herz": heute 13:40 und die Nacht von [Healthbeispiel], der Puls
 * der letzten 24 Stunden mit der Velofahrt von gestern, die HRV der Woche
 * 48-53-46-55-52-41-50 ms, der Blutdruck 124/79 ... 122/78 (heute 7:10).
 * Davor je 40 Tage abwechselnd am unteren und oberen Rand des Entwurfsbands
 * (HRV 44 und 57, oben 116 und 129, unten 74 und 83) - so rechnet die App
 * daraus Baender wie im Entwurf.
 *
 * NUR DATEN - die Woche rechnet die App ([Gesundheitsrechnung.bandwoche],
 * [Gesundheitsrechnung.blutdruckwoche]), gezeichnet wird mit ihrem Code.
 */
internal object Herzbeispiel {
    private val b = Healthbeispiel
    val jetzt = b.jetzt
    val heute = b.heute

    /** Der Puls je halbe Stunde ab 13:40 gestern bis jetzt, wie im Entwurf; dazwischen jede Minute gerade verbunden. */
    private val pulsJeHalbeStunde = listOf(
        72, 75, 80, 78, 92, 128, 142, 118, 84, 78, 76, 74, 73, 72, 70, 66, 62, 58, 55, 54, 53, 52, 53, 54, 52,
        53, 55, 54, 56, 55, 57, 56, 58, 66, 72, 78, 74, 76, 72, 75, 73, 78, 104, 98, 80, 76, 74, 72, 68,
    )

    private val puls: List<Pair<Long, Int>> = run {
        val start = jetzt.epochSecond - 86_400
        (0 until (pulsJeHalbeStunde.size - 1) * 30).map { minute ->
            val i = minute / 30
            val a = pulsJeHalbeStunde[i]
            val c = pulsJeHalbeStunde[i + 1]
            start + minute * 60L to (a + (c - a) * (minute % 30) / 30.0).roundToInt()
        }.filter { it.first < jetzt.epochSecond - 60 }
    }

    /** SpO2 der Nacht alle fuenf Minuten um 96 %, einmal 93 % - "lowest 93" wie im Entwurf. */
    private val spo2: List<Pair<Long, Int>> = run {
        val beginn = b.nacht.einschlafen.epochSecond
        (0..98).map { i -> beginn + i * 300L to if (i == 60) 93 else if (i % 3 == 0) 97 else 96 }
    }

    private val hrvWoche = listOf(48, 53, 46, 55, 52, 41, 50)
    private val obenWoche = listOf(124, 128, 121, 126, 123, 131, 122)
    private val untenWoche = listOf(79, 82, 77, 80, 78, 85, 78)

    /** Tag [k] Tage vor heute. */
    private fun tag(k: Int): LocalDate = heute.minusDays(k.toLong())

    /** Die HRV je Nacht: 40 Naechte vor der Woche abwechselnd 44 und 57, dann die Woche. */
    fun hrvWerte(mitFrueheren: Boolean = true): Map<LocalDate, Int> =
        (if (mitFrueheren) (7..46).associate { k -> tag(k) to if (k % 2 == 0) 44 else 57 } else emptyMap()) +
            hrvWoche.mapIndexed { i, w -> tag(6 - i) to w }

    /**
     * Eine Sitzung je Tag um 7:10: 40 Tage vor der Woche am Rand des
     * Entwurfsbands, dann die Woche. [inDerWoche] = false: die Woche ohne
     * Messung - die juengste Sitzung ist dann die von vor sieben Tagen (129/83).
     */
    fun blutdruckAlle(mitFrueheren: Boolean = true, inDerWoche: Boolean = true): List<Blutdruck> =
        (if (mitFrueheren) (46 downTo 7).map { k ->
            if (k % 2 == 0) Blutdruck(b.um(7, 10, tag(k)), 116, 74) else Blutdruck(b.um(7, 10, tag(k)), 129, 83)
        } else emptyList()) +
            (if (inDerWoche) obenWoche.indices.map { i -> Blutdruck(b.um(7, 10, tag(6 - i)), obenWoche[i], untenWoche[i]) } else emptyList())

    /**
     * WENIG VERLAUF wie auf dem Telefon am 6.10. (We 64, Fr 55, Tu 50): vier
     * Naechte mit HRV - eine neun Tage vor heute (58), dann drei in der Woche.
     * Ab dem vierten Tag der Woche liegen drei Werte davor: das Band steht
     * (54-64), urteilt aber nicht.
     */
    fun hrvVier(): Map<LocalDate, Int> = mapOf(tag(9) to 58, tag(6) to 64, tag(4) to 55, tag(0) to 50)

    /**
     * ACHT NAECHTE: eine zehn Tage vor heute (51), dann die Woche des
     * Entwurfs. Das Band steht ab dem dritten Tag der Woche, ein Urteil hat
     * nur heute - die einzige Nacht mit sieben Werten davor.
     */
    fun hrvAcht(): Map<LocalDate, Int> = mapOf(tag(10) to 51) + hrvWoche.mapIndexed { i, w -> tag(6 - i) to w }

    /** Eine Messung, heute 7:11, 116/73 - wie auf dem Telefon am 2.10. */
    fun blutdruckEinTag(): List<Blutdruck> = listOf(Blutdruck(b.um(7, 11, heute), 116, 73))

    /**
     * Fuenf Tage mit Messung in der Woche, keine davor: am Mittwoch,
     * Donnerstag, Samstag, Sonntag und heute (die Werte des Entwurfs). Das
     * Band steht ab Sonntag (drei Tage davor), geurteilt wird nie.
     */
    fun blutdruckFuenfTage(): List<Blutdruck> = listOf(0, 1, 3, 4, 6).map { i -> Blutdruck(b.um(7, 10, tag(6 - i)), obenWoche[i], untenWoche[i]) }

    /**
     * Die Waage wie auf dem Telefon am 6.10.: heute 69.6 kg und 21.6 % Fett,
     * vor 30 Tagen 69.7 kg - "21.6% fat · −0.1 kg in 30 days". Gerechnet von
     * der App ([Koerperrechnung.zeile]), wie fuer die Karte Body.
     */
    val koerper: Koerperzeile = Koerperrechnung.zeile(
        Koerperstand(
            gewicht = listOf(Koerpermessung(b.um(6, 50, tag(30)), 69.7), Koerpermessung(b.um(6, 50, tag(15)), 69.9), Koerpermessung(b.um(6, 50), 69.6)),
            fett = listOf(Koerpermessung(b.um(6, 50, tag(30)), 21.8), Koerpermessung(b.um(6, 50), 21.6)),
        ),
        heute, b.zone,
    )!!

    /**
     * Der Stand der Herzseite, wie [coredevices.pebble.kiesel.Gesundheitsdaten.lade]
     * ihn baute. [mitFrueheren] = false: nur die Woche selbst - das Band steht
     * erst ab dem vierten Tag und urteilt nie. [blutdruckInDerWoche] =
     * false: zuletzt vor sieben Tagen gemessen, in der Woche nicht.
     */
    fun stand(mitFrueheren: Boolean = true, blutdruckInDerWoche: Boolean = true): GesundheitsStand =
        stand(hrvWerte(mitFrueheren), blutdruckAlle(mitFrueheren, blutdruckInDerWoche))

    /** Der Stand mit diesen HRV-Naechten und Blutdruckmessungen; der Rest wie im Entwurf. */
    fun stand(hrv: Map<LocalDate, Int>, alle: List<Blutdruck>): GesundheitsStand {
        return GesundheitsStand(
            schritte = 0, aktivMinuten = 0, meter = 0.0, wocheSchritte = emptyList(),
            nacht = b.nacht,
            ruhepulsWoche = listOf(54, 56, 55, 55, 54, 56),
            wocheSchlaf = emptyList(),
            puls = puls,
            spo2 = spo2,
            wasserMl = 0, glaeser = emptyList(), wasserZiel = null, supplemente = null, koffein = emptyList(),
            // Die Karte zaehlt 30 Tage, die Woche rechnet mit allen (siehe lade).
            blutdruck = alle.filter { !it.zeit.isBefore(jetzt.minus(Duration.ofDays(30))) },
            letzteDaten = jetzt.minusSeconds(120),
            pulsZonen = listOf(107, 133, 166),
            hrvTage = Gesundheitsrechnung.bandwoche(hrv, heute),
            // Wie lade: ohne jede Messung keine Woche.
            blutdruckWoche = if (alle.isEmpty()) null else Gesundheitsrechnung.blutdruckwoche(alle, heute, b.zone),
        )
    }
}

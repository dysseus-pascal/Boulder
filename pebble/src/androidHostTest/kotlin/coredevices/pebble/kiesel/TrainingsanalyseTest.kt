package coredevices.pebble.kiesel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingsanalyseTest {

    /** Eine gerade Strecke nach Osten, ein Punkt je Sekunde, rund 10 m/s. */
    private fun gerade(sekunden: Int, hoehe: Double? = 400.0): List<Spur.Punkt> =
        (0..sekunden).map { s ->
            Spur.Punkt(1_000_000L + s, 47.0, 7.0 + s * 0.000131, hoehe?.plus(s * 0.5), 5f)
        }

    @Test
    fun zonenAusDemMaximalpuls() {
        assertEquals(0, Trainingsanalyse.zone(90, 190))
        assertEquals(1, Trainingsanalyse.zone(95, 190))
        assertEquals(3, Trainingsanalyse.zone(140, 190))
        assertEquals(5, Trainingsanalyse.zone(171, 190))
    }

    @Test
    fun zonenSekundenGeltenBisZumNaechstenPunkt() {
        val puls = listOf(Pulspunkt(0, 100), Pulspunkt(10, 100), Pulspunkt(20, 150), Pulspunkt(600, 150))
        val z = Trainingsanalyse.zonenSekunden(puls, 190)
        assertEquals(20L, z[1])          // zweimal 10 s in Zone 1
        // Das Loch von 580 s zaehlt hoechstens eine Minute, dazu 10 s Rest
        assertEquals(70L, z[3])
    }

    @Test
    fun streckeUndTempo() {
        val s = Trainingsanalyse.strecke(gerade(60))
        assertEquals(61, s.size)
        assertTrue(s.last().meter > 550 && s.last().meter < 650)
        assertTrue(s[30].tempo > 9.0 && s[30].tempo < 11.0)
    }

    @Test
    fun bewegtOhneHalt() {
        // 30 s fahren, 60 s an der Ampel (derselbe Punkt), 30 s fahren.
        val fahrt = gerade(30)
        val halt = (1..60).map { fahrt.last().copy(zeit = fahrt.last().zeit + it) }
        val weiter = gerade(30).drop(1).map { it.copy(zeit = it.zeit + 90, lon = it.lon + 30 * 0.000131) }
        val s = Trainingsanalyse.strecke(fahrt + halt + weiter)
        val b = Trainingsanalyse.bewegt(s)
        assertTrue("bewegt $b", b in 55..70)
    }

    @Test
    fun sprungZaehltNichtAlsWeg() {
        // Nach 30 s ein Kilometer Versatz nach Norden - verlorener Empfang.
        val punkte = gerade(60).mapIndexed { i, p -> if (i > 30) p.copy(lat = p.lat + 0.009) else p }
        val s = Trainingsanalyse.strecke(punkte)
        assertTrue(s[31].nachSprung)
        assertTrue(s.count { it.nachSprung } == 1)
        assertEquals(Spur.laenge(punkte), s.last().meter, 0.01)
    }

    private val mann = Trainingsanalyse.Geschlecht.Mann

    /** Die Radfahrt vom 29.9.2026, 17:37-18:00: Puls je Minute aus health_data. */
    private val fahrt = listOf(
        95, 94, 126, 121, 120, 122, 121, 128, 130, 132, 128, 122,
        129, 125, 140, 139, 137, 140, 135, 125, 129, 125, 143, 145,
    ).mapIndexed { i, bpm -> Pulspunkt(i * 60L, bpm.toLong()) }

    /** Keytel (2005) fuer Maenner, die Formel davor - hier nur als Vergleich. */
    private fun keytel(puls: List<Pulspunkt>, kg: Double, alter: Int) =
        puls.sumOf { (-55.0969 + 0.6309 * it.bpm + 0.1988 * kg + 0.2017 * alter) / 4.184 }

    @Test
    fun kalorienDerFahrtLiegenBeiFitbit() {
        // Fitbit: 180 kcal in 23:12 min (7,76 je Minute). Keytel gab 11,3 je
        // Minute. Mann, 41, 70 kg, Ruhepuls 54 aus den Naechten, Maximum 190.
        val neu = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, 190)!!
        val jeMinute = neu / (23 + 10 / 60.0)
        assertTrue("je Minute $jeMinute", jeMinute in 7.3..8.6)
        assertTrue("Keytel ${keytel(fahrt, 70.0, 41)}", neu < 0.8 * keytel(fahrt, 70.0, 41))
        // Ohne eingestelltes Maximum (Tanaka, 179): rund 198, zehn Prozent ueber Fitbit.
        val tanaka = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, null)!!
        assertEquals(198.0, tanaka, 2.0)
    }

    @Test
    fun aktiveKalorienOhneRuheumsatz() {
        // Der Ruheumsatz bei 70 kg: 1,225 kcal je Minute, 23 min und der letzte Punkt 10 s.
        val gesamt = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, 190)!!
        val aktiv = Trainingsanalyse.aktiveKalorien(fahrt, 70.0, 41, mann, 54, 190)!!
        assertEquals(gesamt - 1.225 * (23 + 10 / 60.0), aktiv, 0.01)
        // Unter dem Ruhepuls ist nichts aktiv.
        val ruhig = (0..60).map { Pulspunkt(it * 10L, 45) }
        assertEquals(0.0, Trainingsanalyse.aktiveKalorien(ruhig, 70.0, 41, mann, 54, 190)!!, 0.001)
        assertNull(Trainingsanalyse.aktiveKalorien(fahrt, 0.0, 41, mann, 54, 190))
    }

    @Test
    fun maximumWeitUeberTanakaWirdNichtGeglaubt() {
        // 65 Jahre: Tanaka 162,5, Grenze 187,5. 190 gilt nicht, 185 schon.
        assertEquals(
            Trainingsanalyse.kalorien(fahrt, 70.0, 65, mann, 54, null)!!,
            Trainingsanalyse.kalorien(fahrt, 70.0, 65, mann, 54, 190)!!, 0.001,
        )
        assertTrue(
            Trainingsanalyse.kalorien(fahrt, 70.0, 65, mann, 54, 185)!! <
                Trainingsanalyse.kalorien(fahrt, 70.0, 65, mann, 54, null)!!,
        )
    }

    @Test
    fun ohneRuhepulsEinErsatz() {
        // Ohne Nacht gilt 60: etwas weniger als mit 54, aber in derselben Gegend.
        val mit = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, 190)!!
        val ohne = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, null, 190)!!
        assertTrue("$ohne / $mit", ohne < mit && ohne > 0.9 * mit)
        // Ohne Maximum das nach Tanaka (179): derselbe Puls ist mehr Anstrengung.
        val tanaka = Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, null)!!
        assertTrue("$tanaka", tanaka > mit)
    }

    @Test
    fun unterDemRuhepulsBleibtDerRuheumsatz() {
        // Zehn Minuten bei 45, Ruhepuls 54: 3,5 ml/kg/min, 70 kg -> 1,225 kcal je Minute.
        val ruhig = (0..60).map { Pulspunkt(it * 10L, 45) }
        assertEquals(10 * 1.225 + 1.225 / 6, Trainingsanalyse.kalorien(ruhig, 70.0, 41, mann, 54, 190)!!, 0.01)
    }

    @Test
    fun lueckenZaehlenNicht() {
        // Zwei Punkte zehn Minuten auseinander: der erste gilt eine Minute, der letzte zehn Sekunden.
        val luecke = listOf(Pulspunkt(0, 130), Pulspunkt(600, 130))
        val dicht = (0..6).map { Pulspunkt(it * 10L, 130) }
        assertEquals(
            Trainingsanalyse.kalorien(dicht, 70.0, 41, mann, 54, 190)!!,
            Trainingsanalyse.kalorien(luecke, 70.0, 41, mann, 54, 190)!!, 0.01,
        )
    }

    @Test
    fun ohneGewichtOderMitUnsinnNichts() {
        assertNull(Trainingsanalyse.kalorien(fahrt, 0.0, 41, mann, 54, 190))
        assertNull(Trainingsanalyse.kalorien(emptyList(), 70.0, 41, mann, 54, 190))
        // Ein Maximum unter dem Ruhepuls wird durch Tanaka ersetzt, nicht geglaubt.
        assertEquals(
            Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, null)!!,
            Trainingsanalyse.kalorien(fahrt, 70.0, 41, mann, 54, 50)!!, 0.001,
        )
    }

    @Test
    fun frauenUndAeltereMitKleinererVo2max() {
        assertEquals(39.64, Trainingsanalyse.vo2maxMitte(41, mann), 0.01)
        assertEquals(48.0, Trainingsanalyse.vo2maxMitte(18, mann), 0.01)
        assertEquals(24.4, Trainingsanalyse.vo2maxMitte(90, mann), 0.01)
        assertTrue(Trainingsanalyse.vo2maxMitte(41, Trainingsanalyse.Geschlecht.Frau) < 30.0)
    }

    @Test
    fun ruhepulsIstDerMedianDerLetztenNaechte() {
        assertEquals(54, Trainingsanalyse.ruhepulsAusNaechten(listOf(54, 53, 55)))
        // Keine Messung (0) zaehlt nicht; nur die letzten sieben.
        assertEquals(50, Trainingsanalyse.ruhepulsAusNaechten(listOf(80, 80, 0, 50, 50, 50, 50, 60, 60, 60)))
        assertNull(Trainingsanalyse.ruhepulsAusNaechten(listOf(0, 0)))
    }

    @Test
    fun einbruchFaelltWeg() {
        // Alle 10 s ein Punkt um 130; nach 60 s dreissig Sekunden lang 90,
        // dann wieder 130 - der Sensor am Lenker.
        val puls = (0..15).map { i ->
            Pulspunkt(i * 10L, if (i in 6..8) 90L else 130L)
        }
        val e = Trainingsanalyse.entstoert(puls)
        assertEquals(13, e.size)
        assertTrue(e.none { it.bpm == 90L })
        assertEquals(130L, e.maxOf { it.bpm })
        assertEquals(130L, e.minOf { it.bpm })
    }

    @Test
    fun echterAbfallBleibt() {
        // An der Ampel: von 140 auf 100 und dort geblieben.
        val puls = (0..15).map { i -> Pulspunkt(i * 10L, if (i < 6) 140L else 100L) }
        assertEquals(puls, Trainingsanalyse.entstoert(puls))
    }

    @Test
    fun anstiegAmAnfangBleibt() {
        // Der Puls kommt in 30 s von 85 auf 125 und ein Zacken faellt kurz
        // auf 92: der Anstieg ist echt, der Zacken nicht.
        val puls = listOf(85L, 110L, 125L, 92L, 128L, 130L, 131L).mapIndexed { i, b -> Pulspunkt(i * 10L, b) }
        val e = Trainingsanalyse.entstoert(puls)
        assertEquals(listOf(85L, 110L, 125L, 128L, 130L, 131L), e.map { it.bpm })
    }

    @Test
    fun ausreisserMachtKeineSpitze() {
        // Ein einzelner Punkt 40 m voraus: er zaehlte 80 m in zwei Sekunden
        // und war die Spitze des Tages.
        val punkte = gerade(60).mapIndexed { i, p -> if (i == 30) p.copy(lon = p.lon + 4 * 0.000131) else p }
        val s = Trainingsanalyse.strecke(punkte)
        assertEquals(60, s.size)
        assertTrue("Spitze ${s.maxOf { it.tempo }}", s.maxOf { it.tempo } < 12.0)
        assertEquals(Spur.laenge(gerade(60)), s.last().meter, 1.0)
    }

    @Test
    fun pulsUeberStreckeLiegtZwischenDenPunkten() {
        val s = Trainingsanalyse.strecke(gerade(60))
        val p = Trainingsanalyse.pulsUeberStrecke(listOf(Pulspunkt(30, 120)), s)
        assertEquals(1, p.size)
        assertTrue(p[0].meter > s[29].meter && p[0].meter < s[31].meter)
    }

    @Test
    fun kilometerMitRest() {
        val s = Trainingsanalyse.strecke(gerade(250))   // rund 2,5 km
        val km = Trainingsanalyse.kilometer(s, emptyList())
        assertEquals(3, km.size)
        assertEquals(1, km[0].nummer)
        assertTrue(km[2].meter < 900)
        assertTrue(km[0].aufstieg > 40)   // 0,5 m je Sekunde, rund 100 s je km
    }

    @Test
    fun hoeheNurWennVorhanden() {
        assertTrue(Trainingsanalyse.hoeheUeberStrecke(Trainingsanalyse.strecke(gerade(20, null))).isEmpty())
        assertEquals(21, Trainingsanalyse.hoeheUeberStrecke(Trainingsanalyse.strecke(gerade(20))).size)
    }

    @Test
    fun pulsNurAusDemFensterDerSitzung() {
        val von = java.time.Instant.ofEpochSecond(1_000_000)
        val bis = von.plusSeconds(600)
        val messungen = listOf(
            von.plusSeconds(20) to 120L,
            von.minusSeconds(10) to 90L,      // vor dem Start
            von to 100L,
            bis to 110L,                      // genau am Ende: schon draussen
            bis.minusSeconds(1) to 130L,
        )
        val puls = Trainingsanalyse.pulsInSitzung(messungen, von, bis)
        assertEquals(listOf(Pulspunkt(0, 100), Pulspunkt(20, 120), Pulspunkt(599, 130)), puls)
    }

    @Test
    fun kalorienDerAkteSindDieDerAnsichtOhneRuheumsatz() {
        // Akte und Ansicht rechnen mit denselben entstoerten Punkten; die Akte
        // laesst nur den Ruheumsatz weg, den sie selbst zaehlt.
        val roh = (0L..600L step 10).map { Pulspunkt(it, if (it == 300L) 80 else 140) }
        val puls = Trainingsanalyse.entstoert(roh)
        val mann = Trainingsanalyse.Geschlecht.Mann
        val ansicht = Trainingsanalyse.kalorien(puls, 70.0, 41, mann, 54, 179)!!
        val akte = Trainingsanalyse.aktiveKalorien(puls, 70.0, 41, mann, 54, 179)!!
        // Der Aussetzer bei 300 s faellt weg; 600 s Kurve plus 10 s fuer den letzten Punkt.
        assertEquals(60, puls.size)
        val ruhe = Trainingsanalyse.VO2_RUHE * 70.0 / 1000 * Trainingsanalyse.KCAL_JE_LITER * (600 + 10) / 60.0
        assertEquals(ansicht - ruhe, akte, 0.01)
    }

    @Test
    fun zonenMinutenUndProzentAusDenselbenSekunden() {
        // 24 s in Zone 1 sind 2 % - und weniger als eine Minute, nicht "0 min".
        val a = Trainingsanalyse.zonenAnteile(longArrayOf(0, 24, 600, 576, 0, 0))
        assertEquals(listOf(0L to 0, 0L to 2, 10L to 50, 10L to 48, 0L to 0, 0L to 0), a)
        // Abgeschnitten ergaeben drei Drittel 99 %; nach dem groessten Rest sind es 100.
        val drittel = Trainingsanalyse.zonenAnteile(longArrayOf(0, 600, 600, 600, 0, 0))
        assertEquals(100, drittel.sumOf { it.second })
        assertEquals(listOf(10L, 10L, 10L), drittel.drop(1).take(3).map { it.first })
        assertEquals(List(6) { 0L to 0 }, Trainingsanalyse.zonenAnteile(LongArray(6)))
    }

    @Test
    fun pulsInDerPauseZaehltNicht() {
        val puls = (0L..100L step 10).map { Pulspunkt(it, 140) }
        val ohne = Trainingsanalyse.ohnePausen(puls, listOf(30L to 60L))
        assertEquals(listOf(0L, 10L, 20L, 60L, 70L, 80L, 90L, 100L), ohne.map { it.sekunde })
        assertEquals(puls, Trainingsanalyse.ohnePausen(puls, emptyList()))
    }

    @Test
    fun kilometerOhneDiePause() {
        // Rund 100 s je Kilometer; im ersten liegt eine Pause von 30 s.
        val s = Trainingsanalyse.strecke(gerade(250))
        val ohne = Trainingsanalyse.kilometer(s, emptyList())
        val mit = Trainingsanalyse.kilometer(s, emptyList(), listOf(40L to 70L))
        assertEquals(ohne[0].sekunden - 30, mit[0].sekunden)
        assertEquals(ohne[1].sekunden, mit[1].sekunden)
    }

    /** Ein Gang bergauf mit wanderndem GPS: drei Meter je Punkt, die Hoehe zittert um zwei Meter. */
    private fun huegel(): List<Spur.Punkt> = (0 until 1100).map { i ->
        val rauschen = 2 * Math.sin(i * 1.7) + 1.5 * Math.cos(i * 0.31)
        Spur.Punkt(1_000_000L + i * 3L, 47.0, 7.0 + i * 0.0000396, 500.0 + i * 0.08 + rauschen, 5f)
    }

    @Test
    fun dieKilometerErgebenDenAufstiegOben() {
        val punkte = huegel()
        val s = Trainingsanalyse.strecke(punkte)
        val km = Trainingsanalyse.kilometer(s, emptyList())
        assertEquals(4, km.size)   // drei ganze und rund 300 m Rest
        assertEquals(Trainingsanalyse.aufstieg(punkte), km.sumOf { it.aufstieg }, 0.001)
    }

    @Test
    fun aufstiegLiegtNaheAmProfilNichtAmRauschen() {
        // 88 m Hoehenunterschied. Jedes positive Delta roh summiert gab ein
        // Vielfaches; geglaettet mit Schwelle bleibt es beim Hoehenunterschied.
        val punkte = huegel()
        val roh = punkte.zipWithNext { a, b -> (b.hoehe!! - a.hoehe!!).coerceAtLeast(0.0) }.sum()
        val auf = Trainingsanalyse.aufstieg(punkte)
        assertTrue("roh $roh", roh > 500)
        assertTrue("aufstieg $auf", auf in 75.0..100.0)
        // Das Profil steigt um dasselbe, das der Aufstieg sagt.
        val profil = Trainingsanalyse.hoeheUeberStrecke(Trainingsanalyse.strecke(punkte))
        assertEquals(auf, profil.maxOf { it.wert } - profil.minOf { it.wert }, 10.0)
    }
}

package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.js.CompanionAppDevice
import kotlin.uuid.Uuid

/**
 * Boulder: Begleiter, die fest in der App stecken.
 *
 * WARUM: ein Begleiter entsteht sonst nur fuer eine Uhr-App, die in der
 * Sammlung des Telefons steht (PBW mit PebbleKit JS oder PebbleKit-Paket).
 * Kieselsport ist aber eine System-App der Boulder-Firmware - ohne Eintrag in
 * der Sammlung kaeme keine ihrer Nachrichten irgendwo an. Die App, die
 * libpebble einbettet, bringt fuer solche Uhr-Apps ihren eigenen Begleiter mit;
 * er ersetzt dann jeden Weg ueber die Sammlung, also auch PebbleKit.
 */
fun interface BuiltInCompanions {
    /** Der Begleiter fuer die Uhr-App [uuid], oder null, wenn die App keinen hat. */
    fun create(uuid: Uuid, device: CompanionAppDevice): CompanionApp?

    /**
     * Ein Begleiter NEBEN dem der Sammlung, fuer eine Uhr-App, die dort steht
     * und ihr eigenes PebbleKit JS mitbringt (Konfigseite, Pins).
     *
     * ER ERSETZT NUR DEN WEG ZU ANDROID-APPS (PebbleKit Android): frueher
     * hoerte dort eine andere App mit und trug ein, was die Uhr meldete. Jetzt
     * tut das die einbettende App selbst - und die andere bekommt nichts mehr,
     * sonst stuende jedes Ereignis zweimal in der Gesundheitsakte. Das
     * PebbleKit JS der Uhr-App laeuft unveraendert weiter und bestaetigt die
     * Nachrichten - ausser der Begleiter ist [BestaetigtSelbst]. Laeuft es
     * nicht ([pkjsRunning] false, etwa ohne Netzwerk-Freigabe), muss der
     * Begleiter selbst bestaetigen - sonst wiederholt die Uhr jede Nachricht.
     */
    fun createBeside(uuid: Uuid, device: CompanionAppDevice, pkjsRunning: Boolean): CompanionApp? = null
}

object NoBuiltInCompanions : BuiltInCompanions {
    override fun create(uuid: Uuid, device: CompanionAppDevice): CompanionApp? = null
}

/**
 * Boulder: ein Begleiter neben PebbleKit JS, der jede Nachricht erst nach dem
 * Sichern bestaetigt. Die Uhr gibt mit der Bestaetigung ihre Kopie auf -
 * PebbleKit JS bestaetigte beim Empfang, also schweigt es neben ihm.
 */
interface BestaetigtSelbst

fun pkjsBestaetigt(daneben: CompanionApp?): Boolean = daneben !is BestaetigtSelbst

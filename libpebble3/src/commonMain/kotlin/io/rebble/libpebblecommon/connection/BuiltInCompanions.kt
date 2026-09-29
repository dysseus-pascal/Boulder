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
}

object NoBuiltInCompanions : BuiltInCompanions {
    override fun create(uuid: Uuid, device: CompanionAppDevice): CompanionApp? = null
}

package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform
import kotlin.uuid.Uuid

/**
 * Welche Uhr-Apps installiert sind.
 *
 * DIE FESTE REGEL: was zu einer eigenen Uhr-App gehoert (Wasser und Koffein zu
 * Drinktervall, Praeparate zu SupCycle), erscheint in Boulder nur, wenn diese
 * App auf der Uhr ist. Wer sie nicht hat, soll keine leeren Zeilen und keine
 * Mahnungen sehen. Ein eigener Schalter waere Bedienballast.
 */
internal object UhrApps {
    /** Die Apps im Locker - null, wenn das gerade niemand sagen kann. */
    suspend fun installiert(): Set<Uuid>? = versuche("UhrApps", "Apps der Uhr") {
        KoinPlatform.getKoin().getOrNull<LibPebble>()?.getAllLockerBasicInfo()?.first()?.map { it.id }?.toSet()
    }

    /**
     * Ob [app] dazugehoert. UNBEKANNT ZAEHLT ALS JA: ein Fehler beim Fragen
     * soll keine Daten verstecken, die es gibt.
     */
    fun hat(apps: Set<Uuid>?, app: Uuid): Boolean = apps?.contains(app) ?: true
}

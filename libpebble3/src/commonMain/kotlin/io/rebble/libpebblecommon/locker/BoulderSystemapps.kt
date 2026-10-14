package io.rebble.libpebblecommon.locker

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform

private val systemAppLogger = Logger.withTag("Locker")

/**
 * Boulder: die Systemapps in den Locker [dao] eintragen - herausgeloest aus
 * Locker.insertSystemApps, damit ein Test es auf einen BESTEHENDEN Locker
 * anwenden kann (LockerSystemAppsTest). [force]: alle neu und an ihre
 * Standardstelle; sonst nur die fehlenden, jede hinter ihren Vorgaenger.
 */
internal suspend fun systemAppsEinfuegen(dao: LockerEntryRealDao, force: Boolean, syncLimit: Int) {
    val vorhanden = dao.getAllBasicInfo()
    // Boulder: eine Systemapp, die es in der Firmware nicht mehr gibt (Workout), faellt
    // aus der Liste - sonst stuende sie dort als Eintrag, der nichts oeffnet.
    vorhanden.filter { it.systemApp && findSystemApp(it.id) == null }.forEach {
        systemAppLogger.d { "Removing system app the firmware no longer has: ${it.title}" }
        dao.markForDeletion(it.id)
    }
    // Boulder: steht eine Systemapp als gewoehnliche App da (Kieselsport aus dem
    // Store), wird sie zur Systemapp - die Firmware bringt sie selbst mit.
    val lockerApps = vorhanden.filter { it.systemApp || findSystemApp(it.id) == null }.map { it.id }
    val systemAppsToInsert =
        SystemApps.entries.filter { force || !lockerApps.contains(it.uuid) }
            .map { systemApp ->
                LockerEntry(
                    id = systemApp.uuid,
                    version = "",
                    title = systemApp.name,
                    type = systemApp.type.code,
                    developerName = "",
                    configurable = false,
                    "",
                    platforms = systemApp.compatiblePlatforms.map {
                        LockerEntryPlatform(
                            lockerEntryId = systemApp.uuid,
                            sdkVersion = "",
                            processInfoFlags = 0,
                            name = it.codename,
                            pbwIconResourceId = 0,
                        )
                    },
                    systemApp = true,
                    orderIndex = systemApp.defaultOrder,
                    capabilities = emptyList(),
                )
            }

    if (systemAppsToInsert.isNotEmpty()) {
        dao.insertOrReplaceAndOrder(systemAppsToInsert, syncLimit)
        // Boulder: kommt eine Systemapp spaeter dazu (Kieselsport statt Workout), stuende
        // sie mit ihrer negativen Standardposition ganz vorn - also hinter ihren Vorgaenger.
        if (!force) {
            systemAppsToInsert.forEach { neu ->
                val system = findSystemApp(neu.id) ?: return@forEach
                val vorgaenger = SystemApps.entries.getOrNull(system.ordinal - 1)
                    ?.takeIf { it.type == system.type } ?: return@forEach
                val stelle = dao.getEntry(vorgaenger.uuid)?.orderIndex ?: return@forEach
                val jetzt = dao.getEntry(neu.id)?.orderIndex ?: return@forEach
                dao.setOrder(neu.id, if (jetzt < stelle) stelle else stelle + 1, syncLimit)
            }
        }
    }
}

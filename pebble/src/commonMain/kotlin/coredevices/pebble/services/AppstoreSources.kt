package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.database.AppstoreSource
import coredevices.database.AppstoreSourceDao
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val logger = Logger.withTag("AppstoreSources")
private const val PEBBLE_FEED_URL = "https://appstore-api.repebble.com/api"
const val REBBLE_FEED_URL = "https://appstore-api.rebble.io/api"

private val INITIAL_APPSTORE_SOURCES = listOf(
    AppstoreSource(
        url = PEBBLE_FEED_URL,
        title = "Pebble App Feed",
        algoliaAppId = "GM3S9TRYO4",
        algoliaApiKey = "0b83b4f8e4e8e9793d2f1f93c21894aa",
        algoliaIndexName = "apps"
    ),
    AppstoreSource(
        url = REBBLE_FEED_URL,
        title = "Rebble App Feed",
        algoliaAppId = "7683OW76EQ",
        algoliaApiKey = "252f4938082b8693a8a9fc0157d1d24f",
        algoliaIndexName = "rebble-appstore-production",
    )
)

fun AppstoreSource.isRebbleFeed(): Boolean = url == REBBLE_FEED_URL

suspend fun AppstoreSourceDao.initAppstoreSourcesDB(pebbleAccount: PebbleAccountProvider) {
    val current = getAllSources().first()
    //TODO: remove the migration stuff after a while
    val needsInit = current.isEmpty() ||
            current.any { it.algoliaAppId == null } || // migrate old entries
            current.firstOrNull { it.url == "https://appstore-api.repebble.com/api" }?.title != "Pebble App Feed" // migrate title change
    if (needsInit) {
        logger.d { "Initializing appstore sources database" }
        current.forEach { source ->
            deleteSourceById(source.id)
        }
        INITIAL_APPSTORE_SOURCES.forEach { source ->
            insertSource(source)
        }
    } else {
        logger.d { "Appstore sources database already initialized" }
    }

    GlobalScope.launch {
        val rebbleSource = getAllSources().first()
            .firstOrNull { it.isRebbleFeed() }
        if (rebbleSource == null) {
            return@launch
        }
        pebbleAccount.get().loggedIn.collect {
            val loggedIn = it != null
            setSourceEnabled(rebbleSource.id, loggedIn)
        }
    }
}

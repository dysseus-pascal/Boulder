package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import co.touchlab.kermit.Logger
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.services.AppstoreService
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

class AppStoreCollectionScreenViewModel(
    val libPebble: LibPebble,
    val platform: Platform,
    val appstoreSourceDao: AppstoreSourceDao,
    appstoreSourceId: Int,
    val path: String,
    val appType: AppType?,
): ViewModel(), KoinComponent {
    val logger = Logger.withTag("AppStoreCollectionScreenVM")
    var loadedApps by mutableStateOf<Flow<PagingData<CommonApp>>?>(null)
    val watchType = viewModelScope.async {
        libPebble.watches.map {
            it.sortedWith(PebbleDeviceComparator).filterIsInstance<KnownPebbleDevice>()
                .firstOrNull()
        }.map { it?.watchType?.watchType}.firstOrNull() ?: WatchType.DIORITE
    }
    val appstoreService = viewModelScope.async {
        val source = appstoreSourceDao.getSourceById(appstoreSourceId)!!
        get<AppstoreService> { parametersOf(source) }
    }

    fun load() {
        viewModelScope.launch {
            val service = appstoreService.await()
            val watchType = watchType.await()
            loadedApps = Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = {
                    service.fetchAppStoreCollection(
                        path,
                        appType,
                        watchType,
                    )
                },
            ).flow
        }
    }
}

@Composable
fun AppStoreCollectionScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
    sourceId: Int,
    path: String, // e.g. "collection/most-loved"
    title: String,
    appType: AppType?,
) {
    val viewModel = koinViewModel<AppStoreCollectionScreenViewModel> {
        parametersOf(
            sourceId,
            path,
            appType
        )
    }
    LaunchedEffect(Unit) {
        viewModel.load()
    }
    val apps = viewModel.loadedApps
    AppStoreCollectionScreen(
        navBarNav = navBarNav,
        topBarParams = topBarParams,
        collectionTitle = title,
        apps = apps?.collectAsLazyPagingItems(),
    )
}

@Composable
fun AppStoreCollectionScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
    collectionTitle: String,
    apps: LazyPagingItems<CommonApp>?,
) {
    LaunchedEffect(collectionTitle) {
        topBarParams.title(collectionTitle)
        topBarParams.canGoBack(true)
        topBarParams.actions {}
        topBarParams.searchAvailable(false)
        topBarParams.goBack.collect {
            navBarNav.goBack()
        }
    }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize()) {
        if (apps == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.FixedSize(120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                items(
                    count = apps.itemCount,
                    key = apps.itemKey{ it.storeId ?: it.uuid }
                ) { index ->
                    val entry = apps[index]!!
                    NativeWatchfaceCard(
                        entry,
                        navBarNav,
                        false,
                        width = 120.dp,
                        topBarParams = topBarParams,
                    )
                }
            }
        }
    }
}

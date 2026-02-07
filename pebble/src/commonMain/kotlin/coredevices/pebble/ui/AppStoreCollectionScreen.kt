package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.filter
import co.touchlab.kermit.Logger
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.services.AppstoreService
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    val filterScaled = MutableStateFlow(true)
    val logger = Logger.withTag("AppStoreCollectionScreenVM")
    var loadedApps by mutableStateOf<Flow<PagingData<CommonApp>>?>(null)
    val appstoreService = viewModelScope.async {
        val source = appstoreSourceDao.getSourceById(appstoreSourceId)!!
        get<AppstoreService> { parametersOf(source) }
    }

    fun load(watchType: WatchType) {
        viewModelScope.launch {
            val service = appstoreService.await()
            val pagerFlow = Pager(
                config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                pagingSourceFactory = {
                    service.fetchAppStoreCollection(
                        path,
                        appType,
                        watchType,
                    )
                },
            ).flow.cachedIn(viewModelScope)
            loadedApps = combine(pagerFlow, filterScaled) { pagingData, scaled ->
                pagingData.filter { app ->
                    if (!scaled && !app.isNativelyCompatible) {
                        false
                    } else {
                        true
                    }
                }
            }
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
    val lastConnectedWatch = lastConnectedWatch()
    val watchType = lastConnectedWatch?.watchType?.watchType ?: WatchType.DIORITE
    LaunchedEffect(watchType) {
        viewModel.load(watchType)
    }
    val apps = viewModel.loadedApps?.collectAsLazyPagingItems()
    LaunchedEffect(title) {
        topBarParams.title(title)
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize()) {
        Column {
            Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp).horizontalScroll(rememberScrollState())) {
                if (watchType.performsScaling()) {
                    FilterChip(
                        selected = viewModel.filterScaled.value,
                        onClick = { viewModel.filterScaled.value = !viewModel.filterScaled.value },
                        label = { Text("Scaled") },
                        modifier = Modifier.padding(4.dp),
                        leadingIcon = if (viewModel.filterScaled.value) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = "Filter compatible",
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            if (apps == null || apps.itemCount == 0) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
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
                        key = apps.itemKey { it.storeId ?: it.uuid }
                    ) { index ->
                        val entry = apps[index]!!
                        NativeWatchfaceCard(
                            entry,
                            navBarNav,
                            width = 120.dp,
                            topBarParams = topBarParams,
                            highlightInLocker = true,
                        )
                    }
                }
            }
        }
    }
}

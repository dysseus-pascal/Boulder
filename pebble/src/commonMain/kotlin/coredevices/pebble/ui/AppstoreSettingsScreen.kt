package coredevices.pebble.ui

import CoreNav
import NoOpCoreNav
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coredevices.database.AppstoreSource
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.account.PebbleAccount
import coredevices.ui.M3Dialog
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

class AppstoreSettingsScreenViewModel(
    private val dao: AppstoreSourceDao,
    private val pebbleAccount: PebbleAccount,
    private val uriHandler: UriHandler
): ViewModel() {
    val pebbleLoggedIn = pebbleAccount.loggedIn
    val sources = dao.getAllSources()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun removeSource(sourceId: Int) {
        viewModelScope.launch {
            dao.deleteSourceById(sourceId)
        }
    }

    fun changeSourceEnabled(sourceId: Int, isEnabled: Boolean) {
        viewModelScope.launch {
            if (sources.value.firstOrNull { it.title == "Rebble" }?.id == sourceId && isEnabled && pebbleLoggedIn.value == null) {
                uriHandler.openUri(REBBLE_LOGIN_URI)
            } else {
                dao.setSourceEnabled(sourceId, isEnabled)
            }
        }
    }

    fun addSource(title: String, url: String) {
        viewModelScope.launch {
            val source = AppstoreSource(
                title = title,
                url = url
            )
            dao.insertSource(source)
        }
    }
}

@Composable
fun AppstoreSettingsScreen(nav: CoreNav) {
    val uriHandler = LocalUriHandler.current
    val viewModel = koinViewModel<AppstoreSettingsScreenViewModel> { parametersOf(uriHandler) }
    val sources by viewModel.sources.collectAsState()
    AppstoreSettingsScreen(
        nav = nav,
        sources = sources,
        onSourceRemoved = viewModel::removeSource,
        onSourceAdded = viewModel::addSource,
        onSourceEnableChange = viewModel::changeSourceEnabled,
    )
}

@Composable
fun AppstoreSettingsScreen(
    nav: CoreNav,
    sources: List<AppstoreSource>,
    onSourceRemoved: (Int) -> Unit,
    onSourceAdded: (title: String, url: String) -> Unit,
    onSourceEnableChange: (Int, Boolean) -> Unit,
) {
    var createSourceOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appstore Sources") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            nav.goBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        /*floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    createSourceOpen = true
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Source")
            }
        }*/
    ) { insets ->
        if (createSourceOpen) {
            CreateAppstoreSourceDialog(
                onDismissRequest = {
                    createSourceOpen = false
                },
                onSourceCreated = { title, url ->
                    onSourceAdded(title, url)
                    createSourceOpen = false
                }
            )
        }
        LazyColumn(Modifier.padding(insets)) {
            items(sources.size, { sources[it].id }) { i ->
                val source = sources[i]
                AppstoreSourceItem(
                    source = source,
                    onRemove = onSourceRemoved,
                    onEnableChange = onSourceEnableChange
                )
            }
        }
    }
}

@Composable
fun AppstoreSourceItem(
    source: AppstoreSource,
    onRemove: (Int) -> Unit,
    onEnableChange: (Int, Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = source.title)
        },
        supportingContent = {
            Text(text = source.url)
        },
        trailingContent = {
            Checkbox(
                checked = source.enabled,
                onCheckedChange = {
                    onEnableChange(source.id, it)
                }
            )
            /*IconButton(
                onClick = {
                    onRemove(source.id)
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Source")
            }*/
        },
        modifier = Modifier.clickable {
            onEnableChange(source.id, !source.enabled)
        }
    )
}

@Composable
fun CreateAppstoreSourceDialog(
    onDismissRequest: () -> Unit,
    onSourceCreated: (title: String, url: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    M3Dialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(Icons.Filled.Link, contentDescription = null)
        },
        title = {
            Text("Add Appstore Source")
        },
        buttons = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text("Cancel")
            }
            TextButton(
                onClick = {
                    onSourceCreated(title, url)
                },
                enabled = title.isNotBlank() &&
                        url.isNotBlank() &&
                        parseUrl(url)?.protocolOrNull in setOf(URLProtocol.HTTP, URLProtocol.HTTPS)
            ) {
                Text("Add")
            }
        }
    ) {
        Column {
            TextField(title, onValueChange = { title = it }, label = { Text("Name") })
            Spacer(Modifier.height(8.dp))
            TextField(url, onValueChange = { url = it }, label = { Text("Source URL") })
        }
    }
}

@Preview
@Composable
fun AppstoreSettingsScreenPreview() {
    PreviewWrapper {
        AppstoreSettingsScreen(
            nav = NoOpCoreNav,
            sources = listOf(
                AppstoreSource(id = 1, title = "Source 1", url = "https://example.com/source1"),
                AppstoreSource(id = 2, title = "Source 2", url = "https://example.com/source2"),
            ),
            onSourceRemoved = {},
            onSourceAdded = {_, _ -> },
            onSourceEnableChange = { _, _ -> }
        )
    }
}

@Preview
@Composable
fun PreviewCreateAppstoreSourceDialog() {
    PreviewWrapper {
        CreateAppstoreSourceDialog(
            onDismissRequest = {},
            onSourceCreated = { _, _ -> }
        )
    }
}
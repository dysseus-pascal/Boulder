package coredevices.ring.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.compose.collectAsLazyPagingItems
import coredevices.ui.M3Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

expect class SettingsBeeperContactsDialogViewModel(): ViewModel {
    fun getContacts(query: String?): PagingSource<Int, SettingsBeeperContact>
    val approvedIds: StateFlow<Set<String>>
    fun toggleContact(contactId: String)
    fun persist()
}

data class SettingsBeeperContact(
    val id: String,
    val name: String,
    val protocol: String
)

@Composable
fun SettingsBeeperContactsDialog(
    onDismissRequest: () -> Unit
) {
    val viewModel = koinViewModel<SettingsBeeperContactsDialogViewModel>()
    val approvedIds by viewModel.approvedIds.collectAsState()
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }

    val pager = remember(debouncedQuery) {
        Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { viewModel.getContacts(debouncedQuery.ifBlank { null }) }
        ).flow
    }.collectAsLazyPagingItems()

    DisposableEffect(Unit) {
        onDispose { viewModel.persist() }
    }

    M3Dialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Contacts, null) },
        title = { Text("Beeper Contacts") },
        buttons = {
            TextButton(onClick = onDismissRequest) {
                Text("Done")
            }
        }
    ) {
        Column {
            Text(
                "Select contacts to enable messaging them from Index. Try saying \"Message Alice - What's up?\" or \"Text Alice I'm running late\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
            ) {
                items(
                    count = pager.itemCount
                ) { index ->
                    val contact = pager[index] ?: return@items
                    val isApproved = approvedIds.contains(contact.id)
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.protocol) },
                        trailingContent = {
                            Checkbox(
                                checked = isApproved,
                                onCheckedChange = { viewModel.toggleContact(contact.id) }
                            )
                        }
                    )
                }
            }
        }
    }
}

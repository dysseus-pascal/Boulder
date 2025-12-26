package coredevices.pebble.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

@Stable
data class TopBarParams(
    val searchState: SearchState,
    val searchAvailable: (Boolean) -> Unit,
    val actions: (@Composable RowScope.() -> Unit) -> Unit,
    val title: (String) -> Unit,
    val canGoBack: (Boolean) -> Unit,
    val goBack: Flow<Unit>,
    val showSnackbar: (String) -> Unit,
)

data class SearchState(val query: String, val typing: Boolean)

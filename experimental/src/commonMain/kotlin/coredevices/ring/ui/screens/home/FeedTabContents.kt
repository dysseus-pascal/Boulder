package coredevices.ring.ui.screens.home

import CoreNav
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.dp
import coredevices.pebble.ui.TopBarParams
import coredevices.ring.service.RingEvent
import coredevices.ring.service.RingSync
import coredevices.ring.ui.components.chat.ChatInput
import coredevices.ring.ui.components.feed.FeedList
import coredevices.ring.ui.components.feed.ProgressChip
import coredevices.ring.ui.navigation.RingRoutes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun FeedTabContents(topBarParams: TopBarParams?, windowInsets: PaddingValues, coreNav: CoreNav, onAddItem: () -> Unit = {}, onAddChat: (String) -> Unit = {}) {
    Column(modifier = Modifier.padding(bottom = windowInsets.calculateBottomPadding()).fillMaxSize()) {
        LookaheadScope {
            FeedList(
                topBarParams = topBarParams,
                modifier = Modifier
                    .weight(1f)
                    .animateBounds(this@LookaheadScope),
                onItemSelected = { id ->
                    coreNav.navigateTo(RingRoutes.RecordingDetails(id))
                },
            )
            RingSyncIndicator(modifier = Modifier.align(Alignment.End).padding(8.dp))
        }
        Box(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            ChatInput(modifier = Modifier.fillMaxWidth(), onMicClick = onAddItem, onTextSubmit = onAddChat)
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun RingSyncIndicator(modifier: Modifier = Modifier) {
    val status = koinInject<RingSync>().ringEvents
        .filterIsInstance<RingEvent.Transfer>()
        .collectAsState(null)
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(status.value) {
        status.value.let { status ->
            if (status !is RingEvent.Transfer.InProgress || status.progress >= 1.0f) {
                delay(500.milliseconds)
                show = false
            } else {
                show = true
            }
        }
    }
    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val progress = (status.value as? RingEvent.Transfer.InProgress)?.progress ?: 0f
        ProgressChip(
            text = "Syncing",
            progress = progress,
        )
    }
}
package coredevices.ring.ui.components.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coredevices.indexai.data.entity.RecordingEntryStatus
import coredevices.indexai.database.dao.RecordingFeedItem
import coredevices.ring.ui.components.chat.ChatBubble
import coredevices.ring.ui.components.chat.ResponseBubble
import coredevices.ring.ui.components.chat.SemanticResultActionTaken
import coredevices.ring.ui.components.chat.SemanticResultIcon

@Composable
fun FeedListItem(
    chatBubbleModifier: Modifier = Modifier,
    feedItem: RecordingFeedItem,
    onSelected: (() -> Unit)?,
    onHold: (() -> Unit)?,
) {
    var parentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var userBubbleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var responseBubbleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var showOverflowIndicator by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .onGloballyPositioned { parentCoords = it }
            .drawConnectingLine(
                MaterialTheme.colorScheme.outlineVariant,
                parentCoords,
                userBubbleCoords,
                responseBubbleCoords
            )
    ) {
        ChatBubble(
            chatBubbleModifier
                .align(Alignment.End)
                .padding(start = 50.dp)
                .onGloballyPositioned { userBubbleCoords = it }
                .combinedClickable(
                    enabled = feedItem.entry != null,
                    onClick = { onSelected?.invoke() },
                    onLongClick = { onHold?.invoke() }
                ),
        ) {
            Column {
                when (feedItem.entry?.status) {
                    null -> Text("Processing...")
                    RecordingEntryStatus.pending -> Text("Transcribing...", overflow = TextOverflow.Ellipsis)
                    RecordingEntryStatus.completed, RecordingEntryStatus.agent_processing, RecordingEntryStatus.agent_error -> Text(
                        feedItem.entry!!.transcription ?: "No transcription",
                        overflow = TextOverflow.Clip,
                        maxLines = 2,
                        onTextLayout = { result ->
                            showOverflowIndicator = result.hasVisualOverflow
                        }
                    )
                    RecordingEntryStatus.transcription_error -> Text("Transcription Error")
                }
                if (showOverflowIndicator) {
                    Text(
                        "Show more...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier
                            .align(Alignment.End)
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        ResponseBubble(
            modifier = Modifier
                .align(Alignment.Start)
                .onGloballyPositioned { responseBubbleCoords = it }
                .clickable { onSelected?.invoke() },
            leading = {
                if (feedItem.semanticResult == null) {
                    if (feedItem.entry?.status == RecordingEntryStatus.transcription_error) {
                        Icon(
                            imageVector = Icons.Outlined.SpeakerNotesOff,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(12.dp))
                    }
                } else {
                    SemanticResultIcon(feedItem.semanticResult!!, modifier = Modifier.size(12.dp))
                }
            }
        ) {
            if (feedItem.semanticResult == null) {
                if (feedItem.entry?.status == RecordingEntryStatus.transcription_error) {
                    Text("No action taken")
                } else {
                    Text("Thinking", overflow = TextOverflow.Ellipsis)
                }
            } else {
                SemanticResultActionTaken(feedItem.semanticResult!!)
            }
        }
    }
}

private fun Modifier.drawConnectingLine(lineColor: Color, parentCoords: LayoutCoordinates?, userBubbleCoords: LayoutCoordinates?, responseBubbleCoords: LayoutCoordinates?): Modifier {
    return drawBehind {
        if (parentCoords == null || !parentCoords.isAttached) return@drawBehind

        if (userBubbleCoords != null && userBubbleCoords.isAttached && responseBubbleCoords != null && responseBubbleCoords.isAttached) {

            val userBounds = parentCoords.localBoundingBoxOf(userBubbleCoords)
            val responseBounds = parentCoords.localBoundingBoxOf(responseBubbleCoords)

            val startX = userBounds.right + 8.dp.toPx()
            val startY = userBounds.center.y

            val endX = responseBounds.left
            val endY = responseBounds.center.y

            val gutterX = size.width * 0.95f
            val cornerRadius = 16.dp.toPx()

            val path = Path().apply {
                moveTo(startX, startY)

                if (startX < gutterX) {
                    lineTo(gutterX, startY)
                } else {
                    moveTo(gutterX, startY)
                }

                lineTo(gutterX, endY - cornerRadius)

                quadraticTo(
                    gutterX, endY, // Control point (corner)
                    gutterX - cornerRadius, endY // End of curve
                )

                lineTo(endX, endY)
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
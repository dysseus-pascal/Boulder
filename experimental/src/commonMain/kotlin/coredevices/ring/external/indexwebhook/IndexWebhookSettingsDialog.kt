package coredevices.ring.external.indexwebhook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coredevices.ring.ui.relativeTime
import coredevices.ui.dismissKeyboardOnTapOutside
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexWebhookSettingsDialog(
    viewModel: IndexWebhookSettingsViewModel = koinViewModel()
) {
    val gesture by viewModel.gesture.collectAsState()
    val openFor = gesture ?: return
    val urlInput by viewModel.urlInput.collectAsState()
    val headerInputs by viewModel.headerInputs.collectAsState()
    val payloadMode by viewModel.payloadModeInput.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val enabled by viewModel.gestureEnabled.collectAsState()
    val hasStoredUrl by viewModel.gestureHasStoredUrl.collectAsState()

    BasicAlertDialog(
        onDismissRequest = viewModel::closeDialog,
    ) {
        Surface(
            // Cap the height so a long list of headers stays on screen; the
            // title and action buttons stay fixed while the body scrolls.
            modifier = Modifier.wrapContentWidth().heightIn(max = 600.dp)
                .dismissKeyboardOnTapOutside(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            // Resolved inside the dialog: dialogs have their own focus manager.
            val focusManager = LocalFocusManager.current
            val dismissKeyboard = KeyboardActions(onDone = { focusManager.clearFocus() })
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Webhook Configuration",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable body so the dialog never grows past its max height.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                Text(
                    "Send Index 01 recording data to an HTTP endpoint. Each recording gesture has its own endpoint.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Gesture selector
                Text(
                    "Gesture",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                IndexWebhookRecordingTrigger.entries.forEach { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openDialog(candidate) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = candidate == openFor,
                            onClick = { viewModel.openDialog(candidate) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(candidate.gestureLabel())
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (hasStoredUrl) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send on this gesture", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // URL field
                TextField(
                    value = urlInput,
                    onValueChange = viewModel::updateUrlInput,
                    singleLine = true,
                    label = { Text("Webhook URL") },
                    placeholder = { Text("https://example.com/webhook") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = dismissKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Headers editor
                Text(
                    "Headers",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                headerInputs.forEachIndexed { index, headerEntry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = headerEntry.name,
                            onValueChange = { viewModel.updateHeaderName(index, it) },
                            singleLine = true,
                            label = { Text("Name") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = dismissKeyboard,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextField(
                            value = headerEntry.value,
                            onValueChange = { viewModel.updateHeaderValue(index, it) },
                            singleLine = true,
                            label = { Text("Value") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = dismissKeyboard,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.removeHeader(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove header")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(onClick = viewModel::addHeader) {
                    Text("Add header")
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Payload mode dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    TextField(
                        value = payloadMode.displayName(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Send") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        IndexWebhookPayloadMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName()) },
                                onClick = {
                                    viewModel.updatePayloadMode(mode)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = viewModel::sendTestEvent,
                        enabled = urlInput.isNotBlank() && testState != WebhookTestState.Sending,
                    ) {
                        Text("Send test event")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when (val state = testState) {
                            WebhookTestState.Idle -> ""
                            WebhookTestState.Sending -> "Sending..."
                            is WebhookTestState.Done -> state.label
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if ((testState as? WebhookTestState.Done)?.ok == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Recent runs",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (runs.isEmpty()) {
                    Text(
                        "No runs yet. They'll show up here after your first recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                runs.forEach { run ->
                    RunRow(run)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                } // end scrollable body

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    if (hasStoredUrl) {
                        TextButton(onClick = viewModel::clearCurrentGesture) {
                            Text("Unlink")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = viewModel::closeDialog) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = viewModel::save) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: IndexWebhookRun) {
    val color =
        if (run.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Icon(
            if (run.ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            run.status,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            run.detailLine(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            relativeTime(run.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

private fun IndexWebhookRun.detailLine(): String =
    if (ok) "$detail · ${formatBytes(byteSize)} · $durationMs ms" else detail

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun IndexWebhookPayloadMode.displayName(): String = when (this) {
    IndexWebhookPayloadMode.RecordingOnly -> "Recording only"
    IndexWebhookPayloadMode.TranscriptionOnly -> "Transcription only"
    IndexWebhookPayloadMode.Both -> "Recording + Transcription"
}

private fun IndexWebhookRecordingTrigger.gestureLabel(): String = when (this) {
    IndexWebhookRecordingTrigger.SingleClickHold -> "Hold & talk"
    IndexWebhookRecordingTrigger.DoubleClickHold -> "Double click & hold"
}

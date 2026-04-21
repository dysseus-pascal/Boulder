package coredevices.coreapp.ui.screens

import CoreNav
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coredevices.libindex.LibIndex
import coredevices.ring.data.NoteShortcutType
import coredevices.ring.database.MusicControlMode
import coredevices.ring.database.Preferences
import coredevices.ring.database.SecondaryMode
import coredevices.ring.ui.components.Press
import coredevices.ring.ui.components.PressPatternDot
import coredevices.ring.ui.navigation.RingRoutes
import coredevices.ring.ui.screens.settings.AuthorizedIntegrations
import coredevices.ring.ui.screens.settings.IndexDeviceListItem
import coredevices.ring.ui.screens.settings.NoteShortcutDialog
import coredevices.ring.ui.viewmodel.SettingsViewModel
import coredevices.ui.PebbleElevatedButton
import coredevices.util.Platform
import coredevices.util.isAndroid
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import theme.onboardingScheme

@Composable
fun RingOnboardingScreen(
    coreNav: CoreNav,
) {
    val libIndex: LibIndex = koinInject()
    val preferences: Preferences = koinInject()
    val platform: Platform = koinInject()
    val viewModel = koinViewModel<SettingsViewModel>()

    val rings by libIndex.rings.collectAsState()
    val ringPairedState = viewModel.ringPaired.collectAsStateWithLifecycle()
    val ringPaired by derivedStateOf { ringPairedState.value != null }
    val currentRingName by viewModel.currentRingName.collectAsStateWithLifecycle()
    val musicControlMode by viewModel.musicControlMode.collectAsState()
    val secondaryMode by viewModel.secondaryMode.collectAsState()
    val noteShortcut by viewModel.noteShortcut.collectAsState()

    val showNoteShortcutDialog by viewModel.showNoteShortcutDialog.collectAsState()
    val availableNoteProviders by viewModel.availableNoteProviders.collectAsState()
    val availableReminderProviders by viewModel.availableReminderProviders.collectAsState()
    val isAndroid = remember { platform.isAndroid }

    if (showNoteShortcutDialog) {
        NoteShortcutDialog(
            availableNoteProviders = availableNoteProviders,
            availableReminderProviders = availableReminderProviders,
            currentShortcut = noteShortcut,
            onShortcutSelected = {
                viewModel.setNoteShortcut(it)
                viewModel.closeNoteShortcutDialog()
            },
            onDismissRequest = { viewModel.closeNoteShortcutDialog() },
        )
    }

    MaterialTheme(colorScheme = onboardingScheme) {
        Scaffold { windowInsets ->
            Box(modifier = Modifier.padding(windowInsets).fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Get Started!",
                        fontSize = 32.sp,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    IndexDeviceListItem(
                        headline = when {
                            ringPaired && currentRingName != null -> currentRingName!!
                            ringPaired -> "Paired to Index 01"
                            else -> "No Ring Paired"
                        },
                        buttons = {},
                        modifier = Modifier.padding(vertical = 8.dp),
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    // --- Notification integration section ---
                    SectionText("Default Notes & Reminders")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Choose where your notes and reminders are saved by default.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AuthorizedIntegrations(preferences)

                    Spacer(modifier = Modifier.height(10.dp))

                    PebbleElevatedButton(
                        text = "Add Integration",
                        onClick = { coreNav.navigateTo(RingRoutes.AddIntegration) },
                        primaryColor = true,
                        icon = Icons.Default.Add,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    ListItem(
                        modifier = Modifier.clickable { viewModel.showNoteShortcutDialog() },
                        headlineContent = { Text("Notification Shortcut") },
                        supportingContent = {
                            Text(
                                when (val shortcut = noteShortcut) {
                                    is NoteShortcutType.SendToMe -> "Email me"
                                    is NoteShortcutType.SendToNoteProvider -> shortcut.provider.title
                                    is NoteShortcutType.SendToReminderProvider -> shortcut.provider.title
                                }
                            )
                        },
                    )

                    SectionDivider()

                    // --- Button Actions section ---
                    SectionText("Music Play/Pause")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Single or double click without holding to play/pause music on your phone.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (!isAndroid) {
                        Text(
                            "Music controls are Android only.",
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PressPatternTile(
                                label = "Disabled",
                                pattern = emptyList(),
                                selected = musicControlMode == MusicControlMode.Disabled,
                                onClick = { viewModel.setMusicControlMode(MusicControlMode.Disabled) },
                            )
                            PressPatternTile(
                                label = "Single click",
                                pattern = listOf(Press.Short),
                                selected = musicControlMode == MusicControlMode.SingleClick,
                                onClick = { viewModel.setMusicControlMode(MusicControlMode.SingleClick) },
                            )
                            PressPatternTile(
                                label = "Double click",
                                pattern = listOf(Press.Short, Press.Short),
                                selected = musicControlMode == MusicControlMode.DoubleClick,
                                onClick = { viewModel.setMusicControlMode(MusicControlMode.DoubleClick) },
                            )
                        }
                    }

                    SectionDivider()

                    SectionText("Secondary action")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "You can click before holding to perform a secondary action with your voice.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PressPatternTile(
                            label = "Disabled",
                            pattern = emptyList(),
                            selected = secondaryMode == SecondaryMode.Disabled,
                            onClick = { viewModel.setSecondaryMode(SecondaryMode.Disabled) },
                        )
                        PressPatternTile(
                            label = "Search",
                            pattern = listOf(Press.Short, Press.HoldAndSpeak),
                            selected = secondaryMode == SecondaryMode.Search,
                            onClick = { viewModel.setSecondaryMode(SecondaryMode.Search) },
                        )
                    }

                    SectionDivider()

                    Text(
                        "You can configure more in Settings later.",
                        textAlign = TextAlign.Center,
                    )

                    SectionDivider()

                    PebbleElevatedButton(
                        text = "Finished",
                        onClick = { coreNav.goBack() },
                        primaryColor = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.PressPatternTile(
    label: String,
    pattern: List<Press>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.surface,
            contentColor = contentColor,
        ),
        border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier.height(28.dp).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (pattern.isNotEmpty()) {
                    PressPatternDot(
                        pattern = pattern,
                        activeColor = contentColor,
                        idleColor = contentColor.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}
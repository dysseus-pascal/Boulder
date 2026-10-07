package coredevices.ring.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coredevices.indexai.data.entity.mcp_sandbox.SandboxModelType
import coredevices.libindex.device.IndexDeviceManager
import coredevices.libindex.device.InterviewedIndexDevice
import coredevices.libindex.device.KnownIndexDevice
import coredevices.libindex.device.RSSIMeasurement
import coredevices.libindex.di.LibIndexCoroutineScope
import coredevices.ring.RingDelegate
import coredevices.ring.agent.IndexActionsRepository
import coredevices.ring.agent.LlmMode
import coredevices.ring.agent.builtin_servlets.notes.NoteIntegrationFactory
import coredevices.ring.agent.builtin_servlets.notes.NoteProvider
import coredevices.ring.agent.builtin_servlets.reminders.ReminderProvider
import coredevices.ring.agent.integrations.GTasksIntegration
import coredevices.ring.data.NoteShortcutType
import coredevices.ring.database.Preferences
import coredevices.ring.database.room.repository.McpSandboxRepository
import coredevices.ring.model.CactusModelProvider
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.service.button.RingGesture
import coredevices.ui.ModelType
import coredevices.util.CommonBuildKonfig
import coredevices.util.Platform
import coredevices.util.emailOrNull
import coredevices.util.isAndroid
import coredevices.util.isIOS
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

sealed class DiagnosticsState {
    object Idle : DiagnosticsState()
    object Running : DiagnosticsState()
    data class Error(val message: String) : DiagnosticsState()
    data class Completed(val measurement: RSSIMeasurement) : DiagnosticsState()
}

private const val DIAGNOSTICS_ATTEMPTS = 3
private val DIAGNOSTICS_RETRY_DELAY = 1.seconds
private val DIAGNOSTICS_CONNECTION_TIMEOUT = 10.seconds

class SettingsViewModel(
    private val preferences: Preferences,
    private val noteIntegrationFactory: NoteIntegrationFactory,
    private val gTasksIntegration: GTasksIntegration,
    private val indexDeviceManager: IndexDeviceManager,
    private val platform: Platform,
    private val mcpSandboxRepository: McpSandboxRepository,
    private val ringDelegate: RingDelegate,
    private val cactusModelProvider: CactusModelProvider,
    private val appScope: LibIndexCoroutineScope,
    private val gestureRouting: GestureRoutingPreferences,
    private val indexActionsRepository: IndexActionsRepository,
): ViewModel() {
    val version = CommonBuildKonfig.GIT_HASH
    val username = Firebase.auth.authStateChanged
        .map { it?.emailOrNull }
        .stateIn(viewModelScope, SharingStarted.Lazily, Firebase.auth.currentUser?.email)
    val llmMode = preferences.llmMode

    /** The on-device agent can't drive a sandbox group's model, so the local LLM modes are
     *  only offered while the default group runs the Index Agent. */
    val localLlmSupported = mcpSandboxRepository.getDefaultGroupFlow()
        .map { it?.modelType == SandboxModelType.IndexAgent }
        .stateIn(
            viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = preferences.llmMode.value.usesLocalCactus()
        )
    private val _showModelDownloadDialog = MutableStateFlow<ModelType?>(null)
    val showModelDownloadDialog = _showModelDownloadDialog.asStateFlow()
    val gestureRoutes = gestureRouting.routes
    val debugDetailsEnabled = preferences.debugDetailsEnabled
    private val _showContactsDialog = MutableStateFlow(false)
    val showContactsDialog = _showContactsDialog.asStateFlow()
    val sandboxGroups = mcpSandboxRepository.getAllGroupsFlow().stateIn(
        viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    // Eager so the list is loaded before the section is lazily scrolled into view.
    val indexActions = indexActionsRepository.actions.stateIn(
        viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )
    val httpMcpDisabledReason = indexActionsRepository.httpMcpDisabledReason.stateIn(
        viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )
    private val _showNoteShortcutDialog = MutableStateFlow(false)
    val showNoteShortcutDialog = _showNoteShortcutDialog.asStateFlow()
    val noteShortcut = preferences.noteShortcut
    val autoDismissActionNotifications = preferences.autoDismissActionNotifications
    private val currentRing = indexDeviceManager.rings.map {
        it.firstOrNull { ring -> ring is KnownIndexDevice }
    }
    val ringPaired = preferences.ringPaired
    val currentRingFirmware = currentRing
        .mapNotNull { (it as? InterviewedIndexDevice)?.firmwareVersion }
        .stateIn(
            viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = null
        )

    val currentRingName = currentRing
        .map { it?.name }
        .stateIn(
            viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = null
        )

    private val providerRefresh = MutableStateFlow(0)
    val availableNoteProviders = providerRefresh
        .map { authorizedNoteProviders() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val availableReminderProviders = providerRefresh
        .map { authorizedReminderProviders() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _diagnosticsState = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)
    val diagnosticsState = _diagnosticsState.asStateFlow()

    fun setActionEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { indexActionsRepository.setActionEnabled(name, enabled) }
    }

    fun togglePendingIntentScanEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.setUsePendingIntentScan(!preferences.usePendingIntentScan.value)
        }
    }

    val pendingIntentScanEnabled = preferences.usePendingIntentScan

    fun toggleScanByPairedAddress() {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.setScanByPairedAddress(!preferences.scanByPairedAddress.value)
        }
    }

    val scanByPairedAddress = preferences.scanByPairedAddress

    fun beginDiagnostics(): Job {
        _diagnosticsState.value = DiagnosticsState.Running
        return viewModelScope.launch {
            val ring = indexDeviceManager.rings.value.filterIsInstance<KnownIndexDevice>().firstOrNull()
                ?: run {
                    Logger.withTag("RingDiagnostics").w { "No paired ring found for diagnostics" }
                    _diagnosticsState.value = DiagnosticsState.Idle
                    return@launch
                }

            var lastError: Exception? = null
            repeat(DIAGNOSTICS_ATTEMPTS) { attempt ->
                try {
                    val result = ring.measureRSSI(DIAGNOSTICS_CONNECTION_TIMEOUT)
                    Logger.withTag("RingDiagnostics").i { "RSSI diagnostic result: $result" }
                    _diagnosticsState.value = DiagnosticsState.Completed(result)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Logger.withTag("RingDiagnostics").w(e) {
                        "RSSI measurement failed (attempt ${attempt + 1}/$DIAGNOSTICS_ATTEMPTS)"
                    }
                    if (attempt < DIAGNOSTICS_ATTEMPTS - 1) delay(DIAGNOSTICS_RETRY_DELAY)
                }
            }
            _diagnosticsState.value = DiagnosticsState.Error(
                lastError?.message ?: "Couldn't reach your Index 01"
            )
        }.apply {
            invokeOnCompletion {
                if (it is CancellationException) {
                    _diagnosticsState.value = DiagnosticsState.Idle
                }
            }
        }
    }

    fun resetDiagnostics() {
        _diagnosticsState.value = DiagnosticsState.Idle
    }

    fun refreshAvailableProviders() {
        providerRefresh.value++
    }

    private suspend fun authorizedNoteProviders(): List<NoteProvider> =
        NoteProvider.entries.filter { noteIntegrationFactory.createNoteClient(it).isAuthorized() }

    private suspend fun authorizedReminderProviders(): List<ReminderProvider> =
        buildList {
            add(ReminderProvider.BuiltIn)
            if (platform.isIOS) {
                add(ReminderProvider.IOSReminders)
            }
            if (gTasksIntegration.isAuthorized()) {
                add(ReminderProvider.GoogleTasks)
            }
            // Tasker shares one opt-in across notes & reminders; reuse the note client's auth check.
            if (platform.isAndroid &&
                noteIntegrationFactory.createNoteClient(NoteProvider.Tasker).isAuthorized()
            ) {
                add(ReminderProvider.Tasker)
            }
        }

    fun onModelDownloadDialogDismissed(success: Boolean) {
        val wasDownloading = _showModelDownloadDialog.value ?: return
        _showModelDownloadDialog.value = null
        // appScope: pref write must land even if the user leaves Settings.
        appScope.launch {
            when (wasDownloading) {
                is ModelType.Agent -> if (!success) preferences.setLlmMode(LlmMode.RemoteOnly)
                is ModelType.STT -> preferences.setUseCactusTranscription(success)
            }
        }
    }

    fun setLlmMode(mode: LlmMode) {
        appScope.launch {
            if (mode.usesLocalCactus()) {
                // Trigger LM model extraction, should be already integrated into assets so no DL
                cactusModelProvider.getLMModelPath()
            }
            preferences.setLlmMode(mode)
        }
    }

    fun setGestureRoute(gesture: RingGesture, destination: GestureDestination) {
        gestureRouting.setRoute(gesture, destination)
    }

    fun setGestureRoutes(routes: Map<RingGesture, GestureDestination>) {
        gestureRouting.setRoutes(routes)
    }

    fun toggleDebugDetailsEnabled() {
        viewModelScope.launch {
            val newValue = !debugDetailsEnabled.value
            preferences.setDebugDetailsEnabled(newValue)
        }
    }

    fun toggleAutoDismissActionNotifications() {
        preferences.setAutoDismissActionNotifications(!autoDismissActionNotifications.value)
    }

    fun showNoteShortcutDialog() {
        refreshAvailableProviders()
        _showNoteShortcutDialog.value = true
    }

    fun closeNoteShortcutDialog() {
        _showNoteShortcutDialog.value = false
    }

    fun setNoteShortcut(shortcut: NoteShortcutType) {
        preferences.setNoteShortcut(shortcut)
    }

    fun showContactsDialog() {
        _showContactsDialog.value = true
    }

    fun closeContactsDialog() {
        _showContactsDialog.value = false
    }

    fun restartPreemptiveTransfer() {
        ringDelegate.restartPreemptiveTransfer()
    }
}

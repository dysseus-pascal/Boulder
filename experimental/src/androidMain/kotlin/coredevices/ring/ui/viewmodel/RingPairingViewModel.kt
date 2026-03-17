package coredevices.ring.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coredevices.analytics.CoreAnalytics
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.ring.database.Preferences
import coredevices.ring.database.room.repository.RingTransferRepository
import coredevices.ring.service.RingBackgroundManager
import coredevices.ring.service.RingEvent
import coredevices.ring.service.RingPairing
import coredevices.ring.service.RingPairingException
import coredevices.ring.service.RingSync
import coredevices.ring.util.CompanionRegisterResult
import coredevices.ring.util.RingCompanionDeviceManager
import coredevices.util.BondingEvent
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.seconds

class RingPairingViewModel(private val onShowSnackbar: suspend (String) -> Unit, private val bondingEvents: Flow<BondingEvent>): ViewModel(),
    KoinComponent {
    companion object {
        private val logger = Logger.withTag(RingPairingViewModel::class.simpleName!!)
    }

    private val companionDeviceManager: RingCompanionDeviceManager
        by inject { parametersOf(viewModelScope) }
    private val satelliteManager: KMPHaversineSatelliteManager by inject()

    val associations = companionDeviceManager.associations
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState
    private val indexStorage: CollectionIndexStorage by inject()
    private val ringSync: RingSync by inject()
    private val preferences: Preferences by inject()
    private val backgroundManager: RingBackgroundManager by inject()
    private val coreAnalytics: CoreAnalytics by inject()
    private val longRunningScope = CoroutineScope(Dispatchers.Default)
    private val ringPairing: RingPairing by inject()

    val updateStatus = ringSync.ringEvents.filterIsInstance<RingEvent.FirmwareUpdate>().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5.seconds),
        initialValue = null
    )

    private fun logPairedEvent() {
        coreAnalytics.logEvent("ring.pair_success")
    }

    private fun logPairFailedEvent(reason: String) {
        coreAnalytics.logEvent("ring.pair_failed", mapOf("reason" to reason))
    }

    sealed class PairingState {
        data object Idle : PairingState()
        data class Pairing(val id: String, val extraDetail: String? = null) : PairingState()
    }

    private fun targetRing(id: String) {
        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            logger.e { "No user ID found, cannot program satellite" }
            viewModelScope.launch {
                onShowSnackbar("Error: Log in to pair with ring")
            }
            return
        }
        val pressAgainInfoJob = viewModelScope.launch {
            delay(6.seconds)
            _pairingState.value = PairingState.Pairing(id, "Try pressing the ring again to pair")
        }
        longRunningScope.launch(Dispatchers.Main) { // Outlive the ViewModel scope in case user navigates away, we can get in weird state
            try {
                ringPairing.pairRing(id)
            } catch (e: RingPairingException.NoUserId) {
                logger.e(e) { "No user ID found, cannot program satellite" }
                onShowSnackbar("Error: Log in to pair with ring")
                return@launch
            } catch (e: RingPairingException.ProgrammingFailed) {
                logger.e(e) { "Failed to program satellite $id with user ID" }
                onShowSnackbar("Failed to pair with ring, please try again")
                logPairFailedEvent("programming_failed")
                return@launch
            } catch (e: Exception) {
                logger.e(e) { "Unexpected error during pairing" }
                onShowSnackbar("Failed to pair with ring, please report a bug")
                return@launch
            } finally {
                pressAgainInfoJob.cancel()
                _pairingState.value = PairingState.Idle
            }
            logPairedEvent()
            onShowSnackbar("Successfully paired with ring")
        }
    }

    fun openPicker() {
        viewModelScope.launch {
            val result = companionDeviceManager.openPairingPicker()
            logger.d { "Pairing picker result: $result" }
            when (result) {
                is CompanionRegisterResult.Success -> {
                    _pairingState.value = PairingState.Pairing(result.id ?: "")
                    val bond = withTimeoutOrNull(60000) {
                        bondingEvents.first {
                            val bondedId = it.deviceId?.replace(":", "")?.uppercase()
                            it is BondingEvent.Error ||
                                    (it is BondingEvent.Bonded && bondedId == result.id!!)
                        }
                    } ?: run {
                        logger.e { "Pairing timed out" }
                        onShowSnackbar("Pairing timed out. Please try again.")
                        _pairingState.value = PairingState.Idle
                        return@launch
                    }
                    logger.d { "Bonding event received: $bond" }
                    when (bond) {
                        is BondingEvent.Error -> {
                            logger.e { "Bonding error" }
                            onShowSnackbar("Error bonding")
                            logPairFailedEvent("bonding_error")
                            _pairingState.value = PairingState.Idle
                            return@launch
                        }
                        is BondingEvent.Bonded -> {
                            logger.d { "Successfully bonded with ring: ${bond.deviceId}" }
                            preferences.setRingPaired(result.id!!)
                            delay(1.seconds) // Scanning again doesn't work immediately after bonding
                            backgroundManager.startBackgroundIfEnabled()
                            targetRing(result.id)
                        }
                        is BondingEvent.Unbonded -> {
                            logger.d { "Ring unbonded: ${bond.deviceId}" }
                            onShowSnackbar("Ring unbonded: ${bond.deviceId}")
                            _pairingState.value = PairingState.Idle
                            return@launch
                        }
                    }
                }
                is CompanionRegisterResult.Failure -> {
                    logger.e { "Pairing failed: ${result.error}" }
                    onShowSnackbar("Error: ${result.error}")
                    logPairFailedEvent("companion_picker_failed")
                    _pairingState.value = PairingState.Idle
                }
            }
        }
    }
}
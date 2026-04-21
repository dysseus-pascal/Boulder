package coredevices.libindex.database

import kotlinx.coroutines.flow.StateFlow

interface BasePreferences {
    fun setRingPaired(id: String?)
    val ringPaired: StateFlow<String?>

    suspend fun setLastSyncIndex(index: Int?)
    val lastSyncIndex: StateFlow<Int?>
}
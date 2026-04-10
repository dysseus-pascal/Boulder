package coredevices.ring.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class SettingsBeeperContactsDialogViewModel actual constructor() : ViewModel() {
    actual fun getContacts(query: String?): PagingSource<Int, SettingsBeeperContact> {
        throw NotImplementedError()
    }

    actual val approvedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    actual fun toggleContact(contactId: String) {
    }

    actual fun persist() {
    }
}

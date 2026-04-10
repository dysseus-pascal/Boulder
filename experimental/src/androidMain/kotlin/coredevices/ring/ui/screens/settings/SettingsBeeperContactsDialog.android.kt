package coredevices.ring.ui.screens.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingSource
import androidx.paging.PagingState
import coredevices.ring.agent.builtin_servlets.messaging.BeeperAPI
import coredevices.ring.database.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual class SettingsBeeperContactsDialogViewModel actual constructor() : ViewModel(), KoinComponent {
    private val context: Context by inject()
    private val contentResolver: ContentResolver by lazy { context.contentResolver }
    private val prefs: Preferences by inject()

    private val _approvedIds = MutableStateFlow(
        prefs.approvedBeeperContacts.value.toHashSet()
    )
    actual val approvedIds: StateFlow<Set<String>> = _approvedIds.asStateFlow()

    actual fun getContacts(query: String?): PagingSource<Int, SettingsBeeperContact> {
        return BeeperContactsPagingSource(contentResolver, query, _approvedIds.value)
    }

    actual fun toggleContact(contactId: String) {
        val updated = HashSet(_approvedIds.value)
        if (!updated.remove(contactId)) {
            updated.add(contactId)
        }
        _approvedIds.value = updated
    }

    actual fun persist() {
        viewModelScope.launch {
            prefs.setApprovedBeeperContacts(_approvedIds.value.toList())
        }
    }
}

class BeeperContactsPagingSource(
    private val contentResolver: ContentResolver,
    private val query: String?,
    private val approvedIds: Set<String>
) : PagingSource<Int, SettingsBeeperContact>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SettingsBeeperContact> {
        val offset = params.key ?: 0
        val limit = params.loadSize

        return try {
            val contacts = withContext(Dispatchers.IO) {
                fetchContactsWithTimestamps(limit, offset)
            }

            // Deduplicate by id, keeping the one with the most recent timestamp
            val deduped = contacts
                .groupBy { it.id }
                .map { (_, dupes) -> dupes.maxByOrNull { it.timestamp } ?: dupes.first() }

            // Sort: approved first, then by recency within each group
            val sorted = deduped.sortedWith(
                compareByDescending<ContactWithTimestamp> { approvedIds.contains(it.id) }
                    .thenByDescending { it.timestamp }
            )

            val result = sorted.map {
                SettingsBeeperContact(id = it.id, name = it.name, protocol = it.protocol)
            }

            LoadResult.Page(
                data = result,
                prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                nextKey = if (contacts.isEmpty()) null else offset + contacts.size
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private data class ContactWithTimestamp(
        val id: String,
        val name: String,
        val protocol: String,
        val timestamp: Long
    )

    private fun fetchContactsWithTimestamps(limit: Int, offset: Int): List<ContactWithTimestamp> {
        val uriBuilder = BeeperAPI.CONTACTS_URI.toUri().buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .appendQueryParameter("offset", offset.toString())

        if (!query.isNullOrBlank()) {
            uriBuilder.appendQueryParameter("query", Uri.encode(query))
        }

        val uri = uriBuilder.build()
        val proj = arrayOf("id", "displayName", "protocol", "roomIds")
        val cursor = contentResolver.query(uri, proj, null, null, null)
        val contacts = mutableListOf<ContactWithTimestamp>()

        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val nameIdx = c.getColumnIndexOrThrow("displayName")
            val protocolIdx = c.getColumnIndexOrThrow("protocol")
            val roomIdsIdx = c.getColumnIndex("roomIds")

            while (c.moveToNext()) {
                val id = c.getString(idIdx)
                val name = c.getString(nameIdx)
                val protocol = c.getString(protocolIdx)
                val roomIds = if (roomIdsIdx >= 0) c.getStringOrNull(roomIdsIdx) else null

                val timestamp = if (!roomIds.isNullOrBlank()) {
                    getLatestTimestamp(roomIds)
                } else {
                    0L
                }

                contacts.add(ContactWithTimestamp(id = id, name = name, protocol = protocol, timestamp = timestamp))
            }
        }

        return contacts
    }

    private fun getLatestTimestamp(roomIds: String): Long {
        val chatsUri = BeeperAPI.CHATS_URI.toUri().buildUpon()
            .appendQueryParameter("roomIds", roomIds)
            .build()

        var latest = 0L
        contentResolver.query(
            chatsUri,
            arrayOf("timestamp"),
            null,
            null,
            "timestamp DESC"
        )?.use { c ->
            val tsIdx = c.getColumnIndexOrThrow("timestamp")
            if (c.moveToFirst()) {
                latest = c.getLong(tsIdx)
            }
        }
        return latest
    }

    override fun getRefreshKey(state: PagingState<Int, SettingsBeeperContact>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(state.config.pageSize)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(state.config.pageSize)
        }
    }
}

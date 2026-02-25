package coredevices.firestore

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

interface UsersDao {
    val user: Flow<User?>
    suspend fun updateNotionToken(
        notionToken: String?
    )
    suspend fun updateMcpRunToken(
        mcpRunToken: String?
    )
    suspend fun updateTodoBlockId(
        todoBlockId: String
    )
    suspend fun initUserTokens(rebbleUserToken: String?)
    fun init()
}

class UsersDaoImpl(db: FirebaseFirestore): CollectionDao("users", db), UsersDao {
    private val userDoc get() = authenticatedId?.let { db.document(it) }
    private val logger = Logger.withTag("UsersDaoImpl")

    private val _user = MutableSharedFlow<User?>(replay = 1)
    override val user: Flow<User?> = _user.asSharedFlow()

    override fun init() {
        GlobalScope.launch {
            Firebase.auth.authStateChanged
                .onStart { emit(Firebase.auth.currentUser) }
                .flatMapLatest { user ->
                    logger.v { "User changed: $user" }
                    if (user == null) {
                        _user.emit(null)
                        flowOf(null)
                    } else {
                        // 2. Use flatMapLatest so the inner snapshot
                        // is cancelled if the user logs out
                        db.document("users/${user.uid}")
                            .snapshots
                            .catch { e -> logger.w(e) { "Error observing user doc" } }
                    }
                }
                .collect { snapshot ->
                    logger.d { "User changed.." }
                    _user.emit(snapshot?.data<User?>())
                }
        }
    }

    override suspend fun updateNotionToken(
        notionToken: String?
    ) {
        userDoc?.update(mapOf("notion_token" to notionToken))
    }

    override suspend fun updateMcpRunToken(
        mcpRunToken: String?
    ) {
        userDoc?.update(mapOf("mcp_run_token" to mcpRunToken))
    }

    override suspend fun updateTodoBlockId(
        todoBlockId: String
    ) {
        userDoc?.update(mapOf("todo_block_id" to todoBlockId))
    }

    override suspend fun initUserTokens(rebbleUserToken: String?) {
        val user = user.first()
        if (user == null) {
            logger.w { "initUserTokens: user is null" }
            return
        }
        if (rebbleUserToken != null && user.rebbleUserToken != rebbleUserToken) {
            userDoc?.update(mapOf("rebble_user_token" to rebbleUserToken))
        }
        if (user.pebbleUserToken == null) {
            userDoc?.update(mapOf("pebble_user_token" to generateRandomUserToken()))
        }
    }
}

fun generateRandomUserToken(): String {
    val charPool = "0123456789abcdef"
    return (1..24)
        .map { kotlin.random.Random.nextInt(0, charPool.length) }
        .map(charPool::get)
        .joinToString("")
}

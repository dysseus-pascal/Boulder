package coredevices.firestore

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface UsersDao {
    suspend fun ensureUserDocumentCreated()
    suspend fun getUser(): User?
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
    fun userFlow(): Flow<DocumentSnapshot>
}

class UsersDaoImpl(db: FirebaseFirestore): CollectionDao("users", db), UsersDao {
    private val userDoc get() = authenticatedId?.let { db.document(it) }
    private val logger = Logger.withTag("UsersDaoImpl")

    override suspend fun ensureUserDocumentCreated() {
        if (userDoc?.get()?.exists == false) {
            userDoc?.set(User())
        }
    }

    override suspend fun getUser(): User? {
        val doc = userDoc?.get()
        return if (doc?.exists == true) {
            doc.data<User>()
        } else {
            null
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
        val user = getUser()
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

    override fun userFlow() = userDoc?.snapshots ?: flow {  }
}

fun generateRandomUserToken(): String {
    val charPool = "0123456789abcdef"
    return (1..24)
        .map { kotlin.random.Random.nextInt(0, charPool.length) }
        .map(charPool::get)
        .joinToString("")
}

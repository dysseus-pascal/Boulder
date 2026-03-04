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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
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
    suspend fun initUserDevToken(rebbleUserToken: String?)
    fun init()
}

class UsersDaoImpl(db: FirebaseFirestore): CollectionDao("users", db), UsersDao {
    private val userDoc get() = authenticatedId?.let { db.document(it) }
    private val logger = Logger.withTag("UsersDaoImpl")

    private val _user = MutableSharedFlow<User?>(replay = 1)
    override val user: Flow<User?> = _user.asSharedFlow()

    override fun init() {
        GlobalScope.launch {
            if (Firebase.auth.currentUser == null) {
                logger.i { "Logging into firebase anonymously" }
                try {
                    Firebase.auth.signInAnonymously()
                } catch (e: Exception) {
                    logger.e(e) { "Failed to sign in anonymously" }
                }
            }
            Firebase.auth.authStateChanged
                .onStart { emit(Firebase.auth.currentUser) }
                .flatMapLatest { firebaseUser ->
                    logger.v { "User changed: $firebaseUser" }
                    if (firebaseUser == null) {
                        _user.emit(null)
                        flowOf(null)
                    } else {
                        val docRef = db.document("users/${firebaseUser.uid}")
                        docRef.snapshots
                            .onEach { snapshot ->
                                try {
                                    if (!snapshot.exists) {
                                        docRef.set(User(pebbleUserToken = generateRandomUserToken()))
                                    } else if (snapshot.data<User?>()?.pebbleUserToken == null) {
                                        docRef.update(mapOf("pebble_user_token" to generateRandomUserToken()))
                                    }
                                } catch (e: Exception) {
                                    logger.w(e) { "Error initializing user document" }
                                }
                            }
                            .filter { it.exists }
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

    override suspend fun initUserDevToken(rebbleUserToken: String?) {
        if (rebbleUserToken == null) return
        val user = user.first()
        if (user == null) {
            logger.w { "initUserDevToken: user is null" }
            return
        }
        if (user.rebbleUserToken != rebbleUserToken) {
            userDoc?.update(mapOf("rebble_user_token" to rebbleUserToken))
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

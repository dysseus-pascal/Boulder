package coredevices.ring.api

import coredevices.api.ApiClient
import coredevices.indexai.data.oauth.OAuthTokenResponse
import coredevices.indexai.data.oauth.OAuthURLResponse
import coredevices.util.integrations.OAuthProxyApi
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

@Serializable
data class TickTickTask(
    val id: String? = null,
    val projectId: String? = null,
    val title: String? = null,
)

@Serializable
data class TickTickProject(
    val id: String? = null,
    val name: String? = null,
    val closed: Boolean? = null,
)

/**
 * TickTick Open API. OAuth goes through the index-oauth backend proxy (same shape as Notion):
 * TickTick has no PKCE and requires the client secret at token exchange, so the secret lives
 * server-side and the app redeems a backend-minted code with a PKCE verifier instead.
 */
class TickTickApi(config: ApiConfig) : ApiClient(config.version), OAuthProxyApi {
    private val backendBaseUrl = config.tickTickOAuthBackendUrl
    private val baseUrl = "https://api.ticktick.com/open/v1"

    override suspend fun getAuthorizationUrl(challenge: String): String {
        val res = client.get("$backendBaseUrl/auth/start") {
            firebaseAuth()
            parameter("code_challenge", challenge)
            parameter("code_challenge_method", "S256")
        }
        if (!res.status.isSuccess()) {
            error("Failed to get TickTick OAuth link: ${res.status}")
        }
        val response = try {
            res.body<OAuthURLResponse>()
        } catch (e: Exception) {
            null
        }
        return response?.url ?: error("No URL in response")
    }

    override suspend fun exchangeCodeForToken(code: String, verifier: String): String {
        val res = client.post("$backendBaseUrl/auth/exchange") {
            firebaseAuth()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("code", code)
                put("code_verifier", verifier)
            })
        }
        val response = try {
            res.body<OAuthTokenResponse>()
        } catch (e: Exception) {
            null
        }
        if (!res.status.isSuccess()) {
            error("Failed to exchange code for token: ${res.status} message: ${response?.error ?: res.bodyAsText()}")
        }
        return response?.accessToken ?: error("No access token in response")
    }

    // TickTick has no refresh grant and the proxy is stateless, so its /token route (unlike
    // Notion's /auth/token) has nothing to return — this is effectively unsupported and the
    // app never calls it; the long-lived token from sign-in is used until it expires.
    override suspend fun refreshToken(): String {
        val res = client.post("$backendBaseUrl/token") {
            firebaseAuth()
        }
        if (!res.status.isSuccess()) {
            error("Failed to fetch TickTick token: ${res.status}")
        }
        return res.body<OAuthTokenResponse>().accessToken ?: error("No access token in response")
    }

    override suspend fun revokeToken() {
        val res = client.post("$backendBaseUrl/revoke") {
            firebaseAuth()
        }
        if (!res.status.isSuccess()) {
            error("Failed to revoke TickTick token: ${res.status}")
        }
    }

    /** [projectId] null puts the task in the TickTick Inbox. */
    suspend fun createTask(
        token: String,
        title: String,
        dueDate: Instant?,
        timeZone: TimeZone,
        projectId: String?,
        reminders: List<String>,
    ): TickTickTask {
        // Fields are omitted (not null) when absent: TickTick treats a missing projectId as
        // "create in Inbox", and the shared client's encodeDefaults would otherwise send nulls.
        val res = client.post("$baseUrl/task") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("title", title)
                projectId?.let { put("projectId", it) }
                dueDate?.let {
                    put("dueDate", it.toTickTickDate())
                    put("timeZone", timeZone.id)
                    put("isAllDay", false)
                }
                if (reminders.isNotEmpty()) {
                    put("reminders", JsonArray(reminders.map { JsonPrimitive(it) }))
                }
            })
        }
        if (!res.status.isSuccess()) {
            error("Failed to create TickTick task: ${res.status}")
        }
        return res.body()
    }

    suspend fun getProjects(token: String): List<TickTickProject> {
        val res = client.get("$baseUrl/project") {
            bearerAuth(token)
        }
        if (!res.status.isSuccess()) {
            error("Failed to fetch TickTick projects: ${res.status}")
        }
        return res.body()
    }

}

/** TickTick expects "yyyy-MM-dd'T'HH:mm:ssZ", e.g. "2019-11-13T03:00:00+0000". */
internal fun Instant.toTickTickDate(): String {
    val dt = toLocalDateTime(TimeZone.UTC)
    fun Int.pad(length: Int = 2) = toString().padStart(length, '0')
    return "${dt.year.pad(4)}-${dt.monthNumber.pad()}-${dt.dayOfMonth.pad()}" +
        "T${dt.hour.pad()}:${dt.minute.pad()}:${dt.second.pad()}+0000"
}

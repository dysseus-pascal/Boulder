package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.util.encodeBase64
import io.rebble.libpebblecommon.util.toNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import platform.Foundation.HTTPMethod
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNull
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.addValue
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.JavaScriptCore.JSContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val UNSENT = 0
private const val OPENED = 1
private const val HEADERS = 2
private const val LOADING = 3
private const val DONE = 4

internal data class Response(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray
)

internal suspend fun request(
    method: HttpMethod,
    url: String,
    user: String? = null,
    password: String? = null,
    data: ByteArray? = null,
    headers: Map<String, Any> = emptyMap()
): Response = suspendCancellableCoroutine { cont ->
    val request = NSMutableURLRequest(NSURL.URLWithString(url)!!)
    request.HTTPMethod = method.value.uppercase()
    headers.forEach {
        request.addValue(it.value.toString(), forHTTPHeaderField = it.key)
    }
    if (user != null && password != null) {
        request.addValue("Basic ${"$user:$password".encodeBase64()}", forHTTPHeaderField = "Authorization")
    }
    data?.let { request.setHTTPBody(it.toNative()) }

    val session = NSURLSession.sharedSession
    val task = session.dataTaskWithRequest(request) { data, response, error ->
        Logger.d("request") { "Response received" }
        if (error != null) {
            cont.resumeWithException(Exception("Network request failed: ${error.localizedDescription}"))
            return@dataTaskWithRequest
        }
        if (response == null || data == null) {
            cont.resumeWithException(Exception("No response or data received"))
            return@dataTaskWithRequest
        }
        checkNotNull(response as? NSHTTPURLResponse) { "Invalid response type" }

        val status = response.statusCode.toInt()
        val headersMap = response.allHeaderFields.map {
            it.key.toString() to it.value.toString()
        }.toMap()
        val responseData = data.toByteString()
        cont.resume(Response(
            status = status,
            headers = headersMap,
            body = responseData.toByteArray()
        ))
    }
    task.resume()
    cont.invokeOnCancellation {
        task.cancel()
    }
}

class XMLHTTPRequestManager(private val scope: CoroutineScope, private val jsContext: JSContext): RegisterableJsInterface {
    private var lastInstance = 0
    private val instances = mutableMapOf<Int, XHRInstance>()
    private val client = HttpClient(Darwin)
    private val logger = Logger.withTag("XMLHTTPRequestManager")

    override fun register(jsContext: JSContext) {
        jsContext["_XMLHTTPRequestManager"] = mapOf(
            "getXHRInstanceID" to this::getXHRInstanceID,
            "open" to this::open,
            "setRequestHeader" to this::setRequestHeader,
            "send" to this::send,
            "abort" to this::abort,
        )
    }

    private fun getXHRInstanceID(): Int {
        val id = ++lastInstance
        instances[id] = XHRInstance(id)
        return id
    }

    // JSC uses double for numbers
    private fun open(instanceId: Double, method: String, url: String, async: Boolean?, user: String?, password: String?) {
        logger.d { "Instance $instanceId open()" }
        instances[instanceId.toInt()]?.open(method, url, async, user, password)
    }

    private fun setRequestHeader(instanceId: Double, header: String, value: Any) {
        instances[instanceId.toInt()]?.setRequestHeader(header, value)
    }

    private fun send(instanceId: Double, responseType: String?, data: Any?) {
        logger.d { "Instance $instanceId send()" }
        val bytes = when (data) {
            is ByteArray -> data
            is String -> data.encodeToByteArray()
            is NSNull, null -> null
            else -> {
                logger.e { "Invalid data type for send: ${data::class.simpleName}" }
                null
            }
        }
        instances[instanceId.toInt()]?.send(bytes, responseType)
    }

    private fun abort(instanceId: Double) {
        instances[instanceId.toInt()]?.abort()
    }

    inner class XHRInstance(val id: Int) {
        private var async: Boolean = true
        private var url: String? = null
        private var method: HttpMethod? = null
        private var user: String? = null
        private var password: String? = null
        private val headers = mutableMapOf<String, Any>()
        var requestJob: Job? = null

        private val jsInstance = "XMLHttpRequest._instances.get($id)"

        private fun changeReadyState(newState: Int) {
            jsContext.evalCatching("$jsInstance.readyState = $newState")
            dispatchEvent(XHREvent.ReadyStateChange)
        }

        private fun dispatchEvent(event: XHREvent) {
            val evt = "{\"type\": \"${event.toJsName()}\"}"
            jsContext.evalCatching("$jsInstance._dispatchEvent(${Json.encodeToString(event.toJsName())}, ${Json.encodeToString(evt)})")
        }

        fun open(method: String, url: String, async: Boolean?, user: String?, password: String?) {
            this.async = async ?: true
            this.url = url
            this.method = HttpMethod.parse(method)
            this.user = user
            this.password = password
            this.headers.clear()
            changeReadyState(OPENED)
        }

        fun setRequestHeader(header: String, value: Any) {
            headers[header] = value
        }

        private fun dispatchError() {
            changeReadyState(DONE)
            dispatchEvent(XHREvent.Error)
        }

        fun send(data: ByteArray?, responseType: String?) {
            suspend fun execute() {
                dispatchEvent(XHREvent.LoadStart)
                val response = try {
                    withContext(Dispatchers.IO) {
                        request(
                            method = this@XHRInstance.method!!,
                            url = this@XHRInstance.url!!,
                            user = user,
                            password = password,
                            data = data,
                            headers = this@XHRInstance.headers
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.e { "Request timed out: ${e.message}" }
                    changeReadyState(DONE)
                    dispatchEvent(XHREvent.Timeout)
                    return
                } catch (e: Throwable) {
                    logger.e(e) { "Request to $url (size ${data?.size}) failed: ${e.message}" }
                    dispatchError()
                    return
                }
                val responseHeaders = try {
                    Json.encodeToString(response.headers.mapKeys { it.key.lowercase() })
                } catch (e: Throwable) {
                    logger.e(e) { "Failed to serialize response headers: ${e.message}" }
                    dispatchError()
                    return
                }
                val body = try {
                    when (responseType) {
                        "arraybuffer" -> Json.encodeToString(response.body.encodeBase64())
                        "text", "", "json", null -> Json.encodeToString(response.body.decodeToString())
                        else -> {
                            logger.e { "Invalid response type: $responseType" }
                            "null"
                        }
                    }
                } catch (e: Throwable) {
                    logger.e(e) { "Failed to read response body (type $responseType): ${e.message}" }
                    dispatchError()
                    return
                }
                val status = Json.encodeToString(response.status)
                val statusText = Json.encodeToString(HttpStatusCode.fromValue(response.status).description)
                logger.v { "XHR Response: $status $statusText, Body: $body" }
                jsContext.evalCatching("$jsInstance._onResponseComplete($responseHeaders, $status, $statusText, $body)")
                changeReadyState(DONE)
                dispatchEvent(XHREvent.Load)
                dispatchEvent(XHREvent.LoadEnd)
            }
            if (async) {
                requestJob = scope.launch(Dispatchers.IO) {
                    execute()
                }
            } else {
                runBlocking(Dispatchers.IO) {
                    execute()
                }
            }
        }

        fun abort() {
            requestJob?.cancel("Aborted by JS")
            requestJob = null
            changeReadyState(DONE)
            dispatchEvent(XHREvent.Abort)
        }
    }

    override fun close() {
        client.close()
        instances.values.forEach { it.requestJob?.cancel("Closing") }
        instances.clear()
    }
}

enum class XHREvent {
    Abort,
    Error,
    Load,
    LoadEnd,
    LoadStart,
    Progress,
    ReadyStateChange,
    Timeout;
    companion object {
        fun fromString(event: String): XHREvent? {
            return entries.firstOrNull { it.name.equals(event, ignoreCase = true) }
        }
    }

    fun toJsName(): String {
        return name.lowercase()
    }
}

@Serializable
data class CompleteXHRResponse(
    val responseHeaders: Map<String, String>,
    val responseData: String,
    val status: Int,
    val contentType: String?
)
package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.util.flattenEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

private const val UNSENT = 0
private const val OPENED = 1
private const val HEADERS = 2
private const val LOADING = 3
private const val DONE = 4

class XMLHTTPRequestManager(private val scope: CoroutineScope, private val jsContext: JSContext): RegisterableJsInterface {
    private var lastInstance = 0
    private val instances = mutableMapOf<Int, XHRInstance>()
    private val client = HttpClient()
    private val logger = Logger.withTag("XMLHTTPRequestManager")

    override fun register(jsContext: JSContext) {
        jsContext["_XMLHTTPRequestManager"] = mapOf(
            "test" to this::test,
            "getXHRInstanceID" to this::getXHRInstanceID,
            "open" to this::open,
            "setRequestHeader" to this::setRequestHeader,
            "send" to this::send,
            "abort" to this::abort,
        )
    }

    private fun test(thiz: Any, func: JSValue) {
        Logger.d("Thiz: $thiz")
        Logger.d { "Test function called: ${func.isSymbol} ${func.toObject()}" }
        func.callWithArguments(listOf("hi"))
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
            null -> null
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

        private fun getJsInstance(): JSValue? {
            return jsContext.evaluateScript("XMLHttpRequest._instances.get($id)")
        }

        private fun changeReadyState(newState: Int) {
            val instance = getJsInstance()
            instance?.set("readyState", newState) ?: {
                logger.e { "XHR instance $id not found" }
            }
            dispatchEvent(XHREvent.ReadyStateChange)
        }

        private fun dispatchEvent(event: XHREvent, data: Map<String, Any> = emptyMap()) {
            val evt = mapOf(
                "type" to event.toJsName(),
            ) + data
            val instance = getJsInstance()
            instance?.invokeMethod("_dispatchEvent", listOf(event.toJsName(), evt)) ?: {
                logger.e { "XHR instance $id not found" }
            }
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

        fun send(data: ByteArray?, responseType: String?) {
            suspend fun execute() {
                dispatchEvent(XHREvent.LoadStart)
                val response = try {
                    client.request {
                        this.method = this@XHRInstance.method!!
                        this.url(this@XHRInstance.url!!)
                        if (user != null && password != null) {
                            basicAuth(user!!, password!!)
                        }
                        if (data != null) {
                            setBody(data)
                        }
                        headers.entries().forEach {
                            header(it.key, it.value)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.e { "Request timed out: ${e.message}" }
                    changeReadyState(DONE)
                    dispatchEvent(XHREvent.Timeout)
                    return
                } catch (e: Exception) {
                    logger.e(e) { "Request failed: ${e.message}" }
                    changeReadyState(DONE)
                    dispatchEvent(XHREvent.Error)
                    return
                }
                val responseHeaders = response.headers
                    .flattenEntries()
                    .toMap()
                    .mapKeys { it.key.lowercase() }
                val body = when (responseType) {
                    "arraybuffer" -> response.bodyAsBytes().toList()
                    "text", "", "json", null -> response.bodyAsText() // JSON is handled by JS side
                    else -> {
                        logger.e { "Invalid response type: $responseType" }
                        null
                    }
                }
                val status = response.status.value
                val instance = getJsInstance()
                instance?.invokeMethod("_onResponseComplete", listOf(responseHeaders, status, body, response.headers["Content-Type"]))
                    ?: {
                        logger.e { "XHR instance $id not found" }
                    }
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
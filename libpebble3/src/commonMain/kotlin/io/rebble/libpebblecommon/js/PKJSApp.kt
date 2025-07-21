package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.component.get
import org.koin.core.parameter.parameterArrayOf
import kotlin.uuid.Uuid

class PKJSApp(
    private val device: PebbleJSDevice,
    private val jsPath: Path,
    val appInfo: PbwAppInfo,
    val lockerEntry: LockerEntry,
): LibPebbleKoinComponent {
    companion object {
        private val logger = Logger.withTag(PKJSApp::class.simpleName!!)
    }
    val uuid: Uuid by lazy { Uuid.parse(appInfo.uuid) }
    private var jsRunner: JsRunner? = null
    private var runningScope: CoroutineScope? = null
    private val urlOpenRequests = Channel<String>(Channel.RENDEZVOUS)

    private val _logMessages = MutableSharedFlow<String>(10)
    val logMessages = _logMessages.asSharedFlow()

    private fun launchIncomingAppMessageHandler(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        device.inboundAppMessages.onEach {
            if (it.uuid != uuid) {
                logger.v { "Ignoring app message for different app: ${it.uuid} != $uuid" }
                return@onEach
            }
            withTimeoutOrNull(1000) {
                device.sendAppMessageResult(AppMessageResult.ACK(it.transactionId))
            }
            val dataString = it.data.toJSData(appInfo.appKeys)
            logger.d("Received app message: ${it.transactionId} $dataString")
            jsRunner?.signalNewAppMessageData(dataString)
        }.catch {
            logger.e(it) { "Error receiving app message: ${it.message}" }
        }.launchIn(scope)
    }

    private fun launchOutgoingAppMessageHandler(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        jsRunner?.outgoingAppMessages?.onEach { (tIDDeferred, data) ->
            logger.d { "Sending app message: $data" }
            val tID = device.transactionSequence.next()
            val appMessage = data.toAppMessageData(appInfo, tID)
            tIDDeferred.complete(tID)
            val result = device.sendAppMessage(appMessage)
            val resultData = buildJsonObject {
                put("transactionId", tID.toInt())
            }
            when (result) {
                is AppMessageResult.ACK -> {
                    jsRunner?.signalAppMessageAck(resultData.toString())
                }
                is AppMessageResult.NACK -> {
                    jsRunner?.signalAppMessageAck(resultData.toString())
                }
            }
        }?.catch {
            logger.e(it) { "Error sending app message" }
        }?.launchIn(scope) ?: error("JsRunner not initialized")
    }

    suspend fun requestConfigurationUrl(): String? {
        if (runningScope == null) {
            logger.e { "Cannot show configuration, PKJSApp is not running" }
            return null
        }
        val url = runningScope!!.async { urlOpenRequests.receive() }
        jsRunner?.signalShowConfiguration() ?: logger.e { "JsRunner not initialized, cannot show configuration" }
        return url.await()
    }

    private fun injectJsRunner(scope: CoroutineScope): JsRunner =
        get {
            parameterArrayOf(
                device,
                scope,
                appInfo,
                lockerEntry,
                jsPath,
                urlOpenRequests,
                _logMessages
            )
        }

    suspend fun start(connectionScope: CoroutineScope) {
        val scope = connectionScope + SupervisorJob() + CoroutineName("PKJSApp-$uuid")
        runningScope = scope
        jsRunner = injectJsRunner(scope)
        launchIncomingAppMessageHandler(device, scope)
        launchOutgoingAppMessageHandler(device, scope)
        jsRunner?.start() ?: error("JsRunner not initialized")
    }

    suspend fun stop() {
        jsRunner?.stop()
        runningScope?.cancel()
        jsRunner = null
    }

    fun triggerOnWebviewClosed(data: String) {
        runningScope?.launch {
            jsRunner?.signalWebviewClosed(data)
        }
    }
}

fun AppMessageDictionary.toJSData(appKeys: Map<String, Int>): String {
    val data = this.mapKeys {
        val id = it.key
        appKeys.entries.firstOrNull { it.value == id }?.key ?: id
    }
    return buildJsonObject {
        for ((key, value) in data) {
            when (value) {
                is String -> put(key.toString(), value)
                is Number -> put(key.toString(), value)
                is UByteArray -> {
                    val array = buildJsonArray {
                        for (byte in value) {
                            add(byte.toInt())
                        }
                    }
                    put(key.toString(), array)
                }
                is ByteArray -> {
                    val array = buildJsonArray {
                        for (byte in value) {
                            add(byte.toInt())
                        }
                    }
                    put(key.toString(), array)
                }
                is Boolean -> put(key.toString(), value)
                else -> error("Invalid JSON value, unsupported type ${value::class.simpleName}")
            }
        }
    }.toString()
}

private fun String.toAppMessageData(appInfo: PbwAppInfo, transactionId: Byte): AppMessageData {
    val jsonElement = Json.parseToJsonElement(this)
    val jsonObject = jsonElement.jsonObject
    val tuples = jsonObject.mapNotNull { objectEntry ->
        val key = objectEntry.key
        val keyId = appInfo.appKeys[key] ?: key.toIntOrNull() ?: return@mapNotNull null
        when (objectEntry.value) {
            is JsonArray -> {
                Pair(keyId, objectEntry.value.jsonArray.map { it.jsonPrimitive.long.toUByte() }.toUByteArray())
            }
            is JsonObject -> error("Invalid JSON value, JsonObject not supported")
            else -> {
                when {
                    objectEntry.value.jsonPrimitive.isString -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.content)
                    }
                    objectEntry.value.jsonPrimitive.intOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.long.toInt())
                    }
                    objectEntry.value.jsonPrimitive.longOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.long.toUInt())
                    }
                    objectEntry.value.jsonPrimitive.booleanOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.boolean)
                    }
                    else -> error("Invalid JSON value, unsupported primitive type")
                }
            }
        }
    }.toMap()
    return AppMessageData(
        transactionId = transactionId,
        uuid = Uuid.parse(appInfo.uuid),
        data = tuples
    )
}
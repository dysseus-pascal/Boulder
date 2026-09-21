package io.rebble.libpebblecommon.plugin

import io.rebble.libpebblecommon.js.JsEngineInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Injects `Pebble.oauth.{authorize, refresh}` into a plugin session and routes those calls to the
 * host's [PluginOAuthApi]. Present only when the plugin declares an `oauth` connector and the host
 * has an OAuth API wired.
 *
 * The promise plumbing mirrors the XHR bridge: JS calls `_PluginOAuthBridge.request(id, json)`, the
 * result is evaluated back in as `__pluginOAuthReply(id, payload)`. The connector slug is verified
 * against the manifest here, so a plugin can only reach a connector it declared.
 */
internal class PluginOAuthJsBridge(
    private val scope: CoroutineScope,
    private val eval: (String) -> Unit,
    private val pluginUuid: String,
    private val declaredSlugs: Set<String>,
    private val api: PluginOAuthApi,
) : JsEngineInterface {
    override val name = "_PluginOAuthBridge"
    override val methods = listOf("request")

    private val json = Json { ignoreUnknownKeys = true }
    private val bridgeJob = SupervisorJob(scope.coroutineContext[Job])
    private val bridgeScope = CoroutineScope(scope.coroutineContext + bridgeJob)

    override fun dispatch(method: String, args: List<Any?>): Any? {
        if (method != "request") return null
        val id = (args.getOrNull(0) as? Number)?.toInt() ?: return null
        val requestJson = args.getOrNull(1) as? String ?: return null
        bridgeScope.launch {
            val response = try {
                buildJsonObject { put("result", handle(requestJson)) }
            } catch (error: Exception) {
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("code", errorCode(error))
                        put("message", error.message ?: "OAuth operation failed")
                    })
                }
            }
            eval("globalThis.__pluginOAuthReply($id,$response)")
        }
        return null
    }

    private suspend fun handle(raw: String): JsonObject {
        val request = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: SerializationException) {
            throw IllegalArgumentException("malformed OAuth request", error)
        }
        val operation = request.string("operation")
            ?: throw IllegalArgumentException("operation is required")
        val slug = request.string("connectorSlug")
            ?: throw IllegalArgumentException("connectorSlug is required")
        if (slug !in declaredSlugs) {
            throw PluginNativeException(
                PluginErrors.PERMISSION_DENIED,
                "this plugin did not declare an OAuth connector '$slug'",
            )
        }
        return when (operation) {
            "authorize" -> api.authorize(pluginUuid, slug)
            "refresh" -> {
                val brokerRefresh = request.string("brokerRefresh")
                    ?: throw IllegalArgumentException("brokerRefresh is required")
                api.refresh(pluginUuid, slug, brokerRefresh)
            }
            else -> throw IllegalArgumentException("unknown OAuth operation '$operation'")
        }
    }

    private fun JsonObject.string(name: String) =
        get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun errorCode(error: Exception) = when (error) {
        is PluginNativeException -> error.code
        is IllegalArgumentException, is SerializationException -> PluginErrors.INVALID_ARGS
        else -> PluginErrors.UNKNOWN
    }

    fun close() {
        bridgeScope.cancel()
    }

    companion object {
        const val INSTALL_JS = """
(function (global) {
  var nextId = 1;
  var pending = Object.create(null);
  global.__pluginOAuthReply = function (id, payload) {
    var callbacks = pending[id];
    if (!callbacks) return;
    delete pending[id];
    if (payload && payload.error) {
      var error = new Error(payload.error.message || 'OAuth operation failed');
      error.code = payload.error.code;
      callbacks.reject(error);
    } else {
      callbacks.resolve(payload ? payload.result : undefined);
    }
  };
  function call(operation, args) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      _PluginOAuthBridge.request(id, JSON.stringify(Object.assign({ operation: operation }, args)));
    });
  }
  Pebble.oauth = Object.freeze({
    authorize: function (connectorSlug) { return call('authorize', { connectorSlug: connectorSlug }); },
    refresh: function (connectorSlug, brokerRefresh) {
      return call('refresh', { connectorSlug: connectorSlug, brokerRefresh: brokerRefresh });
    }
  });
})(globalThis);
"""
    }
}

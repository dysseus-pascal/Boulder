package io.rebble.libpebblecommon.plugin

import io.ktor.client.HttpClient
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.js.HttpInterceptorManager
import kotlin.uuid.Uuid

class JsPluginFactory(
    private val appContext: AppContext,
    private val scope: LibPebbleCoroutineScope,
    private val httpClient: HttpClient,
    private val httpInterceptorManager: HttpInterceptorManager,
    private val oauthApi: PluginOAuthApi? = null,
) {
    fun create(
        pluginUuid: Uuid,
        name: String,
        manifest: PluginManifest,
        script: suspend () -> String,
    ): JsPlugin = JsPlugin(
        pluginUuid = pluginUuid,
        name = name,
        manifest = manifest,
        script = script,
        appContext = appContext,
        scope = scope,
        httpClient = httpClient,
        httpInterceptorManager = httpInterceptorManager,
        oauthApi = oauthApi,
    )
}

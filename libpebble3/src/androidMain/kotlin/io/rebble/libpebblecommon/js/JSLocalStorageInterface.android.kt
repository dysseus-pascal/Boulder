package io.rebble.libpebblecommon.js

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.rebble.libpebblecommon.connection.AppContext

internal actual fun createJSSettings(
    appContext: AppContext,
    id: String
): Settings {
    val context = appContext.context
    val preferencesName = "${context.packageName}_pkjs_$id"
    val delegate = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    return SharedPreferencesSettings(delegate)
}
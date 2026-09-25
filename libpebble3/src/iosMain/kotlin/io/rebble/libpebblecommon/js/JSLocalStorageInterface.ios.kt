package io.rebble.libpebblecommon.js

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext

internal actual fun createJSSettings(
    appContext: AppContext,
    id: String
): Settings {
    return NSUserDefaultsSettings.Factory().create("PKJS-$id")
}
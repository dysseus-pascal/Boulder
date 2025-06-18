package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

actual fun createJsRunner(
    appContext: AppContext,
    scope: CoroutineScope,
    device: PebbleJSDevice,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,
): JsRunner = WebViewJsRunner(
    appContext,
    device,
    scope,
    appInfo,
    lockerEntry,
    jsPath,
    libPebble,
    jsTokenUtil,
)
package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.WatchInfo
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
): JsRunner = WebViewJsRunner(
    appContext.context,
    device,
    scope,
    appInfo,
    lockerEntry,
    jsPath,
    libPebble,
)
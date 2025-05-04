package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.JavascriptCoreJsRunner
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

actual fun createJsRunner(
    appContext: AppContext,
    scope: CoroutineScope,
    device: PebbleJSDevice,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path
): JsRunner = JavascriptCoreJsRunner(
    appInfo,
    lockerEntry,
    jsPath,
    device,
    scope
)
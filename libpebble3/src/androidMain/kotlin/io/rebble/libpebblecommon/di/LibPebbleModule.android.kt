package io.rebble.libpebblecommon.di

import android.app.Application
import io.rebble.libpebblecommon.calendar.AndroidSystemCalendar
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.AndroidNotificationActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.calls.AndroidSystemCallLog
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidNotificationAppsSync
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.notification.processor.BasicNotificationProcessor
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformModule: Module = module {
    single {
        PhoneCapabilities(
            setOf(
                ProtocolCapsFlag.SupportsAppRunStateProtocol,
                //ProtocolCapsFlag.SupportsInfiniteLogDump,
                ProtocolCapsFlag.SupportsExtendedMusicProtocol,
                ProtocolCapsFlag.SupportsTwoWayDismissal,
                //ProtocolCapsFlag.SupportsLocalization
                ProtocolCapsFlag.Supports8kAppMessage,
//                ProtocolCapsFlag.SupportsHealthInsights,
//                ProtocolCapsFlag.SupportsAppDictation,
//                ProtocolCapsFlag.SupportsUnreadCoreDump,
//                ProtocolCapsFlag.SupportsWeatherApp,
//                ProtocolCapsFlag.SupportsRemindersApp,
//                ProtocolCapsFlag.SupportsWorkoutApp,
//                ProtocolCapsFlag.SupportsSmoothFwInstallProgress,
//                ProtocolCapsFlag.SupportsFwUpdateAcrossDisconnection,
            )
        )
    }
    singleOf(::AndroidPebbleNotificationListenerConnection) bind NotificationListenerConnection::class
    singleOf(::AndroidNotificationActionHandler) bind PlatformNotificationActionHandler::class
    singleOf(::AndroidNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::AndroidSystemCalendar) bind SystemCalendar::class
    singleOf(::AndroidSystemCallLog) bind SystemCallLog::class
    single { get<AppContext>().context }
    single { NotificationHandler(setOf(get<BasicNotificationProcessor>()), get(), get(), get()) }
    singleOf(::BasicNotificationProcessor)
    single { get<Application>().contentResolver }
    single { PlatformConfig(syncNotificationApps = false) }
}
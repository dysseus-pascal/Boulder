package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.calendar.IosSystemCalendar
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationAppsSync
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationListenerConnection
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.calls.IosSystemCallLog
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
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
                //ProtocolCapsFlag.SupportsLocalization
                ProtocolCapsFlag.Supports8kAppMessage,
//                ProtocolCapsFlag.SupportsHealthInsights,
                ProtocolCapsFlag.SupportsNotificationFiltering,
//                ProtocolCapsFlag.SupportsUnreadCoreDump,
//                ProtocolCapsFlag.SupportsWeatherApp,
//                ProtocolCapsFlag.SupportsRemindersApp,
//                ProtocolCapsFlag.SupportsWorkoutApp,
//                ProtocolCapsFlag.SupportsSmoothFwInstallProgress,
//                ProtocolCapsFlag.SupportsFwUpdateAcrossDisconnection,
            )
        )
    }
    singleOf(::IosNotificationActionHandler) bind PlatformNotificationActionHandler::class
    singleOf(::IosNotificationListenerConnection) bind NotificationListenerConnection::class
    singleOf(::IosNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::IosSystemCalendar) bind SystemCalendar::class
    singleOf(::IosSystemCallLog) bind SystemCallLog::class
    single { PlatformConfig(syncNotificationApps = true) }
}

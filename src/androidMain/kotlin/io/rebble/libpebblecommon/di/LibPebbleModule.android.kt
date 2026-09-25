package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import org.koin.core.module.Module
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
}
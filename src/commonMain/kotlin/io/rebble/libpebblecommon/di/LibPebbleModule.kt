package io.rebble.libpebblecommon.di

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.BleConfig
import io.rebble.libpebblecommon.connection.CreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LibPebble3
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.Locker
import io.rebble.libpebblecommon.connection.LockerPBWCache
import io.rebble.libpebblecommon.connection.Negotiator
import io.rebble.libpebblecommon.connection.PebbleConnector
import io.rebble.libpebblecommon.connection.PebbleDeviceFactory
import io.rebble.libpebblecommon.connection.PebbleProtocolRunner
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.RealScanning
import io.rebble.libpebblecommon.connection.RequestSync
import io.rebble.libpebblecommon.connection.Scanning
import io.rebble.libpebblecommon.connection.StaticLockerPBWCache
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectionParams
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.Mtu
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebblePairing
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PpogClient
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PpogServer
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.bleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.KableGattConnector
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.time.createTimeChanged
import io.rebble.libpebblecommon.web.WebSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import org.koin.core.Koin
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module

data class ConnectionScopeProperties(
    val transport: Transport,
    val scope: CoroutineScope,
    val platformIdentifier: PlatformIdentifier,
)

class ConnectionScope(
    private val koinScope: Scope,
    val transport: Transport,
    private val coroutineScope: CoroutineScope,
) {
    val pebbleConnector: PebbleConnector = koinScope.get()

    fun close() {
        coroutineScope.cancel()
        koinScope.close()
    }
}

class ConnectionScopeFactory(private val koin: Koin) {
    fun createScope(props: ConnectionScopeProperties): ConnectionScope {
        val scope = koin.createScope<ConnectionScope>(props.transport.identifier.asString, props)
        Logger.d("scope: $scope")
        return ConnectionScope(scope, props.transport, props.scope)
    }
}

fun initKoin(config: LibPebbleConfig): Koin {
    val koin = koinApplication().koin
    koin.loadModules(
        listOf(
            module {
                factory { config }
                factory { config.context }
                factory { config.webServices }
                factory { config.bleConfig }

                single { getRoomDatabase(get()) }
                singleOf(::StaticLockerPBWCache) bind LockerPBWCache::class
                singleOf(::PlatformNotificationActionHandler)
                singleOf(::PebbleDeviceFactory)
                single { get<Database>().knownWatchDao() }
                singleOf(::WatchManager) bind WatchConnector::class
                single { bleScanner() }
                singleOf(::RealScanning) bind Scanning::class
                single { GlobalScope } bind CoroutineScope::class // TODO remove this
                singleOf(::Locker)
                singleOf(::WebSyncManager) bind RequestSync::class
                single { createTimeChanged(get()) }
                singleOf(::LibPebble3) bind LibPebble::class
                single { ConnectionScopeFactory(koin) }
                singleOf(::CreatePlatformIdentifier)

                scope<ConnectionScope> {
                    // Params
                    scoped { get<ConnectionScopeProperties>().scope }
                    scoped { get<ConnectionScopeProperties>().transport }
                    scoped { get<ConnectionScopeProperties>().transport as Transport.BluetoothTransport.BleTransport }
                    scoped { get<ConnectionScopeProperties>().transport as Transport.SocketTransport }
                    scoped { (get<ConnectionScopeProperties>().platformIdentifier as PlatformIdentifier.BlePlatformIdentifier).peripheral }

                    // Connection
                    scopedOf(::KableGattConnector)
                    scopedOf(::PebbleBle)
                    scoped<GattConnector> {
                        when (get<Transport>()) {
                            is Transport.BluetoothTransport.BleTransport -> get<KableGattConnector>()
                            else -> TODO("not implemented")
                        }
                    }
                    scoped<TransportConnector> {
                        when (get<Transport>()) {
                            is Transport.BluetoothTransport.BleTransport -> get<PebbleBle>()
                            else -> TODO("not implemented")
                        }
                    }
                    scopedOf(::PebbleConnector)
                    scopedOf(::PebbleProtocolRunner)
                    scopedOf(::Negotiator)
                    scoped { PebbleProtocolStreams() }
                    scopedOf(::PPoG)
                    scoped { PPoGStream() }
                    scopedOf(::PpogClient)
                    scopedOf(::PpogServer)
                    scoped<PPoGPacketSender> {
                        when (get<BleConfig>().reversedPPoG) {
                            true -> get<PpogClient>()
                            false -> get<PpogServer>()
                        }
                    }
                    scopedOf(::ConnectionParams)
                    scopedOf(::Mtu)
                    scopedOf(::ConnectivityWatcher)
                    scopedOf(::PebblePairing)

                }
            }
        )
    )
    return koin
}

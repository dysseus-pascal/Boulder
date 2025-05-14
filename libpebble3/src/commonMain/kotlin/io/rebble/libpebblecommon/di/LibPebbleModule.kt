package io.rebble.libpebblecommon.di

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.rebble.libpebblecommon.connection.BleConfig
import io.rebble.libpebblecommon.connection.CreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LibPebble3
import io.rebble.libpebblecommon.connection.LibPebbleConfig
import io.rebble.libpebblecommon.connection.Locker
import io.rebble.libpebblecommon.connection.LockerPBWCache
import io.rebble.libpebblecommon.connection.Negotiator
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.connection.PebbleConnector
import io.rebble.libpebblecommon.connection.PebbleDeviceFactory
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolRunner
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.RealCreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.RealPebbleConnector
import io.rebble.libpebblecommon.connection.RealPebbleProtocolHandler
import io.rebble.libpebblecommon.connection.RealScanning
import io.rebble.libpebblecommon.connection.RequestSync
import io.rebble.libpebblecommon.connection.Scanning
import io.rebble.libpebblecommon.connection.StaticLockerPBWCache
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.RealBluetoothStateProvider
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
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.bleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.KableGattConnector
import io.rebble.libpebblecommon.connection.createCompanionDeviceManager
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.DebugPebbleProtocolSender
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdate
import io.rebble.libpebblecommon.connection.endpointmanager.NotificationManager
import io.rebble.libpebblecommon.connection.endpointmanager.PKJSLifecycleManager
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.AppBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationAppsDb
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.notification.NotificationApi
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.DataLoggingService
import io.rebble.libpebblecommon.services.LogDumpService
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import io.rebble.libpebblecommon.time.createTimeChanged
import io.rebble.libpebblecommon.web.WebSyncManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.cancel
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class ConnectionScopeProperties(
    val transport: Transport,
    val scope: ConnectionCoroutineScope,
    val platformIdentifier: PlatformIdentifier,
)

interface ConnectionScope {
    val transport: Transport
    val pebbleConnector: PebbleConnector
    fun close()
}

class RealConnectionScope(
    private val koinScope: Scope,
    override val transport: Transport,
    private val coroutineScope: ConnectionCoroutineScope,
    private val uuid: Uuid,
) : ConnectionScope {
    override val pebbleConnector: PebbleConnector = koinScope.get()

    override fun close() {
        Logger.d("close ConnectionScope: $koinScope / $uuid")
        coroutineScope.cancel()
        koinScope.close()
    }
}

interface ConnectionScopeFactory {
    fun createScope(props: ConnectionScopeProperties): ConnectionScope
}

class RealConnectionScopeFactory(private val koin: Koin) : ConnectionScopeFactory {
    override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
        val uuid = Uuid.random()
        val scope = koin.createScope<ConnectionScope>("${props.transport.identifier.asString}-$uuid", props)
        Logger.d("scope: $scope / $uuid")
        return RealConnectionScope(scope, props.transport, props.scope, uuid)
    }
}

/**
 * Essentially, GlobalScope for libpebble. Use this everywhere that would otherwise use GlobalScope.
 */
class LibPebbleCoroutineScope(override val coroutineContext: CoroutineContext) : CoroutineScope

/**
 * Per-connection coroutine scope, torn down when the connection ends.
 */
class ConnectionCoroutineScope(override val coroutineContext: CoroutineContext) : CoroutineScope

/**
 * Lazy/provider for when we need to get out of a circular dependency.
 */
class HackyProvider<T>(val getter: () -> T) {
    fun get(): T = getter()
}

expect val platformModule: Module

@OptIn(DelicateCoroutinesApi::class)
fun initKoin(config: LibPebbleConfig): Koin {
    val koin = koinApplication().koin
    val libPebbleScope = LibPebbleCoroutineScope(CoroutineName("libpebble3"))
    koin.loadModules(
        listOf(
            module {
                includes(platformModule)

                factory { config }
                factory { config.context }
                factory { config.webServices }
                factory { config.bleConfig }
                factory { config.watchConfig }

                single { getRoomDatabase(get()) }
                singleOf(::StaticLockerPBWCache) bind LockerPBWCache::class
                singleOf(::PebbleDeviceFactory)
                single { get<Database>().knownWatchDao() }
                single { get<Database>().blobDBDao() }
                single { get<Database>().lockerEntryDao() }
                single { get<Database>().notificationAppDao() }
                singleOf(::WatchManager) bind WatchConnector::class
                single { bleScanner() }
                singleOf(::RealScanning) bind Scanning::class
                single { libPebbleScope }
                singleOf(::Locker)
                singleOf(::WebSyncManager) bind RequestSync::class
                single { createTimeChanged(get()) }
                singleOf(::LibPebble3) bind LibPebble::class
                single { RealConnectionScopeFactory(koin) } bind ConnectionScopeFactory::class
                singleOf(::RealCreatePlatformIdentifier) bind CreatePlatformIdentifier::class
                singleOf(::GattServerManager)
                singleOf(::NotificationApi) bind NotificationApps::class
                singleOf(::RealBluetoothStateProvider) bind BluetoothStateProvider::class
                single { HttpClient() }
                single { createCompanionDeviceManager(get()) }
                factory { HackyProvider { get<Scanning>() } }
                factory<Clock> { Clock.System }

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
                    scopedOf(::RealPebbleConnector) bind PebbleConnector::class
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
                    scopedOf(::RealPebbleProtocolHandler) bind PebbleProtocolHandler::class

                    // Services
                    scopedOf(::SystemService)
                    scopedOf(::AppRunStateService)
                    scopedOf(::PutBytesService)
                    scopedOf(::BlobDBService)
                    scopedOf(::AppFetchService)
                    scopedOf(::TimelineService)
                    scopedOf(::AppMessageService)
                    scopedOf(::DataLoggingService)
                    scopedOf(::LogDumpService)

                    // Endpoint Managers
                    scopedOf(::PutBytesSession)
                    scoped { FirmwareUpdate(get(), get<TransportConnector>().disconnected, get(), get()) }
                    scopedOf(::NotificationBlobDB)
                    scopedOf(::TimelineActionManager)
                    scopedOf(::NotificationManager)
                    scopedOf(::AppBlobDB)
                    scopedOf(::AppFetchProvider)
                    scopedOf(::DebugPebbleProtocolSender)
                    scopedOf(::PKJSLifecycleManager)
                    scopedOf(::NotificationAppsDb)

                    // TODO we ccoouulllddd scope this further to inject more things that we still
                    //  pass in as args
                    //  - transport connected = has connected gatt client
                    //  - fully connected = has WatchInfo (more useful)
                }
            }
        )
    )
    return koin
}

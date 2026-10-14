package coredevices.pebble

import android.content.Context
import coredevices.pebble.kiesel.BoulderBegleiter
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.BuiltInCompanions
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

actual val platformWatchModule: Module = module {
    single {
        val context = get<Context>()
        AppContext(context.applicationContext)
    }
    single<Platform> { Platform.Android }
    singleOf(::PebbleAndroidDelegate)
    // Boulder: fest eingebaute Begleiter (Kieselsport), siehe BuiltInCompanions.
    single<BuiltInCompanions> { BoulderBegleiter(get<Context>().applicationContext) }
}
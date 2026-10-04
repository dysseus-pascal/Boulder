package io.rebble.libpebblecommon.database.boulder

import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.NachtDao
import org.koin.dsl.module

/**
 * Boulders Datenbank im Graphen von libpebble3. Wer NachtDao bekommt, bekommt
 * den Dao, der den Umzug abwartet - nie den rohen aus boulder.db.
 */
val boulderDatenModul = module {
    single { getBoulderDatenbank(get()) }
    single { NachtUmzug(get<Database>(), get<BoulderDatenbank>()) }
    single<NachtDao> { UmgezogenerNachtDao(get<BoulderDatenbank>().nachtDao(), get()) }
}

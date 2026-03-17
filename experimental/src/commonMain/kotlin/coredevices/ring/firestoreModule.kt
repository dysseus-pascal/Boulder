package coredevices.ring

import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

internal val firestoreModule = module {
    singleOf(::FirestoreRecordingsDao)
}
package coredevices.ring

import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.ring.database.firestore.dao.FirestoreTracesDao
import org.koin.dsl.module

internal val firestoreModule = module {
    single { FirestoreRecordingsDao { get() } }
    single { FirestoreTracesDao { get() } }
}
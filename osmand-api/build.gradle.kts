// OsmAnds AIDL-Schnittstelle, unveraendert aus Kiesel-Helper uebernommen.
//
// Die Dateien unter src/ stammen aus dem Modul OsmAnd-api von
// github.com/osmandapp/OsmAnd und stehen unter GPLv3 (LICENSE-OsmAnd) - wie
// Boulder selbst. Uebernommen ist ALLES und nicht nur das Gebrauchte: AIDL
// vergibt die Transaktionsnummern nach der Reihenfolge der Deklarationen, und
// gestrichene Methoden verschoeben alle folgenden.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Fremder Code, den wir nicht pflegen - seine Warnungen brechen den Bau nicht ab.
    lint {
        abortOnError = false
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            aidl.directories.add("src")
            java.directories.add("src")
        }
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

// Boulder: die Fassung steht in BOULDER_VERSION, versionCode ist die Zahl der Commits. Cores
// Schema packt den Tag in die Zahl und bricht an Boulders Tags (v0.54.0); die Commit-Zahl liegt
// ueber jeder installierten Boulder-Fassung und waechst auch mit jedem Core-Merge. Die Namen
// bleiben Cores, damit onVariants unten unveraendert bleibt.
val gitVersionName = providers.fileContents(rootProject.layout.projectDirectory.file("BOULDER_VERSION"))
    .asText.map { it.trim() }

val gitVersionCode = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull() ?: throw GradleException("Error reading current commit count")
}

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    if (!localReleaseBuild) {
        signingConfigs {
            create("release") {
                storeFile = file("../keystore.jks")
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        // Boulder: eigene App-ID, damit die App neben Cores App steht und sich ueber Boulder 0.53
        // installiert. Der namespace oben bleibt Cores, damit die Quellpakete gleich bleiben.
        applicationId = "ch.dysseus.boulder"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Boulder: nur 64 Bit (Entscheid 03.10.2026); Cactus liefert ohnehin nur arm64-v8a.
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("arm64-v8a")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Boulder: Cactus bringt seine iOS-Bibliotheken als Java-Ressourcen mit
            // (ios/lib/..., 16 MB ungepackt); Android laedt sie nie.
            excludes += "ios/**"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (localReleaseBuild) {
                signingConfig = signingConfigs.getByName("debug")
                // Crashlytics regenerates a mapping-id resource every build
                // (upToDateWhen=false), forcing aapt + a full R8 rerun even on
                // null builds. Skip it for local release builds.
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    mappingFileUploadEnabled = false
                }
            } else {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Components this module's manifest declares, so lint can resolve them.
    implementation(project(":util"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work)
    implementation(libs.health.kmp)

    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.koin.android)
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.firebase.auth)
    androidTestImplementation(project(":cactus"))
    androidTestImplementation(project(":experimental"))
    androidTestImplementation(project(":libindex"))
    androidTestImplementation(project(":index-ai"))
    androidTestImplementation(project(":mcp"))
}

// Resolved at execution time — a configuration-time .get() makes every commit invalidate the
// configuration cache.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionCode.set(gitVersionCode)
            it.versionName.set(gitVersionName)
        }
    }
}

// ---- Boulder ----

// Boulder: compose ui-tooling bringt eine exportierte PreviewActivity ins Manifest, ueber die jede
// App auf dem Telefon beliebige Composables der App starten kann. Die KMP-Module ziehen ui-tooling
// in androidMain (dort gibt es keine debug/release-Trennung), darum faellt es hier an der Grenze
// der App aus dem Release; Debug behaelt die Vorschau. Beide Koordinaten, weil das JetBrains-
// Artefakt auf das von AndroidX verweist.
configurations.matching {
    it.name == "releaseRuntimeClasspath" || it.name == "releaseCompileClasspath"
}.configureEach {
    exclude(group = "androidx.compose.ui", module = "ui-tooling")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling")
}

/**
 * Boulder: was die App exportieren darf, samt der Berechtigung davor. Alles andere bricht den
 * Release-Build ab: eine Abhaengigkeit kann ueber das Zusammenfuehren der Manifeste still eine
 * Komponente nachschieben (so kam die PreviewActivity ins Release), ohne dass ein Diff es zeigt.
 * Laufzeit-Empfaenger mit RECEIVER_EXPORTED sieht diese Pruefung nicht.
 */
val erlaubteExporte = setOf(
    "activity|coredevices.coreapp.MainActivity|perm=|read=|write=",
    // Cores Datei-Oeffnen (.pbw, .pbl, .pbz) seit 1.16.0.4; MainApplication schaltet genau eins ein.
    "activity-alias|coredevices.coreapp.FileOpenActivity|perm=|read=|write=",
    "activity-alias|coredevices.coreapp.SamsungFileOpenActivity|perm=|read=|write=",
    "activity-alias|coredevices.coreapp.HealthPermissionsRationaleActivity|perm=|read=|write=",
    "activity-alias|coredevices.coreapp.ViewPermissionUsageActivity|perm=android.permission.START_VIEW_PERMISSION_USAGE|read=|write=",
    "receiver|coredevices.coreapp.debug.QemuSetupReceiver|perm=android.permission.DUMP|read=|write=",
    "receiver|coredevices.coreapp.debug.DevConnectionReceiver|perm=android.permission.DUMP|read=|write=",
    "receiver|coredevices.coreapp.debug.SetSettingReceiver|perm=android.permission.DUMP|read=|write=",
    "receiver|coredevices.pebble.firmware.FirmwareSideloadReceiver|perm=android.permission.DUMP|read=|write=",
    "provider|io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider|perm=|read=|write=",
    "provider|io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider|perm=|read=|write=",
    "service|io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver|perm=|read=|write=",
    "service|io.rebble.libpebblecommon.notification.LibPebbleNotificationListener|perm=android.permission.BIND_NOTIFICATION_LISTENER_SERVICE|read=|write=",
    "service|io.rebble.libpebblecommon.calls.LibPebbleInCallService|perm=android.permission.BIND_INCALL_SERVICE|read=|write=",
    "service|androidx.health.platform.client.impl.sdkservice.HealthDataSdkService|perm=|read=|write=",
    "service|androidx.glance.appwidget.GlanceRemoteViewsService|perm=android.permission.BIND_REMOTEVIEWS|read=|write=",
    "service|androidx.work.impl.background.systemjob.SystemJobService|perm=android.permission.BIND_JOB_SERVICE|read=|write=",
    "receiver|androidx.work.impl.diagnostics.DiagnosticsReceiver|perm=android.permission.DUMP|read=|write=",
    "receiver|androidx.profileinstaller.ProfileInstallReceiver|perm=android.permission.DUMP|read=|write=",
    // Boulder: Kartenlinks kommen aus fremden Apps (Teilen, geo:, Maps-Links) - dafuer
    // ist die Activity da; sie oeffnet nur OsmAnd und schickt nichts weiter.
    "activity|coredevices.pebble.kiesel.KartenlinkActivity|perm=|read=|write=",
    // Boulder: ein Homescreen-Widget muss fuer den Launcher erreichbar sein; es
    // zeichnet nur und nimmt keine Daten an.
    "receiver|coredevices.pebble.kiesel.GesundheitWidget|perm=|read=|write=",
    // Cores Ring/Index (Modul experimental); Boulder nutzt sie nicht, Entscheid in AP7.
    "receiver|coredevices.ring.reminders.ReminderBootReceiver|perm=|read=|write=",
    "service|coredevices.ring.service.RingCompanionDeviceService|perm=android.permission.BIND_COMPANION_DEVICE_SERVICE|read=|write=",
    "activity|coredevices.ring.ShareToIndexNoteActivity|perm=|read=|write=",
    "activity|coredevices.ring.ShareToIndexReminderActivity|perm=|read=|write=",
    // Google-Bibliotheken (F25: eingebaut, still).
    "activity|com.google.firebase.auth.internal.GenericIdpActivity|perm=|read=|write=",
    "activity|com.google.firebase.auth.internal.RecaptchaActivity|perm=|read=|write=",
    "service|com.google.android.gms.auth.api.signin.RevocationBoundService|perm=com.google.android.gms.auth.api.signin.permission.REVOCATION_NOTIFICATION|read=|write=",
    "receiver|com.google.firebase.iid.FirebaseInstanceIdReceiver|perm=com.google.android.c2dm.permission.SEND|read=|write=",
)

abstract class PruefeExporte : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val erlaubt: SetProperty<String>

    @TaskAction
    fun pruefe() {
        val android = "http://schemas.android.com/apk/res/android"
        val dokument = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest.get().asFile)
        val gefunden = mutableSetOf<String>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val knoten = dokument.getElementsByTagName(tag)
            for (i in 0 until knoten.length) {
                val e = knoten.item(i) as org.w3c.dom.Element
                if (e.getAttributeNS(android, "exported") != "true") continue
                gefunden += "$tag|${e.getAttributeNS(android, "name")}" +
                    "|perm=${e.getAttributeNS(android, "permission")}" +
                    "|read=${e.getAttributeNS(android, "readPermission")}" +
                    "|write=${e.getAttributeNS(android, "writePermission")}"
            }
        }
        val neu = (gefunden - erlaubt.get()).sorted()
        // Ein veralteter Eintrag heisst meist: umbenannt oder andere Berechtigung, der neue steht in "neu".
        val weg = (erlaubt.get() - gefunden).sorted()
        if (neu.isEmpty() && weg.isEmpty()) {
            logger.lifecycle("Exported components verified: ${gefunden.size}, all allowlisted.")
            return
        }
        throw GradleException(buildString {
            appendLine("Exported component surface changed (androidApp/build.gradle.kts, erlaubteExporte).")
            if (neu.isNotEmpty()) appendLine("Exported but not allowlisted:\n  " + neu.joinToString("\n  "))
            if (weg.isNotEmpty()) appendLine("Allowlisted but no longer exported like this:\n  " + weg.joinToString("\n  "))
        })
    }
}

// Boulder: die Keep-Regel steht in osmand-api/consumer-rules.pro; geprueft wird, was R8 daraus
// gemacht hat. Nur die Klassenzeilen des Mappings stehen ohne Einzug: "alt -> neu:".
abstract class PruefeOsmandNamen : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @TaskAction
    fun pruefe() {
        val klassen = mutableMapOf<String, String>()
        mapping.get().asFile.useLines { zeilen ->
            for (z in zeilen) {
                if (!z.startsWith("net.osmand.aidlapi.")) continue
                val teile = z.removeSuffix(":").split(" -> ")
                klassen[teile[0]] = teile.getOrElse(1) { "" }
            }
        }
        // Fehlt ALatLon, hiesse "nichts umbenannt" nur, dass nichts geprueft wurde.
        if ("net.osmand.aidlapi.map.ALatLon" !in klassen) {
            throw GradleException("R8 mapping has no net.osmand.aidlapi.map.ALatLon; OsmAnd's class names are unchecked.")
        }
        val umbenannt = klassen.filter { (alt, neu) -> alt != neu }.map { (alt, neu) -> "$alt -> $neu" }.sorted()
        if (umbenannt.isNotEmpty()) {
            throw GradleException(
                "R8 renamed or removed OsmAnd AIDL classes (keep rule in osmand-api/consumer-rules.pro):\n  " +
                    umbenannt.joinToString("\n  ")
            )
        }
        logger.lifecycle("OsmAnd AIDL class names verified: ${klassen.size}, none renamed.")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val pruefung = tasks.register<PruefeExporte>("pruefe${name}Exporte") {
            group = "verification"
            description = "Fails if the release manifest exports anything not on the allowlist."
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            erlaubt.set(erlaubteExporte)
        }
        val osmandNamen = tasks.register<PruefeOsmandNamen>("pruefe${name}OsmandNamen") {
            group = "verification"
            description = "Fails if R8 renamed or removed a class of OsmAnd's AIDL API."
            mapping.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
        // An den Packaging-Tasks, nicht an assemble: auch installRelease und bundleRelease laufen
        // ueber sie. Lazy, weil es sie waehrend onVariants noch nicht gibt.
        val packaging = setOf("package$name", "package${name}Bundle")
        tasks.matching { it.name in packaging }.configureEach { dependsOn(pruefung, osmandNamen) }
        // tasks.matching schweigt, wenn nichts passt: nach einer Umbenennung in AGP liefe kein
        // Release mehr durch die Pruefung. Darum bricht der Bau ab, wenn sie im Graphen fehlt.
        gradle.taskGraph.whenReady {
            val pfad = project.path
            val packt = allTasks.any { it.name in packaging && it.project.path == pfad }
            val prueft = allTasks.any { it.name == "pruefe${name}Exporte" && it.project.path == pfad }
            if (packt && !prueft) {
                throw GradleException("pruefe${name}Exporte is not in the task graph although $name packaging is.")
            }
        }
    }
}


import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
    alias(libs.plugins.nativeCocoaPods)
    alias(libs.plugins.kotlinx.atomicfu)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

// Hoisted out of the lambda below, which must not capture the project.
val providerFactory = providers

// Number of commits in the git history, so it always increases on main.
val gitVersionCode = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull() ?: throw GradleException("Error reading current commit count")
}

// Newest tag anywhere in the repo, including on branches HEAD doesn't descend from.
val gitVersionName = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--tags", "--max-count=1")
}.standardOutput.asText.flatMap { rev ->
    providerFactory.exec {
        isIgnoreExitValue = true
        commandLine("git", "describe", "--tags", rev.trim().ifEmpty { "HEAD" })
    }.standardOutput.asText
}.map { it.trim().ifEmpty { "unknown" } }

dependencies {
    debugImplementation(compose.uiTooling)
}


kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Make xcode invoke gradle from the right place
    tasks.register("fixXcodeProject") {
        val xcodeProjectFile = project.file("../iosApp/Pods/Pods.xcodeproj/project.pbxproj")
        val rootProjectPath = rootProject.projectDir.absolutePath
        doLast {
            if (xcodeProjectFile.exists()) {
                var content = xcodeProjectFile.readText()
                content = content.replace("gradlew\\\" -p \\\"\$REPO_ROOT\\\"", "gradlew\\\" -p \\\"$rootProjectPath\\\"")
                xcodeProjectFile.writeText(content)
            } else {
                logger.warn("Xcode project file not found, skipping fix: ${xcodeProjectFile.path}")
            }
        }
    }
    tasks.named("podInstall") {
        finalizedBy("fixXcodeProject")
    }

    cocoapods {
        version = "1.0"
        summary = "Core App"
        homepage = "https://github.com/coredevices/CoreApp"
        license = "proprietary"
        ios.deploymentTarget = "15.6"
        podfile = project.file("../iosApp/Podfile")

        pod("GoogleSignIn", "8.0.0")
        pod("FirebaseCore", "11.10.0")
        pod("FirebaseAuth") {
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        pod("FirebaseFirestore") {
            linkOnly = true
            source = git("https://github.com/invertase/firestore-ios-sdk-frameworks.git") {
                // 11.10.0 — pinned here by commit because the synthetic Podfile's lock lives
                // under build/ and is never committed
                commit = "e43715cc392c819b522c7a189bed9400e757c788"
            }
        }
        // The binary FirebaseFirestoreInternal is static, so its C deps must be linked here
        pod("nanopb") {
            version = "3.30910.0"
            linkOnly = true
        }
        pod("leveldb-library") {
            version = "1.22.6"
            moduleName = "leveldb"
            linkOnly = true
        }
        pod("FirebaseStorage") {
            linkOnly = true
        }
        pod("FirebaseCrashlytics") {
            linkOnly = true
        }
        pod("FirebaseMessaging") {
            linkOnly = true
        }

        framework {
            baseName = "ComposeApp"
            linkerOpts("-framework", "Accelerate")
            val xcodeExists = providers.exec {
                isIgnoreExitValue = true
                commandLine("which", "xcode-select")
            }.result.get().exitValue == 0
            if (xcodeExists) {
                val xcodeDir = providers.exec {
                    commandLine("xcode-select", "-p")
                }.standardOutput.asText.get().trim()
                val osName =
                    if (target.konanTarget.name.contains("simulator")) "iphonesimulator" else "iphoneos"
                linkerOpts(
                    "-weak_framework", "CoreML",
                    "-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName"
                )
            }
        }
    }

    buildList {
        if (System.getenv("CI_RELEASE") != "true") {
            add(iosSimulatorArm64())
        } else {
            logger.warn("Skipping configuration of iOS simulator targets for CI release build")
        }
        add(iosArm64())
    }.forEach {
        it.binaries.all {
            freeCompilerArgs += listOf(
                "-Xdisable-phases=DevirtualizationAnalysis,DCEPhase"
            )
            // The binary FirebaseFirestoreInternal is static, so its C deps must be linked into
            // every binary that pulls in Firestore, not just the pod framework.
            val grpcSlice = if (target.konanTarget.name.contains("simulator")) {
                "ios-arm64_x86_64-simulator"
            } else {
                "ios-arm64"
            }
            listOf(
                "FirebaseFirestoreGRPCCoreBinary/grpc.xcframework" to "grpc",
                "FirebaseFirestoreGRPCCPPBinary/grpcpp.xcframework" to "grpcpp",
                "FirebaseFirestoreGRPCBoringSSLBinary/openssl_grpc.xcframework" to "openssl_grpc",
                "FirebaseFirestoreAbseilBinary/absl.xcframework" to "absl",
            ).forEach { (path, fw) ->
                val sliceDir = layout.buildDirectory
                    .dir("cocoapods/synthetic/ios/Pods/$path/$grpcSlice")
                    .get().asFile
                linkerOpts.addAll(listOf("-F" + sliceDir.absolutePath, "-framework", fw))
            }
        }
    }
    
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlinx.cinterop.BetaInteropApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        androidMain.dependencies {
            implementation(libs.firebase.crashlytics.ndk)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.credentials)
            implementation(libs.gms.auth)
            implementation(libs.identity.google)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
            implementation(libs.androidx.work)
            implementation(libs.play.update)
            implementation(libs.play.update.ktx)
            implementation(libs.coil.gif)
            implementation(libs.coredevices.haversine)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.ktor.client.okhttp)
            implementation(project(":experimental"))
            implementation(project(":util"))
            implementation(project(":index-ai"))
            implementation(project(":mcp"))
        }
        androidUnitTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.crashkios)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.backhandler)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil)
            implementation(libs.coil.svg)

            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)
            implementation(libs.firebase.crashlytics)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coroutines)
            implementation(project(":pebble"))
            implementation(project(":util"))
            implementation(project(":experimental"))
            implementation(libs.kmpnotifier)
            implementation(libs.kmpio)
            implementation(project(":libpebble3"))
            implementation(project(":libindex"))
            implementation(project(":index-ai"))
            api(project(":mcp"))
            implementation(libs.health.kmp)
        }
    }
    sourceSets.androidInstrumentedTest.dependencies {
        implementation(kotlin("test"))
    }
}

compose.resources {
    packageOfResClass = "coreapp.composeapp.generated.resources"
}

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
        compose = true
    }

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
        applicationId = "coredevices.coreapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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

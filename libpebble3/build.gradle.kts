plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pebble-dev/libpebblecommon")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

room {
    schemaDirectory("schema")
}

android {
    namespace = project.group.toString()
    compileSdk = 34
    namespace = "io.rebble.libpebblecommon"
    defaultConfig {
        minSdk = 23
        lint.targetSdk = compileSdk
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
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
        publishLibraryVariants("release", "debug")
    }

    jvm()

    iosX64 {
        binaries {
            framework {
                baseName = "libpebble3"
            }
        }
    }

    iosArm64 {
        binaries {
            framework {
                baseName = "libpebble3"
            }
        }
    }

    iosSimulatorArm64 {
        binaries {
            framework {
                baseName = "libpebble3"
            }
        }
    }
    
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain {
            kotlin {
                // Include ksp-generated common code (from our :blobdgen processor)
                srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            }
        }
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            // Using our forked version (in a submodule) which has a fix for iOS reads not working
            implementation("com.juul.kable:kable-core")
            implementation("io.github.skolson:kmp-io:0.1.5")
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(compose.ui)
            implementation(project(":blobannotations"))
            implementation(libs.settings)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
        }

        iosMain.dependencies {
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// Otherwise it doesn't trigger our blobdbgen processor when compiling code
// https://github.com/google/ksp/issues/567
tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

dependencies {
//    add("kspCommonMainMetadata", libs.room.compiler)
//    add("kspJvm", libs.room.compiler)
    add("kspCommonMainMetadata", project(":blobdbgen"))
    add("kspAndroid", libs.room.compiler)
    add("kspIosX64", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

/*
if (Os.isFamily(Os.FAMILY_MAC)) {
    val iosSimulatorFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosX64Task = (kotlin.targets.getByName("iosX64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        val iosSimulatorArm64Task = (kotlin.targets.getByName("iosSimulatorArm64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosX64Task.linkTask)
        dependsOn(iosSimulatorArm64Task.linkTask)
        platform.set("simulator")

        inputFrameworks.setFrom(project.files(iosX64Task.outputFile, iosSimulatorArm64Task.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosX64Task.outputFile.path+".dSYM", iosX64Task.outputFile.path+".dSYM"))
    }

    val iosDeviceFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosTask = (kotlin.targets.getByName("ios") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosTask.linkTask)
        platform.set("device")

        inputFrameworks.setFrom(project.files(iosTask.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosTask.outputFile.path+".dSYM"))
    }

    val assembleXCFramework by tasks.registering {
        onlyIf {
            org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_MAC)
        }
        val deviceTask = tasks.getByName("iosDeviceFatFramework")
        val simulatorTask = tasks.getByName("iosSimulatorFatFramework")
        dependsOn(deviceTask)
        dependsOn(simulatorTask)
        outputs.dir(layout.buildDirectory.dir("xcframework")).withPropertyName("outputDir")

        val outputPath = layout.buildDirectory.dir("xcframework").get().asFile.path + "/libpebblecommon.xcframework"

        doLast {
            delete(outputPath)
            exec {
                commandLine (
                    "xcodebuild", "-create-xcframework",
                    "-framework", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-framework", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-output", outputPath
                )
            }
        }
    }
}
*/
/*project.afterEvaluate {
    tasks.withType(PublishToMavenRepository::class.java) {
        onlyIf {
            !publication.name.contains("ios")
        }
    }
    tasks.withType(Jar::class.java) {
        onlyIf {
            !name.contains("ios")
        }
    }
}*/

abstract class PlatformFatFramework: DefaultTask() {
    @get:Input
    abstract val platform: Property<String>

    @get:InputFiles
    val inputFrameworks = project.objects.fileCollection()

    @get:InputFiles
    val inputFrameworkDSYMs = project.objects.fileCollection()

    @Internal
    val platformOutputDir: Provider<Directory> = platform.map { project.layout.buildDirectory.dir("platform-fat-framework/${it}").get() }

    @get:OutputDirectory
    val outputDir = project.objects.directoryProperty().convention(platformOutputDir)

    @get:OutputDirectories
    val outputFiles: Provider<Array<File>> = platformOutputDir.map {arrayOf(
        it.asFile.toPath().resolve(inputFrameworks.files.first().name).toFile(),
        it.asFile.toPath().resolve(inputFrameworkDSYMs.files.first().name).toFile()
    )}

    private fun copyFramework() {
        val file = inputFrameworks.files.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun copyFrameworkDSYM() {
        val file = inputFrameworkDSYMs.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun lipoMergeFrameworks() {
        val inputs = mutableListOf<String>()
        inputFrameworks.forEach {
            inputs.add(it.toPath().resolve("libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworks.files.first().name+"/libpebble3").toString()
        project.exec {
            commandLine ("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    private fun lipoMergeFrameworkDSYMs() {
        val inputs = mutableListOf<String>()
        inputFrameworkDSYMs.forEach {
            inputs.add(it.toPath().resolve("Contents/Resources/DWARF/libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworkDSYMs.files.first().name+"/Contents/Resources/DWARF/libpebble3").toString()
        project.exec {
            commandLine ("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    @TaskAction
    fun createPlatformFatFramework() {
        copyFramework()
        copyFrameworkDSYM()
        lipoMergeFrameworks()
        lipoMergeFrameworkDSYMs()
    }
}
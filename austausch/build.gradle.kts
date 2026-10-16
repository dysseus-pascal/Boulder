import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Boulder: das Format zwischen der App und Boulders eigenem Server (server/), einmal fuer beide.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "boulder.austausch"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.serialization)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

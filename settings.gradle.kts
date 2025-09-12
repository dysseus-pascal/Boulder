pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "libpebbleroot"

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")
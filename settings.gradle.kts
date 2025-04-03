pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "libpebblecommon"

sourceControl {
    gitRepository(java.net.URI.create("https://github.com/coredevices/kable.git")) {
        producesModule("com.juul.kable:kable-core")
    }
}

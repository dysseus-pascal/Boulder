import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Boulder: der eigene Server fuer die KI-Anbindung. Boulder spiegelt seine Daten hierher (/sync),
// Claude fragt sie ueber MCP ab (/mcp). Laeuft als Container (Containerfile, LIESMICH.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("boulder.server.StartKt")
    applicationName = "boulder-server"
}

dependencies {
    implementation(project(":austausch"))
    implementation(libs.modelcontextprotocol.server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.modelcontextprotocol.client)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.client.serialization.json)
}

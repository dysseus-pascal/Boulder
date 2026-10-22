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

version = providers.fileContents(rootProject.layout.projectDirectory.file("BOULDER_VERSION")).asText.get().trim()

// Das Buendel fuer den Server (distTar): bin/ und lib/, dazu Containerfile, Quadlet und LIESMICH.
// Das Image entsteht erst dort aus dem Buendel; Gradle und das Android-SDK braucht der Server nicht.
distributions {
    main {
        contents {
            from("Containerfile", "LIESMICH.md")
            from("quadlet") { into("quadlet") }
        }
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

dependencies {
    implementation(project(":austausch"))
    implementation(libs.modelcontextprotocol.server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqlite.bundled)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.modelcontextprotocol.client)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.client.serialization.json)
}

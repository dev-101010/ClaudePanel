import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

/**
 * Liest ein Geheimnis bevorzugt aus der Umgebung (CI), sonst aus einer Gradle-Property.
 * Die Property gehoert in die globale ~/.gradle/gradle.properties, nicht in die des
 * Projekts - letztere liegt im Repository.
 */
fun secret(environmentVariable: String, gradleProperty: String): Provider<String> =
    providers.environmentVariable(environmentVariable)
        .orElse(providers.gradleProperty(gradleProperty))

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Lokale Installation, falls konfiguriert - spart den Distributions-Download.
        val localPath = providers.gradleProperty("platformLocalPath")
        if (localPath.isPresent) {
            local(localPath.get())
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }

        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChainFile = layout.file(secret("CERTIFICATE_CHAIN_FILE", "signingCertificateChainFile").map { file(it) })
        privateKeyFile = layout.file(secret("PRIVATE_KEY_FILE", "signingPrivateKeyFile").map { file(it) })
        password = secret("PRIVATE_KEY_PASSWORD", "signingPassword")
    }

    publishing {
        token = secret("PUBLISH_TOKEN", "marketplaceToken")
    }
}

// IntelliJ Platform 2026.2 laeuft auf JBR 25; Kotlin und Java muessen dasselbe Ziel haben.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

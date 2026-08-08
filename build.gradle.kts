import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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

/**
 * Zieht den obersten Abschnitt aus CHANGELOG.md als HTML fuer die change-notes.
 *
 * Von Hand gepflegte change-notes laufen frueher oder spaeter vom CHANGELOG weg, und die
 * Abweichung faellt erst im Marketplace auf. Ueber `providers.fileContents` gelesen, damit
 * der Configuration Cache es mitbekommt, wenn sich die Datei aendert.
 */
val changeNotesHtml: Provider<String> =
    providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("## [") }
        if (start < 0) return@map ""

        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## [") }
        val section = if (end < 0) rest else rest.take(end)

        // Fortsetzungszeilen gehoeren zum vorigen Punkt - sonst zerfaellt jeder umbrochene
        // Eintrag in mehrere. Leerzeilen und Zwischenueberschriften beenden einen Punkt;
        // ohne das haengt sich die naechste Ueberschrift an den letzten Eintrag.
        val items = mutableListOf<String>()
        var open: StringBuilder? = null
        for (line in section) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- ") -> {
                    open = StringBuilder(trimmed.removePrefix("- "))
                    items += ""
                    items[items.lastIndex] = open.toString()
                }

                trimmed.isEmpty() || trimmed.startsWith("#") -> open = null

                open != null -> {
                    open.append(' ').append(trimmed)
                    items[items.lastIndex] = open.toString()
                }
            }
        }

        items.joinToString("", prefix = "<ul>", postfix = "</ul>") { item ->
            val escaped = item
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(Regex("`([^`]+)`"), "<code>$1</code>")
            "<li>$escaped</li>"
        }
    }

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        changeNotes = changeNotesHtml

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

    pluginVerification {
        ides {
            // Bewusst nur die Ziel-IDE, damit der Lauf ueberschaubar bleibt. Der
            // Marketplace verifiziert beim Upload zusaetzlich gegen sein eigenes IDE-Set.
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }
}

/**
 * Ohne Argument startet die Sandbox nur mit dem Willkommensbildschirm - und ein Tool Window
 * gibt es erst mit offenem Projekt. Geoeffnet wird bewusst *nicht* dieses Repository:
 * beim Testen soll Claude an Wegwerfdateien arbeiten, nicht am Quellcode des Plugins.
 */
val sandboxProject = layout.projectDirectory.dir("sandbox")

val prepareSandboxProject by tasks.registering {
    val target = sandboxProject
    outputs.dir(target)
    doLast {
        val dir = target.asFile
        dir.mkdirs()
        File(dir, "notizen.txt").writeText(
            """
            Spielwiese fuer das Claude Panel.

            Dieses Verzeichnis ist in .gitignore und wird von prepareSandboxProject
            neu erzeugt - hier darf beim Testen alles kaputtgehen.

            Zum Ausprobieren des Freigabedialogs:
              "Lies notizen.txt"                          -> Read, fragt nach
              "Erstelle test.txt mit dem Inhalt hallo"    -> Write, fragt nach
            """.trimIndent(),
        )
    }
}

tasks.runIde {
    dependsOn(prepareSandboxProject)
    argumentProviders.add(CommandLineArgumentProvider { listOf(sandboxProject.asFile.absolutePath) })

    // Schaltet den Mitschnitt nach system/log/claude-panel.log frei. Bewusst nur hier:
    // die Datei enthaelt die Unterhaltung im Klartext.
    systemProperty("claudepanel.log", "true")
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

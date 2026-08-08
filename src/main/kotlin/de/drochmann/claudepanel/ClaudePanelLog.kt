package de.drochmann.claudepanel

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A record of the traffic with the CLI, for looking things up when something misbehaves.
 *
 * **Off by default.** The record contains the whole conversation in clear text, and that
 * does not belong on a user's disk unasked. It is enabled only via
 * `-Dclaudepanel.log=true`, which `runIde` sets for the sandbox.
 *
 * Written to `<log directory>/claude-panel.log`, so in the sandbox
 * `.intellijPlatform/sandbox/ClaudePanel/<build>/system/log/claude-panel.log`.
 */
object ClaudePanelLog {

    val enabled: Boolean = System.getProperty("claudepanel.log") == "true"

    private val file: Path? =
        if (enabled) Path.of(PathManager.getLogPath(), "claude-panel.log") else null

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * Channels: `>` to the CLI, `<` from it, `!` stderr, plus free-form marks such as
     * `cmd` or `ui`. One character per direction keeps the file readable by eye.
     */
    @Synchronized
    fun log(channel: String, text: String) {
        val target = file ?: return
        val line = buildString {
            append(LocalTime.now().format(timeFormat))
            append(' ')
            append(channel.padEnd(3))
            append(' ')
            append(abbreviate(text))
            append('\n')
        }
        runCatching {
            Files.writeString(
                target, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
            )
        }
    }

    /** Separates runs so it is visible where a new process starts. */
    fun startRun(description: String) = log("===", description)

    /**
     * Events can get very long (thinking blocks carry kilobytes of signature). Unabridged
     * the file would be unreadable; dropping them entirely would mean guessing.
     */
    private fun abbreviate(text: String): String {
        val single = text.replace("\n", "\\n")
        return if (single.length <= MAX_ENTRY_LENGTH) single
        else single.take(MAX_ENTRY_LENGTH) + "…[${single.length} chars]"
    }

    private const val MAX_ENTRY_LENGTH = 2000
}

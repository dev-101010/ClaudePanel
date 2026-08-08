package de.drochmann.claudepanel

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Locates the `claude` CLI.
 *
 * The plugin only starts it - installing, updating and signing in stay with the CLI
 * itself. The project's trust model hangs on exactly that: credentials are never touched
 * here (see CLAUDE.md).
 *
 * Deliberately **without** a list of guessed install locations. Such a list (Homebrew,
 * nvm, npm-global, bun …) goes stale with every new install method, can only be tested on
 * the platform in question, and would mostly be redundant anyway - see [findInPath]. When
 * the search comes up empty, pointing at the setup docs is more honest than guessing.
 */
object ClaudeCli {

    /**
     * On Windows the file is `claude.exe` (native installer or WinGet) or `claude.cmd`
     * (npm shim), depending on how it was installed - searching for the bare name would
     * miss both. On Unix only the name itself exists.
     */
    private val CANDIDATES: List<String> =
        if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude.bat", "claude")
        else listOf("claude")

    /**
     * Searches PATH.
     *
     * [PathEnvironmentVariableUtil] goes through `EnvironmentUtil`, which reads the login
     * shell's environment rather than just the IDE process's. That matters on macOS and
     * Linux: an IDE started from Finder or a launcher otherwise inherits a minimal PATH.
     * Because this gives us the same PATH as the terminal, this single lookup covers every
     * install method that works there.
     */
    fun findInPath(): File? =
        CANDIDATES.firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it) }

    /**
     * A configured path wins, empty means search.
     *
     * The configurable path is the fallback for everything a PATH lookup cannot cover:
     * several versions side by side, a wrapper script, or an install deliberately kept out
     * of PATH.
     */
    fun resolve(configuredPath: String): File? {
        val configured = configuredPath.trim()
        if (configured.isEmpty()) return findInPath()
        return File(configured).takeIf { it.isFile }
    }

    /**
     * Whether a login exists; `null` when it could not be determined.
     *
     * `claude auth status --json` also answers with email address, organisation and
     * subscription. **Only `loggedIn` is read** - the rest is none of the plugin's
     * business and is neither displayed nor stored.
     */
    fun isLoggedIn(executable: File): Boolean? {
        val commandLine = GeneralCommandLine(executable.absolutePath, "auth", "status", "--json")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)

        val output = runCatching { CapturingProcessHandler(commandLine).runProcess(STATUS_TIMEOUT_MS) }
            .getOrNull() ?: return null
        if (output.isTimeout) return null

        val parsed = runCatching { JsonParser.parseString(output.stdout) }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        return parsed.asJsonObject.get("loggedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean
    }

    /**
     * The account's current usage, as the CLI reports it; `null` when it could not be read.
     *
     * Asked as its own short-lived call rather than sending `/usage` into the running
     * session: that would put the answer into the conversation, and into the stored
     * transcript with it. The result is shown and then dropped - nothing about it is kept.
     */
    fun usage(executable: File): String? {
        val commandLine = GeneralCommandLine(executable.absolutePath, "--print", "/usage")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)

        val output = runCatching { CapturingProcessHandler(commandLine).runProcess(USAGE_TIMEOUT_MS) }
            .getOrNull() ?: return null
        if (output.isTimeout) return null
        return output.stdout.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * What the gauge needs out of [usage]; each field is `null` when it could not be read.
     *
     * [highest] is the fallback: it needs no labels at all and therefore survives a
     * rewording that [session] and [week] would not.
     */
    data class Usage(val session: Int?, val week: Int?, val highest: Int?)

    /**
     * The shares of the limits already used, for the gauge; see [Usage].
     *
     * `/usage` answers in prose, so this reads numbers out of free text and is therefore
     * brittle by nature - a reworded line breaks it. Kept deliberately small and
     * fail-quiet, in two stages: if the labels change, [highest] still carries the gauge;
     * if nothing matches at all, the gauge stays empty and the tooltip still shows the
     * full text. Nothing else depends on these values.
     *
     * Measured against CLI 2.1.225, which reports two windows:
     * ```
     * Current session:            39% used · resets Aug 9, 1:50am (Europe/Berlin)
     * Current week (all models):  37% used · resets Aug 13, 2am (Europe/Berlin)
     * ```
     * Further lines below those speak of shares *of* the usage ("85% of your usage was at
     * >150k context") - those are not shares *of a limit* and do not match [PERCENT_USED].
     * Several weekly lines (a separate quota per model, say) collapse to the highest.
     */
    fun parseUsage(usage: String): Usage {
        var session: Int? = null
        var week: Int? = null
        var highest: Int? = null

        for (line in usage.lineSequence()) {
            val percent = PERCENT_USED.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..100 } ?: continue

            highest = maxOf(highest ?: percent, percent)
            when {
                SESSION_LINE.containsMatchIn(line) -> session = maxOf(session ?: percent, percent)
                WEEK_LINE.containsMatchIn(line) -> week = maxOf(week ?: percent, percent)
            }
        }
        return Usage(session, week, highest)
    }

    private val PERCENT_USED = Regex("""(\d{1,3})\s*%\s*used""", RegexOption.IGNORE_CASE)
    private val SESSION_LINE = Regex("""\bsession\b""", RegexOption.IGNORE_CASE)
    private val WEEK_LINE = Regex("""\bweek(ly)?\b""", RegexOption.IGNORE_CASE)

    /** Starts the CLI's sign-in flow. It opens the browser itself. */
    fun loginCommand(executable: File): GeneralCommandLine =
        GeneralCommandLine(executable.absolutePath, "auth", "login")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)

    /**
     * Points at the guide rather than a command: the plugin installs nothing and should
     * not look as though something is meant to be run from here. Which method applies
     * depends on the system, and the guide stays current - unlike a hard-coded line.
     */
    const val SETUP_DOCS: String = "https://code.claude.com/docs/en/setup"

    private const val STATUS_TIMEOUT_MS = 10_000
    private const val USAGE_TIMEOUT_MS = 30_000

    fun describeProblem(configuredPath: String): String =
        if (configuredPath.isBlank()) {
            "Claude Code was not found. This plugin starts the CLI but does not install it.\n" +
                "   Setup guide: $SETUP_DOCS\n" +
                "   If it lives elsewhere, set the path from the gear menu."
        } else {
            "The configured path is not a file: $configuredPath"
        }
}

package de.drochmann.claudepanel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets

/**
 * Startet Claude Code als Hintergrundprozess und spricht ueber das JSON-Protokoll
 * mit ihm - kein Terminal, kein TTY.
 *
 *   claude -p --output-format stream-json --input-format stream-json --verbose
 *
 * stdout liefert einen Ereignisstrom (ein JSON-Objekt pro Zeile), stdin nimmt
 * Nachrichten im selben Format entgegen.
 */
class ClaudeProcess(
    private val workDir: String,
    private val permissionMode: String,
    private val model: String?,
    private val resumeSessionId: String?,
    private val onEvent: (JsonObject) -> Unit,
    private val onRawLine: (String) -> Unit,
    private val onTerminated: (Int) -> Unit,
) : Disposable {

    private var handler: OSProcessHandler? = null
    private val stdoutBuffer = StringBuilder()

    fun start() {
        val commandLine = GeneralCommandLine("claude").apply {
            addParameters("--print")
            addParameters("--output-format", "stream-json")
            addParameters("--input-format", "stream-json")
            addParameters("--include-partial-messages")
            addParameters("--permission-mode", permissionMode)
            model?.takeIf { it.isNotBlank() }?.let { addParameters("--model", it) }
            resumeSessionId?.takeIf { it.isNotBlank() }?.let { addParameters("--resume", it) }
            setWorkDirectory(workDir)
            charset = StandardCharsets.UTF_8
        }

        LOG.info("Starte: ${commandLine.commandLineString}")

        val processHandler = OSProcessHandler(commandLine)
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputType.STDERR) {
                    onRawLine(event.text.trimEnd())
                    return
                }
                consumeStdout(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                onTerminated(event.exitCode)
            }
        })
        processHandler.startNotify()
        handler = processHandler
    }

    /**
     * Der Prozess liefert Text in Haeppchen, nicht zeilenweise - daher puffern und
     * erst an Zeilenumbruechen zerlegen.
     */
    private fun consumeStdout(chunk: String) {
        stdoutBuffer.append(chunk)
        while (true) {
            val newlineIndex = stdoutBuffer.indexOf("\n")
            if (newlineIndex < 0) break
            val line = stdoutBuffer.substring(0, newlineIndex).trim()
            stdoutBuffer.delete(0, newlineIndex + 1)
            if (line.isNotEmpty()) dispatch(line)
        }
    }

    private fun dispatch(line: String) {
        val parsed = runCatching { JsonParser.parseString(line) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            // Kein JSON - roh durchreichen statt zu verschlucken.
            onRawLine(line)
            return
        }
        onEvent(parsed.asJsonObject)
    }

    /** Schickt eine Benutzernachricht im stream-json-Eingabeformat. */
    fun sendUserMessage(text: String) {
        val processHandler = handler ?: return
        val message = JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", text)
            })
        }
        val input = processHandler.processInput
        input.write((message.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        input.flush()
    }

    fun isRunning(): Boolean = handler?.isProcessTerminated == false

    fun stop() {
        handler?.destroyProcess()
        handler = null
    }

    override fun dispose() {
        stop()
    }

    companion object {
        private val LOG = logger<ClaudeProcess>()
    }
}

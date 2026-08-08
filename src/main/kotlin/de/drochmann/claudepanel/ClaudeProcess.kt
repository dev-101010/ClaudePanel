package de.drochmann.claudepanel

import com.google.gson.JsonNull
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
 * Runs Claude Code as a background process and talks to it over the JSON protocol - no
 * terminal, no TTY.
 *
 *   claude --print --verbose --output-format stream-json --input-format stream-json
 *
 * stdout carries an event stream (one JSON object per line), stdin takes messages in the
 * same format. The process stays alive across several turns as long as stdin is open, and
 * the session_id stays the same.
 */
class ClaudeProcess(
    private val executable: String,
    private val workDir: String,
    private val permissionMode: String,
    private val model: String?,
    private val effort: String?,
    private val resumeSessionId: String?,
    private val onEvent: (JsonObject) -> Unit,
    private val onPermissionRequest: (PermissionRequest) -> Unit,
    private val onTerminated: (Int, String?) -> Unit,
) : Disposable {

    private var handler: OSProcessHandler? = null
    private val stdoutBuffer = StringBuilder()
    private val stderrBuffer = StringBuilder()

    /**
     * What the process emits besides the event stream. It has no business in the
     * transcript, but on an unexpected end it is often the only explanation - hence
     * keeping the last lines rather than dropping them.
     */
    private val recentOutput = ArrayDeque<String>()

    fun start() {
        val commandLine = GeneralCommandLine(executable).apply {
            // Without this the process only inherits the IDE process's environment. On
            // macOS and Linux that is nearly empty when started from Finder or a launcher -
            // the CLI would then fail to find node or its own helpers.
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            addParameters("--print")
            // Without --verbose the CLI refuses outright:
            // "When using --print, --output-format=stream-json requires --verbose".
            addParameters("--verbose")
            addParameters("--output-format", "stream-json")
            addParameters("--input-format", "stream-json")
            addParameters("--permission-mode", permissionMode)
            // Undocumented (absent from --help), but the only way to answer requests
            // instead of letting them be refused silently. Should it go away, no
            // control_requests arrive - the session still runs.
            addParameters("--permission-prompt-tool", "stdio")
            model?.takeIf { it.isNotBlank() }?.let { addParameters("--model", it) }
            effort?.takeIf { it.isNotBlank() }?.let { addParameters("--effort", it) }
            resumeSessionId?.takeIf { it.isNotBlank() }?.let { addParameters("--resume", it) }
            setWorkDirectory(workDir)
            charset = StandardCharsets.UTF_8
        }

        LOG.info("Starting: ${commandLine.commandLineString}")
        ClaudePanelLog.startRun(commandLine.commandLineString)

        val processHandler = OSProcessHandler(commandLine)
        processHandler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when {
                    ProcessOutputType.isStderr(outputType) ->
                        consumeLines(stderrBuffer, event.text) { remember("!", it) }

                    ProcessOutputType.isStdout(outputType) ->
                        consumeLines(stdoutBuffer, event.text) { dispatch(it) }

                    // SYSTEM is the platform itself: it reports the command line and the
                    // exit code here. That belongs in the log, not in the transcript -
                    // otherwise the whole claude invocation shows up in the conversation.
                    else -> ClaudePanelLog.log("sys", event.text.trim())
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                flush(stdoutBuffer) { dispatch(it) }
                flush(stderrBuffer) { remember("!", it) }
                ClaudePanelLog.log("end", "exit code ${event.exitCode}")
                onTerminated(event.exitCode, recentOutput.joinToString("\n").ifBlank { null })
            }
        })
        processHandler.startNotify()
        handler = processHandler
        sendInitialize()
    }

    /**
     * Tells the CLI that someone here answers control requests. Without this handshake no
     * permission request arrives.
     */
    private fun sendInitialize() {
        writeLine(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", "initialize")
            add("request", JsonObject().apply {
                addProperty("subtype", "initialize")
                add("hooks", JsonNull.INSTANCE)
            })
        })
    }

    /**
     * The process delivers text in chunks, not line by line - so buffer and split on
     * newlines ourselves.
     */
    private fun consumeLines(buffer: StringBuilder, chunk: String, onLine: (String) -> Unit) {
        buffer.append(chunk)
        while (true) {
            val newlineIndex = buffer.indexOf("\n")
            if (newlineIndex < 0) break
            val line = buffer.substring(0, newlineIndex).trim()
            buffer.delete(0, newlineIndex + 1)
            if (line.isNotEmpty()) onLine(line)
        }
    }

    /** Whatever sits in the buffer at process end without a trailing newline. */
    private fun flush(buffer: StringBuilder, onLine: (String) -> Unit) {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        if (rest.isNotEmpty()) onLine(rest)
    }

    /** Into the log and into the short-term store for the failure case. */
    private fun remember(channel: String, line: String) {
        ClaudePanelLog.log(channel, line)
        recentOutput.addLast(line)
        while (recentOutput.size > MAX_REMEMBERED_LINES) recentOutput.removeFirst()
    }

    private fun dispatch(line: String) {
        ClaudePanelLog.log("<", line)
        val parsed = runCatching { JsonParser.parseString(line) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            // Non-JSON on stdout - console output too, not conversation.
            remember("?", line)
            return
        }
        val event = parsed.asJsonObject
        when (event.get("type")?.asString) {
            "control_request" -> handleControlRequest(event)
            // Acknowledgement of our handshake - nothing to do.
            "control_response", "control_cancel_request" -> Unit
            else -> onEvent(event)
        }
    }

    private fun handleControlRequest(event: JsonObject) {
        val requestId = event.get("request_id")?.asString ?: return
        val request = event.getAsJsonObject("request")

        // Anything we do not understand is answered with an error immediately: an
        // unanswered control request leaves the CLI waiting forever.
        if (request?.get("subtype")?.asString != "can_use_tool") {
            sendControlError(requestId, "Not supported by Claude Panel")
            return
        }

        onPermissionRequest(
            PermissionRequest(
                requestId = requestId,
                toolUseId = request.get("tool_use_id")?.asString,
                toolName = request.get("tool_name")?.asString ?: "Tool",
                description = request.get("description")?.asString.orEmpty(),
                input = request.getAsJsonObject("input"),
            )
        )
    }

    /** Answers a permission request. The CLI then continues, or reports the denial to the model. */
    fun answerPermission(request: PermissionRequest, allow: Boolean, denyMessage: String) {
        val decision = JsonObject().apply {
            if (allow) {
                addProperty("behavior", "allow")
                request.input?.let { add("updatedInput", it) }
            } else {
                addProperty("behavior", "deny")
                addProperty("message", denyMessage)
            }
        }
        writeLine(JsonObject().apply {
            addProperty("type", "control_response")
            add("response", JsonObject().apply {
                addProperty("subtype", "success")
                addProperty("request_id", request.requestId)
                add("response", decision)
            })
        })
    }

    private fun sendControlError(requestId: String, message: String) {
        writeLine(JsonObject().apply {
            addProperty("type", "control_response")
            add("response", JsonObject().apply {
                addProperty("subtype", "error")
                addProperty("request_id", requestId)
                addProperty("error", message)
            })
        })
    }

    /**
     * Interrupts the running answer without ending the session.
     *
     * Verified against 2.1.225: the CLI acknowledges with `still_queued`, sends a `result`
     * with `subtype: "error_during_execution"`, and keeps accepting messages afterwards -
     * same session_id, same process.
     */
    fun interrupt() {
        writeLine(JsonObject().apply {
            addProperty("type", "control_request")
            addProperty("request_id", "interrupt-${System.nanoTime()}")
            add("request", JsonObject().apply { addProperty("subtype", "interrupt") })
        })
    }

    /** Sends a user message in the stream-json input format. */
    fun sendUserMessage(text: String) {
        writeLine(JsonObject().apply {
            addProperty("type", "user")
            add("message", JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", text)
            })
        })
    }

    private fun writeLine(message: JsonObject) {
        ClaudePanelLog.log(">", message.toString())
        val processHandler = handler
        if (processHandler == null) {
            ClaudePanelLog.log("!", "no process - message dropped")
            return
        }
        runCatching {
            val input = processHandler.processInput
            input.write((message.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            input.flush()
        }.onFailure {
            LOG.warn("Could not write to stdin", it)
            ClaudePanelLog.log("!", "stdin write failed: ${it.message}")
        }
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
        private const val MAX_REMEMBERED_LINES = 20
    }
}

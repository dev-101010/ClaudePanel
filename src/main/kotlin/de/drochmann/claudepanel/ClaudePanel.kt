package de.drochmann.claudepanel

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * Die Oberflaeche: Session-Liste oben, Verlauf in der Mitte, Eingabe und
 * Mode-Leiste unten.
 */
class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val sessionListModel = DefaultListModel<SessionEntry>()
    private val sessionList = JBList(sessionListModel)
    private val transcript = JBTextArea()
    private val input = JBTextField()
    private val permissionMode = JComboBox(PERMISSION_MODES)
    private val modelField = JBTextField(12)
    private val startStopButton = JButton("Start")
    private val pickerButton = JButton("Session waehlen…")

    private var process: ClaudeProcess? = null
    private var currentSessionId: String? = null

    init {
        transcript.isEditable = false
        transcript.lineWrap = true
        transcript.wrapStyleWord = true

        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.setEmptyText("Noch keine Sessions aus diesem Fenster")
        sessionList.cellRenderer = SessionCellRenderer()

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JLabel("Letzte Sessions"), BorderLayout.NORTH)
            add(JBScrollPane(sessionList), BorderLayout.CENTER)
            add(pickerButton, BorderLayout.SOUTH)
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, JBScrollPane(transcript)).apply {
            resizeWeight = 0.25
            border = JBUI.Borders.empty()
        }

        add(split, BorderLayout.CENTER)
        add(buildBottomBar(), BorderLayout.SOUTH)

        reloadSessions()
        wireActions()
    }

    private fun buildBottomBar(): JPanel {
        val modes = JPanel().apply {
            add(JLabel("Mode:"))
            add(permissionMode)
            add(JLabel("Modell:"))
            add(modelField)
            add(startStopButton)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(input, BorderLayout.CENTER)
            add(modes, BorderLayout.SOUTH)
        }
    }

    private fun wireActions() {
        input.registerKeyboardAction(
            { sendCurrentInput() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JPanel.WHEN_FOCUSED,
        )

        startStopButton.addActionListener {
            if (process?.isRunning() == true) stopProcess() else startProcess(resumeSessionId = null)
        }

        pickerButton.addActionListener {
            // Rueckfallebene: Claude Codes eigener Picker, falls unsere Liste leer ist.
            append("[Hinweis] Der eingebaute Picker ist interaktiv und braucht ein Terminal. " +
                "Nutze dort: claude --resume")
        }

        sessionList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val selected = sessionList.selectedValue ?: return@addListSelectionListener
            if (process?.isRunning() == true) stopProcess()
            startProcess(resumeSessionId = selected.id)
        }
    }

    private fun startProcess(resumeSessionId: String?) {
        val workingDirectory = project.basePath
        if (workingDirectory == null) {
            append("[Fehler] Projekt hat kein Basisverzeichnis.")
            return
        }

        transcript.text = ""
        currentSessionId = null

        val started = ClaudeProcess(
            workDir = workingDirectory,
            permissionMode = permissionMode.selectedItem as String,
            model = modelField.text,
            resumeSessionId = resumeSessionId,
            onEvent = { event -> onEdt { handleEvent(event) } },
            onRawLine = { line -> onEdt { append(line) } },
            onTerminated = { exitCode -> onEdt { onProcessTerminated(exitCode) } },
        )
        Disposer.register(this, started)
        process = started

        runCatching { started.start() }.onFailure { failure ->
            append("[Fehler] Konnte 'claude' nicht starten: ${failure.message}")
            process = null
            return
        }
        startStopButton.text = "Stop"
    }

    private fun stopProcess() {
        process?.stop()
        process = null
        startStopButton.text = "Start"
    }

    private fun onProcessTerminated(exitCode: Int) {
        append("[Prozess beendet, Exit-Code $exitCode]")
        process = null
        startStopButton.text = "Start"
    }

    private fun sendCurrentInput() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        val running = process
        if (running == null || !running.isRunning()) {
            append("[Hinweis] Keine laufende Session - erst auf Start druecken.")
            return
        }
        append("> $text")
        running.sendUserMessage(text)
        input.text = ""
    }

    /**
     * Das genaue Ereignis-Schema von stream-json ist nicht oeffentlich dokumentiert,
     * deshalb wird defensiv gelesen: Bekanntes wird gerendert, alles andere roh
     * angezeigt statt verschluckt.
     */
    private fun handleEvent(event: JsonObject) {
        when (event.get("type")?.asString) {
            "system" -> {
                val sessionId = event.get("session_id")?.asString
                if (sessionId != null && currentSessionId == null) {
                    currentSessionId = sessionId
                    ClaudeSessionIndex.getInstance(project)
                        .record(sessionId, SimpleDateFormat(TITLE_FORMAT).format(Date()))
                    reloadSessions()
                    append("[Session $sessionId]")
                }
            }

            "assistant" -> appendAssistantText(event)

            "result" -> {
                event.get("result")?.takeIf { it.isJsonPrimitive }?.let { append(it.asString) }
                event.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }
                    ?.let { append("[Kosten: $${it.asString}]") }
            }

            // Partielle Chunks ignorieren - der vollstaendige "assistant"-Block folgt.
            "stream_event" -> Unit

            else -> append(event.toString())
        }
    }

    private fun appendAssistantText(event: JsonObject) {
        val message = event.getAsJsonObject("message") ?: return
        val content = message.getAsJsonArray("content") ?: return
        for (element in content) {
            if (!element.isJsonObject) continue
            val block = element.asJsonObject
            if (block.get("type")?.asString == "text") {
                block.get("text")?.takeIf { it.isJsonPrimitive }?.let { append(it.asString) }
            }
        }
    }

    private fun reloadSessions() {
        sessionListModel.clear()
        ClaudeSessionIndex.getInstance(project).sessions().forEach(sessionListModel::addElement)
    }

    private fun append(text: String) {
        transcript.append(text)
        transcript.append("\n")
        transcript.caretPosition = transcript.document.length
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    override fun dispose() {
        stopProcess()
    }

    companion object {
        private const val TITLE_FORMAT = "dd.MM.yyyy HH:mm"

        /** Die dokumentierten Werte von --permission-mode. */
        private val PERMISSION_MODES = arrayOf(
            "acceptEdits", "auto", "bypassPermissions", "manual", "dontAsk", "plan",
        )
    }
}

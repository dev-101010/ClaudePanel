package de.drochmann.claudepanel

import com.google.gson.JsonObject
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

/**
 * The interface: session picker on top, transcript in the middle, input and mode bar at
 * the bottom.
 */
class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val sessionModel = DefaultComboBoxModel<SessionEntry>()
    private val sessionCombo = ComboBox(sessionModel)
    private val transcript = JTextPane()
    private val input = JBTextField()
    /**
     * The three of them carry no caption: in a tool window this narrow the words would take
     * a third of the row, and the values say what they are anyway. What a field is for goes
     * into its tooltip instead.
     *
     * None of them can be typed into, and each is filled from what the CLI reports rather
     * than from a list kept here - a hand-written list ages with every new model, and a
     * free-text field only invents values the CLI will reject. The constants are the
     * fallback for when the lookup fails.
     */
    private val permissionMode = ComboBox(PERMISSION_MODES).apply {
        toolTipText = "Permission mode"
    }

    private val modelCombo = ComboBox(MODELS).apply {
        toolTipText = "Model"
        renderer = defaultLabelledRenderer()
    }

    private val effortCombo = ComboBox(EFFORTS).apply {
        toolTipText = "Effort level"
        renderer = defaultLabelledRenderer()
    }

    /**
     * Stays put and is merely disabled rather than disappearing - a button that comes and
     * goes makes the row jump and is harder to hit at the moment you need it.
     */
    private val stopButton = iconButton(AllIcons.Actions.Suspend, "Interrupt the running answer - the session stays")

    private val optionsButton = iconButton(AllIcons.General.GearPlain, "Options")

    /**
     * Purely an indicator: a ring for the five-hour window, its centre coloured by the
     * weekly one, with the figures in the tooltip. No click - it refreshes on its own, and
     * a button that only reprints what hovering already shows would be one control too many.
     */
    private val usageIcon = UsageIcon()
    private val usageGauge = JLabel(usageIcon).apply {
        border = JBUI.Borders.emptyLeft(6)
        toolTipText = "Reading usage…"
    }

    /** Where the embedded permission buttons sit, while they sit there. */
    private var permissionComponentStart: Int? = null

    private var process: ClaudeProcess? = null
    private var currentSessionId: String? = null

    /**
     * What the running process was started with. If the dropdown says something else, the
     * next message has to start a new process.
     */
    private var startedWith: String? = null

    /** Between a sent message and its "result" - only then is interrupting meaningful. */
    private var turnInProgress = false

    /** [reloadSessions] sets the selection itself - that must not start a process. */
    private var suppressSessionEvents = false

    /** A termination we caused is not worth reporting - only an unexpected one. */
    private var expectingTermination = false

    private val transcriptStore = ClaudeTranscriptStore(project)

    /**
     * Where the transcript is written to. For a new session the id is only known once the
     * `init` event arrives - until then nothing is saved, afterwards the whole transcript
     * including its beginning.
     */
    private var transcriptTarget: String? = null

    /** Where a tool call's line sits, until its result colours it. */
    private val toolLines = HashMap<String, IntRange>()

    /** Requests arrive one at a time but can pile up while nobody is looking. */
    private val pendingPermissions = ArrayDeque<PermissionRequest>()
    private var permissionTimeout: Timer? = null

    init {
        transcript.isEditable = false
        transcript.border = JBUI.Borders.empty(6, 8)
        installStyles()

        sessionCombo.renderer = SessionCellRenderer()
        sessionCombo.toolTipText = "Start a new session or resume an earlier one"

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(JLabel("Session:").apply { border = JBUI.Borders.emptyRight(4) }, BorderLayout.WEST)
            add(sessionCombo, BorderLayout.CENTER)
            add(optionsButton, BorderLayout.EAST)
        }

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(transcript), BorderLayout.CENTER)
        add(buildBottomBar(), BorderLayout.SOUTH)

        reloadSessions()
        restoreSettings()
        wireActions()
        greet()
    }

    /**
     * A JButton claims the padding of a full-size button even without border or label.
     * Here only the icon should take up room.
     */
    private fun iconButton(icon: javax.swing.Icon, tooltip: String): JButton =
        JButton(icon).apply {
            disabledIcon = IconLoader.getDisabledIcon(icon)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.emptyLeft(4)
            val square = JBUI.size(20)
            preferredSize = square
            minimumSize = square
            maximumSize = square
        }

    /**
     * A missing CLI would otherwise only surface once you have already typed something -
     * better to say so when the panel opens than to let the first message run into
     * nothing.
     */
    private fun greet() {
        val configured = ClaudePanelSettings.getInstance(project).claudePath
        val executable = ClaudeCli.resolve(configured)
        if (executable == null) {
            append(ClaudeCli.describeProblem(configured), STYLE_ERROR)
            return
        }
        showNewSessionHint()
        checkLogin(executable)
        // Fetch ahead of time so the first hover already has something to show.
        refreshUsage(force = false)
        loadCliOptions(executable)
    }

    /**
     * In the background: the call starts a process and must not freeze the interface.
     * Only the "not signed in" case is reported - an existing login needs no confirmation
     * in the transcript.
     */
    private fun checkLogin(executable: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (ClaudeCli.isLoggedIn(executable) != false) return@executeOnPooledThread
            onEdt { append(NOT_LOGGED_IN, STYLE_ERROR) }
        }
    }

    /**
     * Kicks off the CLI's sign-in - nothing more.
     *
     * The CLI opens the browser, runs the OAuth flow and stores the result itself. The
     * plugin sees neither code nor token and keeps none of it; it starts the process and
     * afterwards reads whether a login exists.
     */
    private fun startLogin() {
        val configured = ClaudePanelSettings.getInstance(project).claudePath
        val executable = ClaudeCli.resolve(configured)
        if (executable == null) {
            append(ClaudeCli.describeProblem(configured), STYLE_ERROR)
            return
        }

        separate()
        append(
            "Sign-in started - the CLI opens a browser for it. If none opens, a terminal " +
                "is the right place.",
            STYLE_DIM,
        )

        val handler = runCatching { OSProcessHandler(ClaudeCli.loginCommand(executable)) }
            .getOrElse {
                append("Could not start sign-in: ${it.message}", STYLE_ERROR)
                return
            }

        // The sign-in output is deliberately **not** shown: it contains the full sign-in
        // link including state and code_challenge. That would end up in the transcript -
        // and through the transcript store, on disk.
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                onEdt {
                    when (ClaudeCli.isLoggedIn(executable)) {
                        true -> append("Signed in.", STYLE_DIM)
                        false -> append("Still not signed in.", STYLE_ERROR)
                        null -> append("Could not determine sign-in state.", STYLE_ERROR)
                    }
                }
            }
        })
        handler.startNotify()
    }

    /**
     * Usage is account data: held in memory only, never written to the transcript or to
     * disk. It disappears with the panel.
     *
     * Kept at all because the query takes about 1.3 seconds - long enough that asking on
     * every click would mean staring at a dead button. So it is fetched ahead of time and
     * the hover shows what is already there.
     */
    private var usageText: String? = null
    private var usageFetchedAt: Long = 0
    private var usageLoading = false

    /**
     * @param force ignores the age; used for the explicit click. Otherwise the value is
     * only renewed once it has gone stale, so finishing a turn does not spawn a process
     * every time.
     */
    private fun refreshUsage(force: Boolean) {
        if (usageLoading) return
        if (!force && System.currentTimeMillis() - usageFetchedAt < USAGE_MAX_AGE_MS) return

        val configured = ClaudePanelSettings.getInstance(project).claudePath
        val executable = ClaudeCli.resolve(configured) ?: return

        usageLoading = true
        updateUsageDisplay()
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = ClaudeCli.usage(executable)
            onEdt {
                usageLoading = false
                if (fetched != null) {
                    usageText = fetched
                    usageFetchedAt = System.currentTimeMillis()
                    val usage = ClaudeCli.parseUsage(fetched)
                    // Without labels there is no telling the windows apart - then the ring
                    // shows whichever is highest, as it did before there were two of them.
                    usageIcon.ringPercent = usage.session ?: usage.highest
                    usageIcon.weekPercent = usage.week
                }
                updateUsageDisplay()
            }
        }
    }

    private fun updateUsageDisplay() {
        val cached = usageText
        usageGauge.toolTipText = when {
            cached == null && usageLoading -> "Reading usage…"
            cached == null -> "Usage unavailable"
            else -> asTooltipHtml(cached, usageFetchedAt)
        }
        usageGauge.repaint()
    }

    /** Swing tooltips only break lines when told to in HTML. */
    private fun asTooltipHtml(text: String, fetchedAt: Long): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        val stamp = SimpleDateFormat(CLOCK_FORMAT).format(Date(fetchedAt))
        // Said only while there is in fact a dot - otherwise the legend would explain a
        // part of the glyph that is not on screen.
        val legend = if (usageIcon.weekPercent != null) "ring: session · centre: week · " else ""
        return "<html><body>$escaped<br><br><i>${legend}as of $stamp</i></body></html>"
    }

    /**
     * Replaces the three lists with what the CLI offers, and names the empty entry after
     * what it is set to.
     *
     * An empty field claimed nothing was selected, which was never true - the CLI runs with
     * a model and an effort either way. Naming the entry says so without pinning anything:
     * the stored value stays empty, no flag is passed, and a later change to the CLI's own
     * setting shows up at the next open instead of being frozen in today's answer.
     */
    private fun loadCliOptions(executable: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val options = ClaudeCli.options(executable)
            onEdt {
                suppressSettingEvents = true
                try {
                    // Into the tooltip, not into the list: the list speaks the CLI's
                    // aliases, and a display name among them reads as a second vocabulary.
                    options.currentModel?.let {
                        modelCombo.toolTipText = "Model - the CLI is currently set to $it"
                    }
                    options.currentEffort?.let {
                        effortCombo.toolTipText = "Effort level - the CLI is currently set to $it"
                    }

                    // Each list on its own: one unreadable answer must not cost the others.
                    // What was stored decides the selection, not what is in the box - a
                    // stored name the built-in list does not know (say "opusplan") was
                    // dropped on restore and can be honoured now.
                    val settings = ClaudePanelSettings.getInstance(project)
                    refill(
                        modelCombo,
                        options.models,
                        keepEmpty = true,
                        wanted = settings.model,
                        fallback = "",
                    )
                    refill(
                        effortCombo,
                        options.efforts,
                        keepEmpty = true,
                        wanted = settings.effort,
                        fallback = "",
                    )
                    refill(
                        permissionMode,
                        options.permissionModes,
                        keepEmpty = false,
                        wanted = settings.permissionMode,
                        fallback = ClaudePanelSettings.DEFAULT_PERMISSION_MODE,
                    )
                } finally {
                    suppressSettingEvents = false
                }
            }
        }
    }

    /**
     * @param keepEmpty prepends the "leave it to the CLI" entry, which is not one of the
     * CLI's own names - it stands for passing no flag at all.
     * @param wanted what was stored for this project; taken if the CLI still offers it.
     * @param fallback where to land otherwise - the empty entry for model and effort, and
     * the built-in default for the mode, which has none. Not written back to storage: a
     * name that is gone today may be on offer again tomorrow, and the choice is not ours
     * to discard.
     */
    private fun refill(
        combo: ComboBox<String>,
        values: List<String>,
        keepEmpty: Boolean,
        wanted: String,
        fallback: String,
    ) {
        if (values.isEmpty()) return
        val entries = if (keepEmpty) listOf("") + values else values
        combo.model = DefaultComboBoxModel(entries.toTypedArray())
        combo.selectedItem = entries.firstOrNull { it == wanted }
            ?: entries.firstOrNull { it == fallback }
            ?: entries.first()
    }

    /** A remembered mode may come from an older version - then it falls back. */
    private fun restoreSettings() {
        val settings = ClaudePanelSettings.getInstance(project)
        permissionMode.selectedItem =
            PERMISSION_MODES.firstOrNull { it == settings.permissionMode }
                ?: ClaudePanelSettings.DEFAULT_PERMISSION_MODE
        // A value from a newer CLI would otherwise sit in the box without being on offer;
        // falling back to "" means the CLI decides, which is the safe end. The real lists
        // arrive a moment later from [loadCliOptions] and keep whatever still fits.
        modelCombo.selectedItem = MODELS.firstOrNull { it == settings.model } ?: ""
        effortCombo.selectedItem = EFFORTS.firstOrNull { it == settings.effort } ?: ""
    }

    /** Guards [rememberSettings] against the panel's own writes, as [reloadSessions] does. */
    private var suppressSettingEvents = false

    private fun rememberSettings() {
        if (suppressSettingEvents) return
        val settings = ClaudePanelSettings.getInstance(project)
        settings.permissionMode = permissionMode.selectedItem as? String
            ?: ClaudePanelSettings.DEFAULT_PERMISSION_MODE
        settings.model = currentModel()
        settings.effort = currentEffort()
    }

    private fun currentModel(): String = modelCombo.selectedItem?.toString().orEmpty().trim()

    private fun currentEffort(): String = effortCombo.selectedItem?.toString().orEmpty().trim()

    private fun buildBottomBar(): JPanel {
        // No FlowLayout: in a narrow tool window it silently cuts off whatever does not
        // fit the row - it hit the start button first, then the model field. GridLayout
        // divides the width instead and squeezes rather than hiding.
        val fields = JPanel(GridLayout(1, 3, 8, 0)).apply {
            add(permissionMode)
            add(modelCombo)
            add(withTrailing(effortCombo, usageGauge))
        }

        // The button belongs to the input, not to the settings: it acts on what was just
        // written.
        val inputRow = JPanel(BorderLayout()).apply {
            add(input, BorderLayout.CENTER)
            add(stopButton, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(inputRow, BorderLayout.CENTER)
            add(fields, BorderLayout.SOUTH)
        }
    }

    /**
     * Few, clearly distinguished roles rather than many colours: what I said, what Claude
     * answered, what happened along the way, and what went wrong.
     */
    private fun installStyles() {
        val document = transcript.styledDocument
        val default = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)

        // Every colour as a light/dark pair - a fixed colour would be unreadable in
        // exactly one of the two themes.
        document.addStyle(STYLE_PLAIN, default).also {
            StyleConstants.setForeground(it, UIUtil.getLabelForeground())
        }
        document.addStyle(STYLE_USER, default).also {
            StyleConstants.setBold(it, true)
            StyleConstants.setForeground(it, JBColor(0x2A6FCF, 0x589DF6))
        }
        // Tool calls: recognisable, but quieter than the answer.
        document.addStyle(STYLE_TOOL, default).also {
            StyleConstants.setForeground(it, JBColor(0x8A6100, 0xCFA83C))
        }
        document.addStyle(STYLE_TOOL_OK, default).also {
            StyleConstants.setForeground(it, JBColor(0x2E7D32, 0x6FBF73))
        }
        // A permission question halts the session - that may stand out.
        document.addStyle(STYLE_PERMISSION, default).also {
            StyleConstants.setBold(it, true)
            StyleConstants.setForeground(it, JBColor(0xB8730B, 0xE8A33D))
        }
        // Headers, cost, process notices: readable, but making no claim.
        document.addStyle(STYLE_DIM, default).also {
            StyleConstants.setForeground(it, UIUtil.getContextHelpForeground())
        }
        document.addStyle(STYLE_THINKING, default).also {
            StyleConstants.setItalic(it, true)
            StyleConstants.setForeground(it, JBColor(0x6E5494, 0xA98BD6))
        }
        document.addStyle(STYLE_ERROR, default).also {
            StyleConstants.setForeground(it, JBColor(0xC7222E, 0xFF6B68))
        }
    }

    /** Label on the left, field next to it - the field takes the rest of the cell. */
    /**
     * BorderLayout rather than a row: the gauge must keep its width even when the tool
     * window gets narrow, and the field beside it is the one that may shrink.
     */
    private fun withTrailing(field: JComponent, trailing: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            add(field, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
        }

    private fun wireActions() {
        input.registerKeyboardAction(
            { sendCurrentInput() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JPanel.WHEN_FOCUSED,
        )

        // Remember immediately rather than only on start: otherwise the selection is lost
        // when the window closes without a process ever having run.
        permissionMode.addActionListener { rememberSettings() }
        modelCombo.addActionListener { rememberSettings() }
        effortCombo.addActionListener { rememberSettings() }

        stopButton.addActionListener { interruptTurn() }
        optionsButton.addActionListener { showOptionsMenu() }

        // An existing session resumes immediately - there is nothing to type before
        // something should happen. "New session" waits for the first message instead,
        // otherwise a process would start unasked every time the window opens.
        sessionCombo.addActionListener {
            if (suppressSessionEvents) return@addActionListener
            val wanted = selectedSessionId()

            // "New session": start nothing, but the previous transcript no longer belongs
            // here - otherwise the first message writes underneath someone else's text.
            if (wanted == null) {
                if (process?.isRunning() == true) stopProcess()
                clearTranscript()
                startedWith = null
                showNewSessionHint()
                return@addActionListener
            }

            if (wanted == startedWith && process?.isRunning() == true) return@addActionListener
            if (process?.isRunning() == true) stopProcess()
            startProcess(wanted)
        }
    }

    /**
     * What lives here acts on the *selected* session, not the running one - which is why
     * the button sits next to the picker and not by the input.
     */
    private fun showOptionsMenu() {
        val selected = sessionCombo.selectedItem as? SessionEntry
        val selectedId = selected?.id?.takeIf { it.isNotBlank() }

        val menu = JBPopupMenu()

        menu.add(JMenuItem("Clear view").apply {
            addActionListener {
                clearTranscript()
                if (selectedId == null) showNewSessionHint()
            }
        })

        menu.add(JMenuItem("Delete stored transcript").apply {
            isEnabled = selectedId != null
            addActionListener {
                selectedId?.let { transcriptStore.delete(it) }
                clearTranscript()
            }
        })

        menu.addSeparator()

        menu.add(JMenuItem("Sign in…").apply {
            addActionListener { startLogin() }
        })

        menu.add(JMenuItem("Path to the claude CLI…").apply {
            addActionListener { askForClaudePath() }
        })

        menu.addSeparator()

        menu.add(JMenuItem("Remove session from the list").apply {
            isEnabled = selectedId != null
            addActionListener {
                val id = selectedId ?: return@addActionListener
                if (id == startedWith) stopProcess()
                ClaudeSessionIndex.getInstance(project).remove(id)
                transcriptStore.delete(id)
                clearTranscript()
                reloadSessions()
                showNewSessionHint()
            }
        })

        menu.add(JMenuItem("Forget all sessions").apply {
            isEnabled = sessionModel.size > 1
            addActionListener {
                val confirmed = MessageDialogBuilder
                    .yesNo(
                        "Forget all sessions?",
                        "The list and the stored transcripts are deleted. The sessions " +
                            "themselves remain in Claude Code.",
                    )
                    .ask(this@ClaudePanel)
                if (!confirmed) return@addActionListener
                if (process?.isRunning() == true) stopProcess()
                val index = ClaudeSessionIndex.getInstance(project)
                index.sessions().forEach { transcriptStore.delete(it.id) }
                index.clear()
                clearTranscript()
                reloadSessions()
                showNewSessionHint()
            }
        })

        menu.show(optionsButton, 0, optionsButton.height)
    }

    /**
     * The fallback when the PATH lookup does not find it - several versions side by side,
     * a wrapper script, an install kept out of PATH.
     */
    private fun askForClaudePath() {
        val settings = ClaudePanelSettings.getInstance(project)
        val found = ClaudeCli.findInPath()
        val hint = found?.let { "Found in PATH: ${it.absolutePath}" } ?: "Not found in PATH."

        val entered = Messages.showInputDialog(
            this,
            "$hint\n\nFull path to the claude CLI. Leave empty to search PATH.",
            "Path to the claude CLI",
            null,
            settings.claudePath,
            null,
        ) ?: return

        settings.claudePath = entered.trim()
        val resolved = ClaudeCli.resolve(settings.claudePath)
        if (resolved == null) append(ClaudeCli.describeProblem(settings.claudePath), STYLE_ERROR)
        else append("claude CLI: ${resolved.absolutePath}", STYLE_DIM)
    }

    /** Empty means: new session, so no --resume. */
    private fun selectedSessionId(): String? =
        (sessionCombo.selectedItem as? SessionEntry)?.id?.takeIf { it.isNotBlank() }

    /** Returns whether the process runs - the message must not be sent otherwise. */
    private fun startProcess(resumeSessionId: String?): Boolean {
        val workingDirectory = project.basePath
        if (workingDirectory == null) {
            append("The project has no base directory.", STYLE_ERROR)
            return false
        }

        clearTranscript()
        clearPermissions()
        rememberSettings()

        // When resuming the id is already known, so the old transcript can be there right
        // away. For a new session the record waits for the init event.
        if (resumeSessionId != null) {
            transcriptStore.load(resumeSessionId)?.let { restoreTranscript(it) }
            transcriptTarget = resumeSessionId
            append("Session ${resumeSessionId.take(8)} resumed.", STYLE_DIM)
        }

        val settings = ClaudePanelSettings.getInstance(project)
        val executable = ClaudeCli.resolve(settings.claudePath)
        if (executable == null) {
            append(ClaudeCli.describeProblem(settings.claudePath), STYLE_ERROR)
            return false
        }
        ClaudePanelLog.log("cli", executable.absolutePath)

        val started = ClaudeProcess(
            executable = executable.absolutePath,
            workDir = workingDirectory,
            permissionMode = permissionMode.selectedItem as String,
            model = currentModel(),
            effort = currentEffort(),
            resumeSessionId = resumeSessionId,
            onEvent = { event -> onEdt { handleEvent(event) } },
            onPermissionRequest = { request -> onEdt { enqueuePermission(request) } },
            onTerminated = { exitCode, output -> onEdt { onProcessTerminated(exitCode, output) } },
        )
        Disposer.register(this, started)
        process = started

        runCatching { started.start() }.onFailure { failure ->
            append("Could not start 'claude': ${failure.message}", STYLE_ERROR)
            process = null
            updateStatus()
            return false
        }
        startedWith = resumeSessionId
        updateStatus()
        return true
    }

    private fun stopProcess() {
        expectingTermination = true
        process?.stop()
        persistTranscript()
        process = null
        startedWith = null
        clearPermissions()
        updateStatus()
    }

    /**
     * Console output appears nowhere else in the transcript. Here it does: on an
     * unexpected end it is usually the only explanation.
     */
    private fun onProcessTerminated(exitCode: Int, output: String?) {
        // When switching sessions we terminate it ourselves - then the exit code is an
        // internal detail, not a message to the user.
        if (expectingTermination) {
            expectingTermination = false
        } else {
            separate()
            append("Session ended unexpectedly (exit code $exitCode)", STYLE_ERROR)
            output?.lines()?.forEach { append("   $it", STYLE_ERROR) }
        }
        process = null
        startedWith = null
        clearPermissions()
        updateStatus()
    }

    /**
     * Interrupts only the running turn. The process stays up so the next message lands in
     * the same session - hence no stop() here.
     */
    private fun interruptTurn() {
        val running = process?.takeIf { it.isRunning() } ?: return
        ClaudePanelLog.log("ui", "interrupt requested")
        running.interrupt()
        append("Interrupt requested", STYLE_DIM)
    }

    /** The stop button's state is what shows whether something is running. */
    private fun updateStatus() {
        val running = process?.isRunning() == true
        if (!running) turnInProgress = false
        stopButton.isEnabled = running && turnInProgress
    }

    /**
     * An unanswered permission request halts the session - the CLI waits. So it is made
     * visible and, after a while, denied by itself rather than leaving the process hanging
     * silently.
     */
    private fun enqueuePermission(request: PermissionRequest) {
        ClaudePanelLog.log("ui", "permission asked: ${request.toolName} (${request.requestId})")
        pendingPermissions.addLast(request)

        // The call's line is already there - recolouring it says the same as a second line
        // would, without doubling the transcript. The buttons appear right below it.
        val existing = request.toolUseId?.let { toolLines[it] }
        if (existing != null) {
            restyle(existing, STYLE_PERMISSION)
        } else {
            // Without a line to attach to, one is needed after all - otherwise there would
            // be no sign of what this is about. It is registered so the result colours it.
            val label = listOfNotNull(
                request.toolName,
                request.description.takeIf { it.isNotBlank() }?.let { abbreviate(it) },
            ).joinToString(": ")
            separate()
            val range = appendRanged("? $label", STYLE_PERMISSION)
            request.toolUseId?.let { toolLines[it] = range }
        }

        if (pendingPermissions.size == 1) showPendingPermission()
    }

    /**
     * The buttons sit in the text, right behind the line in question - a central bar would
     * force you to establish that connection yourself. Since the CLI stands still during a
     * request, that line is always the last one.
     */
    private fun showPendingPermission() {
        permissionTimeout?.stop()
        permissionTimeout = null
        removePermissionButtons()

        val request = pendingPermissions.firstOrNull() ?: return

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(JButton("Allow").apply {
                toolTipText = request.input?.toString()
                addActionListener { answerPermission(allow = true) }
            })
            add(JButton("Deny").apply {
                addActionListener { answerPermission(allow = false, reason = DENIED_BY_USER) }
            })
        }

        val document = transcript.styledDocument
        val start = document.length
        // Through an attribute rather than insertComponent(): the latter goes through
        // replaceSelection and does nothing in a non-editable area.
        val style = document.addStyle(STYLE_BUTTONS, null)
            .also { StyleConstants.setComponent(it, buttons) }
        runCatching {
            document.insertString(start, " ", style)
            document.insertString(document.length, "\n", document.getStyle(STYLE_PLAIN))
            permissionComponentStart = start
        }
        transcript.caretPosition = document.length

        permissionTimeout = Timer(PERMISSION_TIMEOUT_MS) { answerPermission(allow = false, reason = TIMED_OUT) }
            .apply { isRepeats = false; start() }
    }

    private fun removePermissionButtons() {
        val start = permissionComponentStart ?: return
        permissionComponentStart = null
        val document = transcript.styledDocument
        runCatching { document.remove(start, minOf(2, document.length - start)) }
    }

    private fun answerPermission(allow: Boolean, reason: String = "") {
        val request = pendingPermissions.removeFirstOrNull() ?: return
        permissionTimeout?.stop()
        permissionTimeout = null
        removePermissionButtons()

        ClaudePanelLog.log("ui", "answered ${if (allow) "allow" else "deny"} to ${request.requestId}")
        process?.answerPermission(request, allow, reason)

        // No line for the answer: the result colours the call's line green or red right
        // away, and on a denial the reason appears underneath.
        persistTranscript()
        showPendingPermission()
    }

    private fun clearPermissions() {
        permissionTimeout?.stop()
        permissionTimeout = null
        pendingPermissions.clear()
        removePermissionButtons()
    }

    /**
     * There is no start button: the first message starts the session. If the dropdown says
     * something other than what the running process was started with, it is replaced -
     * otherwise the message would land in the wrong session.
     */
    private fun sendCurrentInput() {
        val text = input.text.trim()
        if (text.isEmpty()) return

        val wanted = selectedSessionId()
        val running = process?.takeIf { it.isRunning() }
        if (running == null || wanted != startedWith) {
            if (running != null) stopProcess()
            if (!startProcess(wanted)) return
        }

        separate()
        append(text, STYLE_USER)
        process?.sendUserMessage(text)
        input.text = ""
        turnInProgress = true
        updateStatus()
    }

    /**
     * The schema is not publicly documented but was measured against CLI 2.1.225 (see
     * CLAUDE.md). What is known gets rendered, mere noise is dropped, anything unexpected
     * is still shown raw rather than swallowed.
     */
    private fun handleEvent(event: JsonObject) {
        when (event.get("type")?.asString) {
            "system" -> handleSystemEvent(event)
            "assistant" -> appendAssistantContent(event)
            "user" -> appendToolResults(event)
            "result" -> appendResultSummary(event)

            // Would be the token-by-token output - that would need
            // --include-partial-messages back.
            "stream_event" -> Unit

            "rate_limit_event" -> recordRateLimit(event)

            else -> append(event.toString(), STYLE_DIM)
        }
    }

    /**
     * Not shown, only recorded - groundwork for a decision that is still open.
     *
     * `rate_limit_info` is the only **structured** statement about limits the CLI makes;
     * the figures behind the ring come from prose (see [ClaudeCli.parseUsagePercent]).
     * Measured on 2026-08-08: arrives **once per process**, not per turn, and only for
     * `rateLimitType: "five_hour"` - so it goes stale in a long-lived session and says
     * nothing about the weekly window. In every sample `status` was `"allowed"`, so the
     * range of values is unknown.
     *
     * It is logged at info level rather than only to [ClaudePanelLog] because that one is
     * off by default: this way the samples accumulate in normal use. The content is limit
     * metadata - no conversation, nothing personal.
     */
    private fun recordRateLimit(event: JsonObject) {
        val info = event.getAsJsonObject("rate_limit_info") ?: return
        LOG.info("rate_limit_info: $info")
        ClaudePanelLog.log("rl", info.toString())
    }

    private fun handleSystemEvent(event: JsonObject) {
        when (event.get("subtype")?.asString) {
            // Arrives once per turn, not once per session.
            "init" -> {
                val sessionId = event.get("session_id")?.asString ?: return
                if (currentSessionId != null) return
                // When resuming the header is already there - do not double it.
                val resumed = startedWith == sessionId
                currentSessionId = sessionId
                // Otherwise the dropdown points at this session while the process still
                // counts as "started with nothing" - and the next message would replace it
                // for no reason.
                startedWith = sessionId
                bindTranscriptTo(sessionId)
                ClaudeSessionIndex.getInstance(project)
                    .record(sessionId, SimpleDateFormat(TITLE_FORMAT).format(Date()))
                reloadSessions()
                // The only place the alias is resolved for free: "opus" says nothing about
                // the version, init answers with the id that was actually taken. Once per
                // process - the guard above sees to that.
                val model = event.get("model")?.takeIf { it.isJsonPrimitive }?.asString
                when {
                    !resumed && model != null ->
                        append("New session ${sessionId.take(8)} started with $model.", STYLE_DIM)

                    !resumed -> append("New session ${sessionId.take(8)} started.", STYLE_DIM)

                    model != null -> append("Running $model.", STYLE_DIM)
                }
            }

            // Headless the CLI does not ask, it refuses outright - so the "manual" mode
            // means "no to everything" here.
            "permission_denied" -> {
                val tool = event.get("tool_name")?.asString ?: "Tool"
                append("  refused: $tool", STYLE_ERROR)
            }

            "status", "thinking_tokens" -> Unit

            else -> append(event.toString(), STYLE_DIM)
        }
    }

    private fun appendAssistantContent(event: JsonObject) {
        val blocks = contentBlocks(event).filter { it.get("type")?.asString in RENDERED_BLOCKS }
        if (blocks.isEmpty()) return

        // Spacing only before spoken text. Consecutive tool calls arrive as separate
        // events - a blank line between them would break one action into a series of
        // paragraphs.
        if (blocks.any { it.get("type")?.asString != "tool_use" }) separate()

        for (block in blocks) {
            when (block.get("type")?.asString) {
                "text" -> block.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(it.trim()) }

                // The block carries a kilobyte-long "signature" - that does not belong in
                // the transcript, the thinking text does. Empty blocks are common and say
                // nothing; they would just be noise before every answer.
                "thinking" -> block.get("thinking")?.asString.orEmpty().trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { append(it, STYLE_THINKING) }

                "tool_use" -> {
                    val name = block.get("name")?.asString ?: "Tool"
                    val line = "${markerFor(name)} $name${describeToolInput(block.getAsJsonObject("input"))}"
                    val range = appendRanged(line, STYLE_TOOL)
                    block.get("id")?.asString?.let { toolLines[it] = range }
                }
            }
        }
    }

    /**
     * The content of a successful tool call is raw material for the model - in the
     * transcript it would be a wall of file contents. So instead of a second line the
     * existing one changes colour: amber while it runs, green on success, red on failure.
     * Only the error text itself is shown in addition.
     */
    private fun appendToolResults(event: JsonObject) {
        for (block in contentBlocks(event)) {
            if (block.get("type")?.asString != "tool_result") continue
            val failed = block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

            block.get("tool_use_id")?.asString?.let { id ->
                toolLines.remove(id)?.let { restyle(it, if (failed) STYLE_ERROR else STYLE_TOOL_OK) }
            }

            if (!failed) continue
            val body = block.get("content")
                ?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                ?.trim()
                .orEmpty()
            if (body.isEmpty()) continue
            append("   ${abbreviate(body)}", STYLE_ERROR)
        }
    }

    /** A mark by kind of tool - reading, writing, running, delegating. */
    private fun markerFor(toolName: String): String = when (toolName) {
        "Read", "Glob", "Grep", "NotebookRead", "WebFetch", "WebSearch" -> "→"
        "Write", "Edit", "NotebookEdit" -> "✎"
        "Bash", "PowerShell" -> "$"
        "Task", "Agent", "Skill" -> "⇢"
        else -> "·"
    }

    /**
     * "result.result" only repeats the last assistant text, which is already in the
     * transcript - so just the numbers, and those discreetly.
     */
    private fun appendResultSummary(event: JsonObject) {
        turnInProgress = false
        updateStatus()
        // No spacing before it: cost and duration belong to the answer above, not as their
        // own section in between.
        if (event.get("subtype")?.asString != "success") {
            append("interrupted (${event.get("subtype")?.asString.orEmpty()})", STYLE_DIM)
            return
        }
        val cost = event.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
        val duration = event.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.asLong
        val parts = buildList {
            if (cost != null) add(String.format(Locale.ROOT, "$%.3f", cost))
            if (duration != null) add(String.format(Locale.ROOT, "%.1f s", duration / 1000.0))
        }
        if (parts.isNotEmpty()) append(parts.joinToString(" · "), STYLE_DIM)
        persistTranscript()
        // A finished turn is exactly when the numbers have moved - but only if the cached
        // ones have aged, so a long conversation does not spawn a process per turn.
        refreshUsage(force = false)
    }

    private fun contentBlocks(event: JsonObject): List<JsonObject> {
        val message = event.getAsJsonObject("message") ?: return emptyList()
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return content.filter { it.isJsonObject }.map { it.asJsonObject }
    }

    /** The most telling input field of a tool call, kept short. */
    private fun describeToolInput(input: JsonObject?): String {
        if (input == null) return ""
        val telling = TELLING_INPUT_KEYS.firstNotNullOfOrNull { key ->
            input.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        } ?: return ""
        return " ${abbreviate(shortenPath(telling))}"
    }

    /**
     * Absolute paths inside the project fill two lines on their own while saying nothing
     * you do not already know.
     */
    private fun shortenPath(text: String): String {
        val base = project.basePath?.trimEnd('/', '\\') ?: return text
        var result = text
        // Mid-text as well, not only at the start: in a shell command the path sits there
        // as an argument and is just as long and just as dispensable.
        for (variant in listOf(base.replace('/', '\\'), base.replace('\\', '/'))) {
            result = result.replace("$variant\\", "").replace("$variant/", "")
        }
        return result
    }

    private fun abbreviate(text: String): String {
        val single = text.replace('\n', ' ').trim()
        return if (single.length <= MAX_SUMMARY_LENGTH) single
        else single.take(MAX_SUMMARY_LENGTH) + "…"
    }

    /** The first entry is always "New session"; the selection survives the reload. */
    private fun reloadSessions() {
        val previous = (sessionCombo.selectedItem as? SessionEntry)?.id
        suppressSessionEvents = true
        try {
            sessionModel.removeAllElements()
            sessionModel.addElement(NEW_SESSION)
            ClaudeSessionIndex.getInstance(project).sessions().forEach(sessionModel::addElement)

            val wanted = currentSessionId ?: previous
            val match = (0 until sessionModel.size).map { sessionModel.getElementAt(it) }
                .firstOrNull { it.id.isNotBlank() && it.id == wanted }
            sessionCombo.selectedItem = match ?: NEW_SESSION
        } finally {
            suppressSessionEvents = false
        }
    }

    private fun append(text: String, style: String = STYLE_PLAIN) {
        appendRanged(text, style)
    }

    /** Like [append], but returns where the text sits - for recolouring it later. */
    private fun appendRanged(text: String, style: String = STYLE_PLAIN): IntRange {
        val document = transcript.styledDocument
        val start = document.length
        runCatching { document.insertString(start, "$text\n", document.getStyle(style)) }
        transcript.caretPosition = document.length
        return start until start + text.length
    }

    /**
     * Saves the transcript as it currently looks - with the style names, so that resuming
     * brings it back in colour rather than as a grey block.
     */
    private fun persistTranscript() {
        val target = transcriptTarget ?: return
        transcriptStore.save(target, serializeTranscript())
    }

    private fun serializeTranscript(): String {
        val document = transcript.styledDocument
        val root = document.defaultRootElement
        return buildString {
            for (index in 0 until root.elementCount) {
                val element = root.getElement(index)
                val start = element.startOffset
                val end = minOf(element.endOffset, document.length)
                if (end <= start) continue
                val line = runCatching { document.getText(start, end - start) }
                    .getOrDefault("").removeSuffix("\n")
                val style = document.getCharacterElement(start)
                    .attributes.getAttribute(StyleConstants.NameAttribute)?.toString()
                    ?: STYLE_PLAIN
                append(style).append('\t').append(line).append('\n')
            }
        }
    }

    /** Counterpart to [serializeTranscript]; unknown styles fall back to the plain tone. */
    private fun restoreTranscript(stored: String) {
        val document = transcript.styledDocument
        for (line in stored.trimEnd('\n').lineSequence()) {
            val separator = line.indexOf('\t')
            val style = if (separator > 0) line.substring(0, separator) else STYLE_PLAIN
            val text = if (separator > 0) line.substring(separator + 1) else line
            val resolved = document.getStyle(style) ?: document.getStyle(STYLE_PLAIN)
            runCatching { document.insertString(document.length, "$text\n", resolved) }
        }
        transcript.caretPosition = document.length
    }

    /** Recolours text already written - appending does not shift existing positions. */
    private fun restyle(range: IntRange, style: String) {
        val document = transcript.styledDocument
        runCatching {
            document.setCharacterAttributes(
                range.first, range.count(), document.getStyle(style), true,
            )
        }
    }

    /** An empty area does not say you can simply start typing - so it says it. */
    private fun showNewSessionHint() {
        append("New session — write something to start it.", STYLE_DIM)
    }

    /** Reset everything that hangs on the previous transcript. */
    private fun clearTranscript() {
        transcript.text = ""
        toolLines.clear()
        currentSessionId = null
        transcriptTarget = null
    }

    /** A blank line before a new section - but never two in a row. */
    private fun separate() {
        val document = transcript.styledDocument
        if (document.length == 0) return
        val tail = runCatching { document.getText(maxOf(0, document.length - 2), minOf(2, document.length)) }
            .getOrDefault("")
        if (tail.endsWith("\n\n")) return
        append("")
    }

    /** From here the session is known - the beginning collected so far can be saved too. */
    private fun bindTranscriptTo(sessionId: String) {
        transcriptTarget = sessionId
        persistTranscript()
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    override fun dispose() {
        persistTranscript()
        stopProcess()
    }

    companion object {
        private val LOG = logger<ClaudePanel>()

        private const val TITLE_FORMAT = "dd.MM.yyyy HH:mm"
        private const val CLOCK_FORMAT = "HH:mm"
        private const val MAX_SUMMARY_LENGTH = 160

        /** Usage barely moves within a few minutes, and the query costs no tokens. */
        private const val USAGE_MAX_AGE_MS = 5 * 60 * 1000L

        private const val STYLE_PLAIN = "plain"
        private const val STYLE_USER = "user"
        private const val STYLE_TOOL = "tool"
        private const val STYLE_TOOL_OK = "toolOk"
        private const val STYLE_PERMISSION = "permission"
        private const val STYLE_DIM = "dim"
        private const val STYLE_THINKING = "thinking"
        private const val STYLE_ERROR = "error"
        private const val STYLE_BUTTONS = "buttons"

        /** Placeholder for "no --resume". An empty id means: new session. */
        private val NEW_SESSION = SessionEntry("", "New session", Long.MAX_VALUE)

        /**
         * Aliases per `claude --help`; the field stays editable for full names. The empty
         * one leaves `--model` off altogether, so the CLI keeps whatever it is set to.
         */
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku", "fable")

        /**
         * Levels per `claude --help`, plus `auto`, which only `/effort` mentions. Verified
         * on 2026-08-08 that the flag is accepted together with `--print`. The empty one
         * leaves `--effort` off.
         */
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max", "auto")

        /**
         * Shown for the empty entry - in the list only, never as a value. Deliberately
         * without naming the model: everything else in the list is a CLI alias, and a
         * display name like "Opus 5" among them made the list read in two vocabularies.
         * What the CLI is set to goes into the tooltip, where a whole sentence fits.
         */
        private const val DEFAULT_LABEL = "(as in the CLI)"

        /**
         * `SimpleListCellRenderer.create` would be shorter but is scheduled for removal;
         * [ColoredListCellRenderer] has been around unchanged for years and matches the
         * platform's list styling, which a plain Swing renderer would not.
         */
        private fun defaultLabelledRenderer() =
            object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(
                    list: JList<out String>,
                    value: String?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(if (value.isNullOrBlank()) DEFAULT_LABEL else value)
                }
            }

        private const val PERMISSION_TIMEOUT_MS = 5 * 60 * 1000
        private const val DENIED_BY_USER = "Denied in the panel."
        private const val TIMED_OUT = "No answer in the panel, so denied."

        private const val NOT_LOGGED_IN =
            "Claude Code is not signed in. Use the gear menu: Sign in… - the CLI opens a " +
                "browser and handles it itself."

        /** What of an assistant message ends up in the transcript at all. */
        private val RENDERED_BLOCKS = setOf("text", "thinking", "tool_use")

        /** The order decides which field describes a tool call. */
        private val TELLING_INPUT_KEYS = listOf(
            "file_path", "command", "pattern", "url", "path", "prompt", "description",
        )

        /** The documented values of --permission-mode. */
        private val PERMISSION_MODES = arrayOf(
            "acceptEdits", "auto", "bypassPermissions", "manual", "dontAsk", "plan",
        )
    }
}

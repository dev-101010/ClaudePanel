package de.drochmann.claudepanel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Remembers the settings of the mode bar across sessions.
 *
 * Deliberately per project: which permission mode is defensible depends on the project -
 * a scratch directory may allow more than a real application. Lives in the same file as
 * [ClaudeSessionIndex] but as its own component, so a session index does not get mixed up
 * with interface state.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudePanelSettings", storages = [Storage("claude-panel.xml")])
class ClaudePanelSettings : PersistentStateComponent<ClaudePanelSettings.SettingsState> {

    class SettingsState {
        @JvmField
        var permissionMode: String = DEFAULT_PERMISSION_MODE

        /** Empty means no --model, so the CLI's default. */
        @JvmField
        var model: String = ""

        /** Empty means search PATH. Otherwise a full path to the CLI. */
        @JvmField
        var claudePath: String = ""
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        this.state = state
    }

    var permissionMode: String
        get() = state.permissionMode
        set(value) {
            state.permissionMode = value
        }

    var model: String
        get() = state.model
        set(value) {
            state.model = value
        }

    var claudePath: String
        get() = state.claudePath
        set(value) {
            state.claudePath = value
        }

    companion object {
        const val DEFAULT_PERMISSION_MODE = "acceptEdits"

        fun getInstance(project: Project): ClaudePanelSettings = project.service()
    }
}

package de.drochmann.claudepanel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * One entry of the session list.
 *
 * Deliberately with a no-argument constructor and `var` fields: the platform's XML
 * serialisation instantiates the class reflectively.
 */
class SessionEntry {
    var id: String = ""
    var title: String = ""
    var startedAtEpochMillis: Long = 0L

    constructor()

    constructor(id: String, title: String, startedAtEpochMillis: Long) {
        this.id = id
        this.title = title
        this.startedAtEpochMillis = startedAtEpochMillis
    }
}

/**
 * Keeps the index of sessions this plugin started.
 *
 * Claude Code offers no command that lists sessions machine-readably, and the internal
 * layout under ~/.claude/projects is undocumented. Rather than parsing it, the plugin
 * remembers the ids the event stream reports anyway and keeps them in project state. That
 * way the list depends on nothing that can change underneath us.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudePanelSessionIndex", storages = [Storage("claude-panel.xml")])
class ClaudeSessionIndex : PersistentStateComponent<ClaudeSessionIndex.IndexState> {

    class IndexState {
        @JvmField
        var sessions: MutableList<SessionEntry> = mutableListOf()
    }

    private var state = IndexState()

    override fun getState(): IndexState = state

    override fun loadState(state: IndexState) {
        this.state = state
    }

    /** Newest first. */
    fun sessions(): List<SessionEntry> = state.sessions.sortedByDescending { it.startedAtEpochMillis }

    fun remove(id: String) {
        state.sessions.removeAll { it.id == id }
    }

    fun clear() {
        state.sessions.clear()
    }

    fun record(id: String, title: String) {
        if (id.isBlank()) return
        val existing = state.sessions.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.title.isBlank()) existing.title = title
            return
        }
        state.sessions.add(SessionEntry(id, title, System.currentTimeMillis()))
        while (state.sessions.size > MAX_SESSIONS) {
            val oldest = state.sessions.minByOrNull { it.startedAtEpochMillis } ?: break
            state.sessions.remove(oldest)
        }
    }

    companion object {
        private const val MAX_SESSIONS = 50

        fun getInstance(project: Project): ClaudeSessionIndex = project.service()
    }
}

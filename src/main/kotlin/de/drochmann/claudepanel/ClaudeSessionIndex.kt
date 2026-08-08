package de.drochmann.claudepanel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Ein Eintrag der Session-Liste.
 *
 * Bewusst mit parameterlosem Konstruktor und `var`-Feldern: die XML-Serialisierung
 * der Plattform instanziiert die Klasse reflektiv.
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
 * Fuehrt den Index der Sessions, die dieses Plugin gestartet hat.
 *
 * Claude Code bietet keinen Befehl, der Sessions maschinenlesbar auflistet, und das
 * interne Ablageformat unter ~/.claude/projects ist undokumentiert. Statt es zu parsen
 * merkt sich das Plugin die IDs, die ihm der Ereignisstrom ohnehin nennt, und legt sie
 * im projekteigenen Zustand ab. Damit haengt die Liste an nichts, was sich unter uns
 * aendern kann.
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

    /** Neueste zuerst. */
    fun sessions(): List<SessionEntry> = state.sessions.sortedByDescending { it.startedAtEpochMillis }

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

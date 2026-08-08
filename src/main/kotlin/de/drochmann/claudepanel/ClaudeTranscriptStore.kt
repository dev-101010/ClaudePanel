package de.drochmann.claudepanel

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Keeps the rendered transcript per session, so a resumed conversation does not start in
 * an empty window.
 *
 * Deliberately an **own** record rather than `~/.claude/projects/<path>/<uuid>.jsonl`:
 * that format is undocumented and can change with any update (see CLAUDE.md). It was also
 * measured that `--resume` does not replay the history and that the control protocol has
 * no request for it - so there is no documented route. The price of this decision: only
 * sessions that ran through this panel have a transcript. Ones started in a terminal stay
 * empty.
 *
 * Stored under the IDE's system directory, not in the project - a transcript is working
 * state and has no business in version control.
 */
class ClaudeTranscriptStore(project: Project) {

    private val root: Path =
        Path.of(PathManager.getSystemPath(), "claude-panel", project.locationHash)

    /** For resuming; `null` when nothing exists for this session. */
    fun load(sessionId: String): String? {
        val file = fileFor(sessionId) ?: return null
        if (!Files.exists(file)) return null
        return runCatching {
            val text = Files.readString(file)
            // Very long transcripts would slow the text area down - the interesting part
            // is at the end, so cut from the front. At a line boundary, otherwise half a
            // style name would be left over.
            if (text.length <= MAX_LOADED_CHARS) text
            else text.takeLast(MAX_LOADED_CHARS).substringAfter('\n')
        }.onFailure { LOG.warn("Cannot read transcript: $file", it) }.getOrNull()
    }

    /**
     * Rewrites the whole transcript rather than appending lines.
     *
     * Necessary because a tool line changes its colour **after the fact**, once the result
     * arrives - appended lines could never reflect that. Called at a few points (end of a
     * turn, permission answer, process end), not for every line.
     */
    fun save(sessionId: String, text: String) {
        val file = fileFor(sessionId) ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(
                file, text,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }.onFailure { LOG.warn("Cannot write transcript: $file", it) }
    }

    fun delete(sessionId: String) {
        val file = fileFor(sessionId) ?: return
        runCatching { Files.deleteIfExists(file) }
            .onFailure { LOG.warn("Cannot delete transcript: $file", it) }
    }

    /**
     * The session id comes from the event stream, so from outside. It becomes a file name -
     * hence it is checked rather than trusted.
     */
    private fun fileFor(sessionId: String): Path? {
        if (!SAFE_ID.matches(sessionId)) {
            LOG.warn("Unexpected session id, no transcript: $sessionId")
            return null
        }
        return root.resolve("$sessionId.txt")
    }

    companion object {
        private val LOG = logger<ClaudeTranscriptStore>()
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_LOADED_CHARS = 200_000
    }
}

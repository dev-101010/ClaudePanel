package de.drochmann.claudepanel

import com.google.gson.JsonObject

/**
 * A permission request from the CLI, waiting to be answered.
 *
 * Built from a `control_request` with `subtype: "can_use_tool"`. While it is unanswered
 * the session stands still - the CLI waits.
 */
data class PermissionRequest(
    val requestId: String,
    /** Ties the request to the tool call line that was already written. */
    val toolUseId: String?,
    val toolName: String,
    /** A short title the CLI already condensed, e.g. the file name. May be empty. */
    val description: String,
    /** The tool input; handed back unchanged when allowing. */
    val input: JsonObject?,
)

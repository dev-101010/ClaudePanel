# Claude Panel

A JetBrains IDE plugin that runs [Claude Code](https://claude.com/claude-code) as a
background process and renders the conversation in a docked tool window — no terminal
emulator, no TUI.

## Why not just use the terminal?

Claude Code ships an official JetBrains plugin and several third-party GUI wrappers. This
one exists for a narrower reason: it is a small, auditable amount of code that you own. It
never touches your credentials — authentication stays entirely inside the `claude` CLI —
and it drives the CLI through its documented JSON streaming interface rather than
scraping internal state.

## How it works

The plugin spawns:

```
claude --print \
       --output-format stream-json \
       --input-format stream-json \
       --include-partial-messages \
       --permission-mode <mode> \
       [--model <model>] [--resume <session-id>]
```

stdout carries one JSON event per line, which the panel parses and renders. Messages you
type go back over stdin in the same format.

## Layout

| Area | Contents |
|---|---|
| Top | Recent sessions; selecting one resumes it via `--resume` |
| Center | Conversation transcript |
| Bottom | Input field, permission-mode selector, model field, start/stop |

## Session list

Claude Code has no command that lists sessions in machine-readable form — `--resume`
without an ID opens an interactive picker, and `claude project` only offers `purge`. The
internal `~/.claude/projects/**.jsonl` files could be parsed, but that format is
undocumented and can change without notice.

So the plugin keeps its own index instead: the event stream reports each `session_id`, and
those are stored in the project's own state (`claude-panel.xml`). Nothing depends on
Anthropic's internal layout, and resuming always goes through the supported
`--resume <id>` flag.

## Requirements

- A JetBrains IDE build 262 or newer (2026.2+)
- The `claude` CLI on your `PATH`, already authenticated

## Building

```bash
./gradlew buildPlugin   # build/distributions/ClaudePanel-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew verifyPlugin  # JetBrains Plugin Verifier
```

By default the build compiles against the local IntelliJ IDEA installation configured as
`platformLocalPath` in `gradle.properties`, which avoids downloading the distribution.
Comment that property out to fetch `platformVersion` from the network instead.

## Status

Early. Known gaps:

- The exact `stream-json` event schema is not publicly documented. Known event types are
  rendered; anything unrecognized is printed raw rather than dropped, so nothing is lost
  silently — but the rendering will need refinement against real output.
- Permission prompts (`--permission-mode manual`) arrive on the same stream and are not
  answered yet. Use `acceptEdits` or `plan` until that is implemented.
- No syntax highlighting or diff rendering for tool output.

## License

[MIT](LICENSE) © Dennis Drochmann

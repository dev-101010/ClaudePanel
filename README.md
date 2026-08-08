# Claude Panel

A JetBrains IDE plugin that runs [Claude Code](https://claude.com/claude-code) as a
background process and renders the conversation in a docked tool window — no terminal
emulator, no TUI.

> Unofficial and not affiliated with Anthropic, who publish their own JetBrains plugin
> separately.

## Why not just use the terminal?

Claude Code ships an official JetBrains plugin and several third-party GUI wrappers. This
one exists for a narrower reason: it is a small, auditable amount of code that you own.
It never touches your credentials — signing in stays entirely inside the `claude` CLI —
and it drives the CLI over its streaming JSON interface rather than scraping internal
state.

## How it works

The plugin spawns:

```
claude --print \
       --verbose \
       --output-format stream-json \
       --input-format stream-json \
       --permission-mode <mode> \
       --permission-prompt-tool stdio \
       [--model <model>] [--resume <session-id>]
```

stdout carries one JSON event per line, which the panel parses and renders. Messages you
type go back over stdin in the same format, and the process stays alive across turns, so
the session id does not change.

`--verbose` is not optional: without it the CLI refuses `stream-json` outright.

## Layout

| Area | Contents |
|---|---|
| Top | Session dropdown and an options menu |
| Center | Conversation transcript |
| Bottom | Input with an interrupt button, permission mode, model, usage ring |

There is no start button — the first message starts a session, and picking an existing one
resumes it straight away.

## Permissions

`--permission-mode manual` does not prompt when run headless; it refuses outright and
reports the refusal to the model. To ask instead of refuse, the plugin registers itself
over the CLI's control protocol, which turns each request into an Allow/Deny pair right
below the tool call it belongs to.

**The flag this needs, `--permission-prompt-tool`, is not in `claude --help`.** It is
accepted all the same, but it is undocumented and could go away. Every failure mode was
built to end in "no permission" rather than a stuck session: an unanswered request is
denied after five minutes, an unrecognised control request is answered with an error so
the CLI does not wait forever, and if the flag ever stops working, no requests arrive and
the panel behaves as it did before.

## Sessions and transcripts

Claude Code has no command that lists sessions in machine-readable form — `--resume`
without an id opens an interactive picker, and `claude project` only offers `purge`. The
internal `~/.claude/projects/**.jsonl` files could be parsed, but that format is
undocumented and can change without notice.

So the plugin keeps its own index: the event stream reports each `session_id`, and those
are stored in the project's own state. Resuming always goes through the supported
`--resume <id>` flag.

`--resume` does not replay the conversation, and no request exists to fetch it, so the
panel also records what it rendered — one file per session under the IDE's system
directory, outside the project and outside version control.

## Requirements

- A JetBrains IDE build 262 or newer (2026.2+)
- The `claude` CLI on your `PATH` and a Claude subscription

The CLI is located through the login shell's `PATH`, which is what makes this work when
the IDE is started from Finder or a launcher. If it lives somewhere else, set the path
from the options menu.

## Building

```bash
./gradlew buildPlugin   # build/distributions/ClaudePanel-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew verifyPlugin  # JetBrains Plugin Verifier
```

`runIde` opens `sandbox/`, a gitignored throwaway project, so testing does not have Claude
edit the plugin's own source. It also enables a record of the CLI traffic under the
sandbox's `system/log/claude-panel.log`; that record is off in normal builds, since it
contains the conversation in clear text.

The build downloads the platform by default. To compile against a local IntelliJ IDEA
installation instead and skip the download, set `platformLocalPath` in your global
`~/.gradle/gradle.properties`.

## Status

Working, but young. Known gaps:

- The `stream-json` event schema is not publicly documented. It was measured against CLI
  2.1.225; known events are rendered and anything unrecognised is printed raw rather than
  dropped, so nothing is lost silently.
- Limit percentages exist only as prose in `/usage`, so the usage ring parses text and
  fails quiet. `rate_limit_event` is structured but carries no percentage and arrives once
  per process.
- No syntax highlighting or diff rendering for tool output.
- No token-by-token streaming; answers appear when a block completes.

## License

[MIT](LICENSE) © Dennis Drochmann

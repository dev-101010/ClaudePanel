# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-08-13

### Added

- Effort level as a third dropdown, so a session can be started with `--effort` without
  leaving the panel.
- The weekly limit in the usage gauge: the ring now shows the five-hour window alone, and
  the dot in its centre carries the colour of the week. Before, the ring silently switched
  to whichever window was higher, so a drop could mean a reset or merely a change of
  window.

### Changed

- Mode, model and effort are filled from what the CLI reports rather than from a list kept
  in the plugin, which aged with every new model. The model field can no longer be typed
  into: free text mostly produced names the CLI rejects at start.
- The empty entry reads "(as in the CLI)" rather than showing an empty field, which looked
  like a fault instead of a choice. What actually applies is in the tooltip - "Model: Opus
  5". Nothing is pinned by this: no flag is passed, and a later change to the CLI's own
  setting shows up at the next open.
- Changing mode, model or effort now takes effect: the next message restarts the CLI with
  the new flags and resumes the same session. Before, the field showed `sonnet` while Opus
  went on answering, because these are start-up flags a running process never learns about.
- A stored choice survives a CLI that no longer offers it: the field falls back, the
  setting stays, and the choice returns when the name does.
- The captions "Mode:" and "Model:" gave way to tooltips. In a narrow tool window they took
  a third of the row, and the values say what they are.
- Installs on Android Studio as well: the minimum platform is 261 rather than 262. Verified
  against Android Studio 261.26222 and IntelliJ IDEA 262.8665, both "Compatible".

## [0.1.0] - 2026-08-08

First release.

### Added

- Tool window that runs `claude` headless over its JSON streaming protocol and renders
  the conversation natively, without a terminal emulator.
- Sessions in a dropdown, backed by the plugin's own index rather than Claude Code's
  undocumented internal files. Resuming goes through the supported `--resume` flag.
- Transcripts kept per session, so a resumed conversation opens with its history rather
  than an empty window. `--resume` does not replay it, and nothing else offers it.
- Permission requests answered in the panel, with Allow and Deny directly below the tool
  call they belong to. A request that goes unanswered is denied after five minutes rather
  than leaving the session stuck.
- Interrupting a running answer without ending the session — the next message continues
  in the same one.
- Tool calls rendered as one line each, changing colour with their outcome. Output of a
  successful call is left out: it is raw material for the model, not for a reader.
- Permission mode and model remembered per project, and a configurable path for a CLI
  that is not on PATH.
- Sign-in started from the panel. The CLI opens the browser and stores the result itself;
  the plugin sees neither code nor token.
- Current usage as a ring that fills, with the figures on hover. The query costs no
  tokens.

### Notes

- Requires the `claude` CLI and a Claude subscription. Credentials are never handled by
  the plugin.
- Unofficial and not affiliated with Anthropic.

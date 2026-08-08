# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

# Claude Panel — Projektkontext

Dieses Dokument hält fest, was beim Aufsetzen des Projekts entschieden und **verifiziert**
wurde, damit es nicht neu hergeleitet werden muss. Stand: 2026-08-08, Commit `bb2b75e`.

## Was das Projekt ist

Ein JetBrains-Plugin, das **Claude Code als Hintergrundprozess** startet und die
Unterhaltung nativ in einem angedockten Tool Window rendert — **kein Terminal-Emulator,
keine TUI**.

## Warum es existiert

Es gibt bereits das offizielle Plugin (`com.anthropic.code.plugin`, Anthropic PBC, ~4,5 Mio
Downloads) und Dritt-Wrapper mit GUI (`CC GUI`, `Claude Code with GUI`). Zwei Gründe für den
Eigenbau:

1. Beim offiziellen Plugin läuft Claude in der Console, nicht als eigenes Fenster.
2. Dritt-Plugins mit KI-Zugriff sehen den kompletten Quellcode und potenziell Credentials —
   dem Vertrauensmodell fehlt die Grundlage.

Daraus folgt eine harte Anforderung: **Das Plugin fasst niemals Credentials an.** Die
Authentifizierung bleibt vollständig in der `claude`-CLI. Der eigene Code bleibt klein und
prüfbar. Wer diese Eigenschaft aufweicht, hebelt den einzigen Grund für das Projekt aus.

## Architektur — und warum genau diese

Zwei Wege wurden erwogen:

- **Terminal-Widget** (`TerminalToolWindowManager.createShellWidget` → `TerminalWidget`,
  eingebettet per `getComponent()`). Technisch validiert und lauffähig, aber die
  Oberfläche *ist* dann das Terminal. Passt nicht zur Anforderung „richtiges Fenster mit
  Session-Liste oben und Mode-Leiste unten". **Verworfen.**
- **Headless über das JSON-Protokoll.** Gewählt. Volle Kontrolle über die Darstellung, und
  die Schnittstelle ist dokumentiert.

Gestartet wird:

```
claude --print
       --output-format stream-json
       --input-format stream-json
       --include-partial-messages
       --permission-mode <mode>
       [--model <model>] [--resume <session-id>]
```

stdout = ein JSON-Objekt pro Zeile. stdin = Nachrichten im selben Format.

## Verifizierte Fakten (nicht erneut nachschlagen)

Alles hier wurde am 2026-08-08 gegen die installierten Werkzeuge geprüft, nicht aus dem
Gedächtnis geschrieben.

**CLI-Optionen** (aus `claude --help`):

| Option | Bedeutung |
|---|---|
| `-p, --print` | nicht-interaktiv, kein TTY nötig |
| `--output-format` | `text` \| `json` \| `stream-json` (nur mit `--print`) |
| `--input-format` | `text` \| `stream-json` (nur mit `--print`) |
| `--include-partial-messages` | Token-Chunks (nur mit `--print` + `stream-json`) |
| `--include-hook-events` | Hook-Lifecycle im Strom |
| `--permission-mode` | `acceptEdits`, `auto`, `bypassPermissions`, `manual`, `dontAsk`, `plan` |
| `--resume [value]` | ID **oder** interaktiver Picker; funktioniert mit `-p` |
| `--json-schema`, `--max-budget-usd` | strukturierte Ausgabe, Kostendeckel |

**Es gibt keinen Befehl, der Sessions maschinenlesbar auflistet.** Geprüft:
`--resume` ohne ID öffnet einen interaktiven TUI-Picker (schreibt kein Ergebnis auf
stdout); `claude project` kennt nur `purge`; kein `sessions`-Subcommand. Deshalb der
eigene Index — siehe unten.

**Plattform / Toolchain:**

- Gson ist in der Plattform gebündelt (`lib/intellij.libraries.gson.jar`) — **keine
  Extra-Abhängigkeit hinzufügen**, das gäbe Classloader-Konflikte.
- Kotlin 2.4.10 ist die neueste stabile Version. **Kotlin und Java müssen dasselbe
  JVM-Ziel haben** (beide 25) — sonst bricht `compileKotlin` mit
  „Inconsistent JVM-target compatibility" ab.
- **IntelliJ IDEA Community Edition ist eingestellt.** Seit 2025.3 gibt es eine unified
  distribution; `IIC` steht seit 2025-12-08 auf Build 253, während `IIU`/WS/PS/PY auf 262
  sind. Nicht `intellijIdeaCommunity(...)` verwenden — das nagelt auf die alte Plattform.
  Korrekt ist `intellijIdea(...)` oder die lokale Installation.
- Lokale IDEA: `C:\Users\denni\AppData\Local\Programs\IntelliJ IDEA`, Build `IU-262.8665.337`
  (= 2026.2.0.1). Der Build nutzt sie über `platformLocalPath` in `gradle.properties` und
  spart damit ~2 GB Download.

## Bewusste Entscheidung: eigener Session-Index

`ClaudeSessionIndex` (ein `PersistentStateComponent`, Datei `claude-panel.xml`) speichert
die `session_id`s, die der Ereignisstrom ohnehin meldet.

**Nicht** aus `~/.claude/projects/<kodierter-Pfad>/<uuid>.jsonl` lesen. Diese Dateien
existieren zwar und enthalten `sessionId`, `mode` und `permissionMode`, aber das Format ist
undokumentiert und kann sich mit jedem Update ändern. Die Trennung ist Absicht:

- **Auflisten** = eigener Index → nichts kann unter uns wegbrechen.
- **Fortsetzen** = `--resume <id>` → dokumentierte, stabile Option.

Wer die Liste „verbessern" will, indem er die internen Dateien parst, tauscht Robustheit
gegen Kosmetik. Vorher fragen.

## Offene Punkte (nach Priorität)

1. **Das Ereignis-Schema von `stream-json` ist nicht öffentlich dokumentiert.** Die Flags
   sind verifiziert, die Feldnamen nicht. `ClaudePanel.handleEvent` rendert `system`,
   `assistant` und `result` und gibt **alles Unbekannte roh aus statt es zu verschlucken**.
   Nächster Schritt: `./gradlew runIde`, echten Strom ansehen, Rendering daran ausrichten.
   Die Annahmen stehen als Kommentar an der Methode.
2. **Berechtigungsanfragen** bei `--permission-mode manual` kommen über denselben Strom und
   werden noch nicht beantwortet. Bis dahin `acceptEdits` oder `plan`.
3. Der Knopf „Session wählen…" ist ein Platzhalter — der eingebaute Picker ist interaktiv
   und braucht ein TTY, das es hier bewusst nicht gibt.
4. Keine Syntaxhervorhebung, keine Diff-Darstellung für Tool-Ausgaben.

## Fallstricke, die schon aufgetreten sind

- **`apply`-Block-Shadowing:** In `GeneralCommandLine(...).apply { setWorkDirectory(x) }`
  verweist ein Bezeichner wie `workingDirectory` auf die gleichnamige Property von
  `GeneralCommandLine` (Typ `Path`), nicht auf den eigenen Konstruktor-Parameter. Der
  Parameter heißt deshalb `workDir`.
- **Prozessausgabe kommt in Häppchen, nicht zeilenweise.** `ProcessListener.onTextAvailable`
  liefert beliebige Fragmente — `ClaudeProcess.consumeStdout` puffert und zerlegt selbst an
  Zeilenumbrüchen. Nicht auf ganze Zeilen verlassen.

## Konventionen (projektübergreifend)

- Namensraum `de.drochmann.*` — Reverse-DNS einer Domain, die Dennis gehört. Die Plugin-ID
  ist nach dem ersten Marketplace-Upload **unveränderlich**.
- MIT-Lizenz, Code öffentlich auf GitHub.
- Marketplace-Releases laufen als **hidden**: nicht gelistet, nur über Direktlink
  installierbar. Hidden-Plugins durchlaufen trotzdem das normale Approval, und
  **Unhiding ist irreversibel** — nie ungefragt vorschlagen.
- Secrets nie im Repo: Build liest Env-Var, sonst Gradle-Property aus der **globalen**
  `~/.gradle/gradle.properties` (`marketplaceToken`, `signingPassword`, …). Marketplace-Token
  haben das Präfix `perm-` (Bindestrich), nicht `perm:` wie in der älteren Hub-Doku.

## Verwandtes Projekt

`C:\Users\denni\WebstormProjects\RecentFilesToolWindow` — dasselbe Gradle-Setup, dieselbe
Publishing-Kette, bereits als hidden release beim Marketplace eingereicht. Bei Fragen zu
Build-Struktur, Signing oder Upload dort nachsehen.

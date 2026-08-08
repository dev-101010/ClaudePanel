# Claude Panel — Projektkontext

Dieses Dokument hält fest, was beim Aufsetzen des Projekts entschieden und **verifiziert**
wurde, damit es nicht neu hergeleitet werden muss. Stand: 2026-08-08, Arbeitsstand
oberhalb von Commit `db024ac` (Freigabedialog, Verlaufs-Mitschnitt, Protokoll und
Einstellungen sind gebaut, aber noch nicht committet).

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
       --verbose
       --output-format stream-json
       --input-format stream-json
       --permission-mode <mode>
       [--model <model>] [--resume <session-id>]
```

stdout = ein JSON-Objekt pro Zeile. stdin = Nachrichten im selben Format.

**`--verbose` ist Pflicht**, sonst bricht die CLI sofort ab:
`When using --print, --output-format=stream-json requires --verbose`.

Der Prozess bleibt **über mehrere Turns am Leben**, solange stdin offen ist; die
`session_id` bleibt dabei dieselbe. Nachgemessen mit zwei aufeinanderfolgenden
Nachrichten über einen Prozess.

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
| `--session-id <uuid>` | eigene Session-ID vorgeben statt sie zu erfahren |
| `--fork-session` | beim Fortsetzen eine neue ID vergeben |
| `--replay-user-messages` | eigene Eingaben zur Bestätigung zurückspiegeln |
| `--permission-prompt-tool` | **nicht in `--help`**, wird aber angenommen — siehe offener Punkt 1 |

**Das Ereignis-Schema** (gemessen gegen CLI **2.1.225** am 2026-08-08, Mitschnitte
existieren nicht mehr — bei Zweifeln neu aufnehmen):

| Ereignis | Inhalt |
|---|---|
| `system` / `init` | `session_id`, `tools`, `model`, `permissionMode`, `cwd` — **einmal pro Turn**, nicht pro Sitzung |
| `system` / `status` | `status: "requesting"` — Rauschen |
| `system` / `thinking_tokens` | Token-Zähler, mehrfach pro Turn — Rauschen |
| `system` / `permission_denied` | `tool_name`, `tool_use_id`, `message` |
| `assistant` | `message.content[]` mit `text`, `tool_use` oder `thinking` |
| `user` | `message.content[]` mit `tool_result` (`is_error`), dazu `tool_use_result` |
| `rate_limit_event` | `rate_limit_info` |
| `stream_event` | echte Anthropic-Deltas, `event.delta.text` bei `content_block_delta` |
| `result` / `success` | `result`, `total_cost_usd` (**Zahl**), `usage`, `duration_ms` |

Zwei Fallen darin: der `thinking`-Block trägt eine kilobytelange `signature`, die nicht
in den Verlauf gehört, und `result.result` wiederholt nur den letzten Assistententext —
beides würde die Ansicht doppeln bzw. fluten.

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

## Bewusste Entscheidung: eigener Verlaufs-Mitschnitt

**Es gibt keinen Weg, einen alten Verlauf über die Schnittstelle zu laden.** Am
2026-08-08 alle vier Möglichkeiten durchprobiert:

| Versuch | Ergebnis |
|---|---|
| `--resume` liefert den Verlauf mit | nein — nur `init` + neue Antwort (4 Ereignisse) |
| Control-Protokoll `get_history` / `history` / `list_messages` | `Unsupported control request subtype` |
| `claude project` | kennt nur `purge` |
| Export-Subcommand | existiert nicht |

Deshalb schreibt `ClaudeTranscriptStore` mit, **was das Panel selbst rendert** — eine
Datei je Session unter `PathManager.getSystemPath()/claude-panel/<locationHash>/`, also
außerhalb des Projekts und damit außerhalb der Versionsverwaltung.

Der Preis ist bekannt und gewollt: nur Sessions, die durch das Panel liefen, haben einen
Verlauf. Im Terminal gestartete lassen sich fortsetzen, beginnen aber leer. Die
vollständige Alternative wäre das Parsen von `~/.claude/projects/<pfad>/<uuid>.jsonl`
gewesen — dagegen spricht dasselbe Argument wie beim Session-Index unten.

Zwei Feinheiten, die beim Bauen nötig waren: Bei einer **neuen** Session steht die ID erst
mit `init` fest, bis dahin sammelt ein Puffer die Zeilen und schreibt sie nach. Und die
Session-ID wird zum Dateinamen, kommt aber von außen — sie wird gegen
`[A-Za-z0-9_-]{1,64}` geprüft, statt ihr zu vertrauen.

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

1. **Berechtigungsdialog — gebaut, aber auf undokumentiertem Protokoll.**

   Ohne Zutun verweigert die CLI headless still: `system/permission_denied`, dann ein
   `tool_result` mit `is_error: true` ans Modell. Der Modus `manual` heißt dann faktisch
   „nein zu allem".

   **Mit `--permission-prompt-tool stdio` fragt sie hingegen nach** und wartet auf die
   Antwort. Beide Richtungen am 2026-08-08 gegen 2.1.225 verifiziert — bei `allow` wurde
   die Datei wirklich angelegt, bei `deny` nicht. Der Ablauf:

   ```
   -> stdin   {"type":"control_request","request_id":"init-1",
               "request":{"subtype":"initialize","hooks":null}}
   <- stdout  {"type":"control_response","response":{"subtype":"success", ...}}

   <- stdout  {"type":"control_request","request_id":"<uuid>",
               "request":{"subtype":"can_use_tool","tool_name":"Write",
                          "display_name":"Write","input":{...},
                          "description":"kontrolle.txt","tool_use_id":"toolu_…",
                          "permission_suggestions":[
                             {"type":"setMode","mode":"acceptEdits","destination":"session"}]}}
   -> stdin   {"type":"control_response","response":{"subtype":"success",
               "request_id":"<uuid>",
               "response":{"behavior":"allow","updatedInput":{...}}}}
             bzw. {"behavior":"deny","message":"…"}   // landet als tool_result is_error
   ```

   `permission_suggestions` ist die Vorlage für „nicht mehr fragen". `updatedInput` erlaubt,
   die Tool-Eingabe vor der Ausführung zu ändern.

   **Der Haken:** `--permission-prompt-tool` steht **nicht in `--help`** und das
   Control-Protokoll ist nirgends dokumentiert — die CLI nimmt das Flag nur kommentarlos an.
   Damit hinge ein Kernfeature an undokumentiertem Verhalten, also genau an dem Kriterium,
   mit dem oben das Parsen von `~/.claude/projects` abgelehnt wurde. Der Unterschied: dort
   ging es um Kosmetik mit dokumentierter Alternative, hier gibt es keine Alternative außer
   `--allowedTools` vorab (statisch, kein Dialog). Bewusst entscheiden, nicht nebenbei —
   und beim Bauen so kapseln, dass ein Wegbrechen nur den Dialog kostet, nicht die Sitzung.

   Kein Weg ist das Agent SDK: es gibt **kein JVM-SDK**, nur TypeScript und Python, und es
   spricht darunter genau dieses Protokoll. Ein Node-Sidecar kauft nichts.

   **So ist es abgesichert** (`ClaudeProcess.handleControlRequest`, `ClaudePanel.enqueuePermission`):
   Bleibt das `control_request` aus, verhält sich das Panel wie vorher — es fragt eben
   niemand. Kommt eine Anfrage, die wir nicht verstehen, wird sie sofort mit
   `subtype: "error"` beantwortet, damit die CLI nicht endlos wartet. Bleibt der Nutzer
   stumm, lehnt ein Timer nach fünf Minuten ab. Schlägt das Schreiben auf stdin fehl,
   wird geloggt statt geworfen. Der Ausfallmodus ist also immer „keine Freigabe", nie
   „hängende Sitzung".

   `permission_suggestions` (das „nicht mehr fragen") wird noch nicht ausgewertet.

   Am 2026-08-08 in der echten Oberfläche durchgespielt: Anfrage erscheint, Sitzung steht
   still, „Erlauben" führt die Aktion aus.

   **Abbrechen** geht über denselben Kanal und ist etwas anderes als Beenden:
   `{"type":"control_request","request":{"subtype":"interrupt"}}`. Verifiziert — die CLI
   quittiert mit `still_queued`, schickt ein `result` mit `subtype:
   "error_during_execution"` (ohne `result`-Feld, das Auslesen muss das aushalten), und
   nimmt danach weiter Nachrichten an: derselbe Prozess, dieselbe `session_id`. Der
   Stop-Knopf im Panel macht genau das und beendet die Sitzung *nicht*.
2. **Tokenweise Ausgabe fehlt.** `stream_event` liefert die Deltas, wird aber verworfen;
   `--include-partial-messages` ist deshalb bewusst **nicht** gesetzt. Wer das Streaming
   will, braucht beides zusammen — sonst nur Ereignislast ohne Nutzen.
3. Der Knopf „Session wählen…" ist ein Platzhalter — der eingebaute Picker ist interaktiv
   und braucht ein TTY, das es hier bewusst nicht gibt. `--session-id` wäre die Alternative:
   die ID selbst vergeben, statt sie aus `init` zu erfahren.
4. **Grenzwerte gibt es nur als Fließtext — `rate_limit_event` wäre die strukturierte
   Alternative, reicht aber noch nicht.** Am 2026-08-08 durchgemessen:

   | Quelle | strukturiert | Inhalt |
   |---|---|---|
   | `/usage` | nein, Prosa | die Prozentwerte, beide Fenster, kostenlos |
   | JSON-Hülle von `/usage` | ja | nur Token-Zähler des Aufrufs, alle 0 |
   | `/context` | nein, Markdown | Kontextfüllung, kostenlos |
   | `/insights` | HTML-Datei | **kostet Geld** (gemessen $1,54, `num_turns: 1`) |
   | `rate_limit_event` | **ja** | `status`, `resetsAt`, `rateLimitType`, `overageStatus`, `isUsingOverage` |
   | `result.usage` je Zug | ja | Token und `total_cost_usd` |

   `rate_limit_event` kommt **einmal pro Prozess**, nicht pro Zug (über sechs Mitschnitte
   geprüft, auch bei zwei Zügen nur eines), und nur für `rateLimitType: "five_hour"`.
   Es veraltet daher in einer langen Sitzung — beobachtet: `resetsAt` stand auf 13:00,
   während `/usage` bereits 17:59 nannte. Es enthält **keinen Prozentwert**, der Ring
   braucht also weiterhin `/usage`.

   In allen Proben stand `status: "allowed"`; der Wertebereich ist unbekannt. Deshalb
   wird das Ereignis vorerst nur mitgeschrieben (`ClaudePanel.recordRateLimit`, auf
   `info`-Ebene, damit es auch ohne eingeschalteten Mitschnitt anfällt). Sobald genug
   Beispiele vorliegen, wäre der sinnvolle Schritt eine **Warnung bei Statuswechsel** —
   nicht das Ersetzen der Prozentwerte.

   Nicht empfohlen: `~/.claude/stats-cache.json` und `~/.claude/usage-data/facets/*.json`
   enthalten lokal mehr (Tagesaktivität, Token je Modell, Sitzungsauswertungen), sind aber
   dieselbe Kategorie wie `~/.claude/projects` — undokumentiert. Und Tagesstatistik ist
   nicht die Grenzauslastung, um die es geht.
5. Keine Syntaxhervorhebung, keine Diff-Darstellung für Tool-Ausgaben.

## Bedienung — und warum sie so ist

- **Kein Start-Knopf.** Die erste Nachricht startet die Sitzung; eine im Dropdown
  gewählte *bestehende* Session startet dagegen sofort, weil es da nichts zu tippen gibt,
  bevor etwas passieren soll. „Neue Session" wartet, sonst liefe bei jedem Öffnen des
  Tool Windows ungefragt ein Prozess an.
- **`startedWith`** hält fest, womit der laufende Prozess gestartet wurde, und wird beim
  `init` auf die echte ID gehoben. Ohne das würde jede zweite Nachricht den Prozess neu
  starten, weil das Dropdown nach dem Aufzeichnen auf die neue Session zeigt.
- **`reloadSessions()` schirmt seine eigenen Auswahl-Ereignisse ab** — sonst startet das
  programmatische Setzen der Auswahl einen Prozess.
- **Der Stop-Knopf erscheint nur während eines laufenden Zuges** und bricht ihn ab, ohne
  die Sitzung zu beenden.
- **Mode und Modell werden projektbezogen gemerkt** (`ClaudePanelSettings`, eigene
  Komponente in derselben Datei wie der Session-Index). Projektbezogen, weil vom Projekt
  abhängt, welcher Berechtigungsmodus vertretbar ist. Gespeichert wird bei jeder
  Änderung, nicht erst beim Start.

## CLI finden, Anmeldung, Verbrauch

Drei Dinge, die das Plugin über die CLI hinaus tut — und wo jeweils die Grenze liegt.

**Finden** (`ClaudeCli`): Suche im PATH über `PathEnvironmentVariableUtil`, das über
`EnvironmentUtil` die Umgebung der **Anmelde-Shell** liest, nicht die des IDE-Prozesses.
Auf macOS und Linux ist das entscheidend — eine aus dem Finder gestartete IDE erbt sonst
nur `/usr/bin:/bin:/usr/sbin:/sbin`. Weil damit dasselbe PATH gilt wie im Terminal, deckt
diese eine Suche jeden Installationsweg ab, der dort funktioniert.

**Bewusst ohne Liste vermuteter Installationsorte** (Homebrew, nvm, npm-global, bun …):
sie veraltet mit jedem neuen Installationsweg, ließe sich nur auf der jeweiligen Plattform
prüfen und wäre neben dem PATH meist überflüssig. Findet die Suche nichts, folgt ein
Verweis auf <https://code.claude.com/docs/en/setup> — **kein** Installationsbefehl, das
Plugin installiert nichts. Für Sonderfälle gibt es den eintragbaren Pfad im Zahnradmenü.

Unter Windows heißt die Datei je nach Installationsweg `claude.exe` (nativ, WinGet) oder
`claude.cmd` (npm-Shim) — nach dem bloßen Namen zu suchen verfehlt beides. Und der Prozess
läuft mit `ParentEnvironmentType.CONSOLE`: die CLI zu *finden* genügt nicht, sie braucht
ihrerseits eine brauchbare Umgebung.

**Anmelden:** Das Plugin startet `claude auth login` und hört auf. Die CLI öffnet den
Browser, führt den OAuth-Fluss und legt das Ergebnis selbst ab — weder Code noch Token
laufen durch den Plugin-Code.

Zwei Regeln, die dabei nicht aufgeweicht werden dürfen:

- `claude auth status --json` antwortet auch mit E-Mail, Organisation und Abonnement.
  **Gelesen wird ausschließlich `loggedIn`.**
- Die **Ausgabe** des Anmeldevorgangs wird nicht angezeigt. Sie enthält den vollständigen
  Anmeldelink mit `state` und `code_challenge`, und der stünde über die Verlaufssicherung
  auf der Platte. Die Rückfallebene „Code einfügen" wird deshalb auch nicht bedient — das
  gehört in ein Terminal.

**Verbrauch:** `claude --print /usage`, als eigener kurzer Aufruf statt `/usage` in die
laufende Session — sonst stünde die Antwort in der Unterhaltung und im gespeicherten
Verlauf. Gemessen: **kostet nichts**, `num_turns: 0`, alle Token-Zähler 0, die CLI
beantwortet es lokal. Dauert aber rund **1,3 s** (Startzeit der CLI, nicht MCP —
`--strict-mcp-config` bringt 0,05 s). Deshalb wird vorab geladen und zwischengespeichert;
der Tooltip zeigt den Stand samt Uhrzeit, der Klick zeigt sofort den gespeicherten Wert
und frischt dahinter auf. Der Zwischenspeicher liegt **nur im Arbeitsspeicher**.

## Entwickeln und Testen

- `./gradlew runIde` öffnet **`sandbox/`** als Projekt, nicht dieses Repository — beim
  Testen soll Claude an Wegwerfdateien arbeiten, nicht am eigenen Quellcode. Der Ordner
  ist in `.gitignore` und wird von `prepareSandboxProject` erzeugt.
- Ohne Projektargument zeigt die Sandbox nur den Willkommensbildschirm, und Tool Windows
  gibt es erst mit offenem Projekt.
- `runIde` setzt `-Dclaudepanel.log=true`. Damit schreibt `ClaudePanelLog` nach
  `<sandbox>/system/log/claude-panel.log`: `>` an die CLI, `<` von ihr, `!` stderr, `ui`
  für Bedienschritte. **Standardmäßig aus** — der Mitschnitt enthält die Unterhaltung im
  Klartext und gehört nicht ungefragt auf die Platte eines Anwenders.

## Fallstricke, die schon aufgetreten sind

- **Windows sperrt die Plugin-JAR, solange die Sandbox läuft.** `prepareSandbox`
  scheitert dann mit „Der Vorgang ist bei einer Datei mit einem geöffneten Bereich…
  nicht anwendbar". Vor dem Bauen die Sandbox schließen.
- **`FlowLayout` in einem schmalen Tool Window schneidet ab, was nicht in die Reihe
  passt** — kommentarlos. So war der Start-Knopf unsichtbar und später die Angabe in der
  Freigabeleiste abgeschnitten. Was sichtbar bleiben muss, gehört in einen eigenen
  Bereich eines `BorderLayout`, nicht ans Ende einer Reihe.

- **`apply`-Block-Shadowing:** In `GeneralCommandLine(...).apply { setWorkDirectory(x) }`
  verweist ein Bezeichner wie `workingDirectory` auf die gleichnamige Property von
  `GeneralCommandLine` (Typ `Path`), nicht auf den eigenen Konstruktor-Parameter. Der
  Parameter heißt deshalb `workDir`.
- **`--verbose` vergessen.** Ohne das Flag startet der Prozess gar nicht, und die Ursache
  steht nur auf stderr. Deshalb wird stderr im Panel als `[stderr] …` markiert statt
  ununterscheidbar in den Verlauf zu laufen — sonst sucht man den Fehler im Rendering.
- **Prozessausgabe kommt in Häppchen, nicht zeilenweise.** `ProcessListener.onTextAvailable`
  liefert beliebige Fragmente — `ClaudeProcess.consumeStdout` puffert und zerlegt selbst an
  Zeilenumbrüchen. Nicht auf ganze Zeilen verlassen.

## Konventionen (projektübergreifend)

- **Sprache: Oberfläche und Code auf Englisch, dieses Dokument auf Deutsch.** Sichtbare
  Texte, KDoc und Kommentare sind englisch — üblich für Marketplace-Plugins, und es
  erspart die Umlaut-Umschreibungen, die im Deutschen sonst nötig wären. CLAUDE.md ist
  Arbeitsdokument, kein Teil der Veröffentlichung, und bleibt deutsch.
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

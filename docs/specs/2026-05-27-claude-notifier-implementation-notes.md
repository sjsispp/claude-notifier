# Claude Notifier — Plan A Implementation Notes

> Companion document to:
> - Spec: `docs/specs/2026-05-27-claude-notifier-design.md`
> - Plan A: `docs/plans/2026-05-27-plan-a-macos-app.md`
>
> Purpose: capture **actual implementation details** that emerged during Plan A so future readers and Plan B / Plan C implementers have ground truth (not just the pre-implementation design).

---

## 1. Plan A Status Summary

- **Date completed**: 2026-05-27
- **Total commits**: 16 (on `master`, range `692ba81..HEAD`)
- **Test coverage**: 25 unit + integration tests, all passing
- **Verified E2E**: HTTP server + dedupe + iTerm jump/approve (via mocked AppleScript runner)
- **Known limitations**:
  - IDEA adapter is a **stub** (always throws `AdapterError.pluginNotInstalled`) — Plan C will replace with a real HTTP client
  - CC hook auto-POST is **not yet wired** (waits for Plan B)

### Commit list (chronological)

| # | SHA | Subject |
|---|-----|---------|
| 01 | `23d9f1d` | feat(core): HookPayload codable with schema validation |
| 02 | `c9b60f5` | feat(core): NotificationStore with session-based dedupe |
| 03 | `cbdfe2a` | feat(core): RuntimeConfig port discovery + runtime.json writer |
| 04 | `6b9f8ff` | feat(server): HTTP endpoint accepts /hook POST and updates store |
| 05 | `e7d88cb` | feat(terminal): adapter protocol + Unknown/VSCode/Idea-stub impls |
| 06 | `1bd8f62` | feat(terminal): iTerm adapter with AppleScript jump + approve |
| 07 | `6be077e` | feat(terminal): TerminalRouter dispatch by host |
| 08 | `b229d75` | feat(ui): menubar agent + status item with badge |
| 09 | `a33cd77` | feat(ui): floating panel container with toggle |
| 10 | `25cda57` | feat(ui): notification row + list view with event styling |
| 11 | `d1c4788` | feat(ui): sound + auto-show on new event, auto-hide when empty |
| 12 | `8ec3184` | feat(util): LaunchAtLogin wrapper via SMAppService |
| 13 | `f10ad55` | feat(ui): preferences window + menubar context menu |
| 14 | `58f6e26` | feat(ui): wire approve/jump to router with error display |
| 15 | `e53d099` | docs: manual test checklist + README |
| 16 | *(this doc)* | docs: Plan A implementation notes |

---

## 2. Implementation Decisions That Deviated From Spec/Plan

### (a) Router lifetime — stored property on AppDelegate

The plan literally wrote `let router = ...` as a local variable inside `applicationDidFinishLaunching`. This silently broke at runtime because `FloatingPanelController.router` is declared `weak var` — the locally-scoped router would be deallocated as soon as `applicationDidFinishLaunching` returned, and any approve/jump action would no-op or crash.

**Fix**: declared a stored property on the AppDelegate:

```swift
private var router: TerminalRouter!
```

**Learning for Plan C**: When `IdeaAdapter` is rewritten as a real HTTP client (with its own URLSession / connection pool / healthcheck timer), its lifecycle must similarly be owned by a long-lived object. Do not instantiate it inside a temporary scope.

### (b) `NSApplicationDelegate` requires class `@main`, not SwiftUI `App` struct

The plan sketched `struct ClaudeNotifierApp: App` (SwiftUI App protocol). We had to switch to:

```swift
class ClaudeNotifierApp: NSObject, NSApplicationDelegate { ... }
```

with `@main` on the class. Reason: we need direct AppKit primitives — `NSPanel` (floating panel above all spaces), `NSStatusItem` (menubar agent), `LSUIElement=true` (no Dock icon) — that the SwiftUI `App` protocol cannot host cleanly as a menubar-only agent. SwiftUI `App` always wants a `Scene` / `WindowGroup`, which conflicts with the agent app shape.

### (c) AppKit ↔ SwiftUI bridging

We kept the views in SwiftUI but hosted them through AppKit shells:

- `FloatingPanelController` owns an `NSPanel` whose `contentView` is an `NSHostingView(rootView: FloatingPanelView)`
- `PreferencesView` is wrapped by `NSHostingController` inside an `NSWindow`

This split — AppKit for window/panel/status-item primitives, SwiftUI for view content — is the recommended modern pattern and worth preserving when Plan C-era refactors happen.

### (d) `swift test` requires full Xcode (not just Command Line Tools)

Discovered during local dev: `xcrun --find xctest` fails when only the Command Line Tools are installed; `swift test` then bails with a missing-XCTest error. Full Xcode.app is required.

**Action taken**: noted in README "App 开发" section as a prerequisite. Future contributors hitting `swift test` failures should check `xcode-select -p` points at `/Applications/Xcode.app/Contents/Developer`.

---

## 3. Actual HTTP API Surface (Ground Truth)

Implemented in `Sources/ClaudeNotifier/Server/HookEndpointServer.swift`.

### `POST /hook`

```
POST http://127.0.0.1:6789/hook
Content-Type: application/json
Body:   HookPayload JSON  (see spec §4 for schema)

Response 200 OK
Content-Type: application/json
{ "accepted": true, "event_id": "<uuid>" }

Response 400 Bad Request
Content-Type: application/json
{ "accepted": false, "error": "<reason>" }
```

Reasons returned in `error`: `invalid json`, `unsupported schema`, `missing required field: <name>`, etc.

### `GET /api/notifications` — NOT IMPLEMENTED

Plan A T16 manual checklist mentioned this endpoint. It was wishful — **the route does not exist**. Treat as "future enhancement" if a debugging/inspection HTTP surface is wanted; for now use the floating panel UI to inspect state.

### Port

Default `6789`; `RuntimeConfig` walks upward (6789 → 6790 → …) if the default is occupied, then writes the chosen port to `runtime.json` (see §4).

---

## 4. Runtime Files (Actual Paths and Schemas)

### `~/.config/claude-notifier/runtime.json`

Written by `RuntimeConfig` on every successful startup (after the HTTP server bind succeeds).

```json
{
  "host": "127.0.0.1",
  "port": 6789,
  "pid": 12345,
  "startedAt": 1748332800
}
```

| Field | Type | Notes |
|-------|------|-------|
| `host` | string | Currently always `127.0.0.1` |
| `port` | int | Actual bound port (may differ from 6789 if it was taken) |
| `pid` | int | App process id; useful for `kill` during dev |
| `startedAt` | int | Unix timestamp (seconds) |

**Important for Plan B**: the CC hook script **must read this file** to discover the current port. Do not hardcode `6789` — port walking means the real port can drift.

---

## 5. Adapter Capability Matrix (Actual)

| Host | Detection | Jump | Approve | Adapter file |
|------|-----------|------|---------|--------------|
| `iterm` | `TERM_PROGRAM=iTerm.app` | ✅ AppleScript by session id (`ITERM_SESSION_ID`) | ✅ AppleScript `write text "1"` | `Terminal/ITermAdapter.swift` |
| `idea` | `TERMINAL_EMULATOR=JetBrains-JediTerm` + `CLAUDE_IDEA_TAB_ID` env | ❌ throws `pluginNotInstalled` (stub) | ❌ throws `pluginNotInstalled` (stub) | `Terminal/IdeaAdapter.swift` (Plan C rewrites) |
| `vscode` | `TERM_PROGRAM=vscode` | ⚠️ best-effort: `open vscode://file<cwd>` | ❌ throws `AdapterError.unsupported` | `Terminal/VSCodeAdapter.swift` |
| `unknown` | fallback when none of the above match | ❌ `unsupported` | ❌ `unsupported` | `Terminal/UnknownAdapter.swift` |

Routing is performed by `Terminal/TerminalRouter.swift` based on the `terminal.host` field of the `HookPayload`.

`AppleScriptRunner` (`Terminal/AppleScriptRunner.swift`) is the single AppleScript-execution seam — easy to mock in tests, which is how the iTerm adapter is integration-tested.

---

## 6. Plan B / Plan C Integration Hints

### For Plan B (Claude Code plugin / hook script) implementer

- **Discover port**: read `~/.config/claude-notifier/runtime.json` → `port`. Do not hardcode.
- **Schema version**: current is `schema=1`. The server rejects mismatched schemas with `400 unsupported schema`. Always set `schema: 1` in the payload until coordinated bump.
- **Env vars to capture** (the host detection relies on these reaching the payload):
  - `TERM_PROGRAM`
  - `ITERM_SESSION_ID`
  - `TERMINAL_EMULATOR`
  - `CLAUDE_IDEA_TAB_ID`
  - `TTY=$(tty)`
  - `PPID`
- **Last prompt cache**: respect the existing pre-Plan-A convention from `notify-on-stop.sh` — cache at `/tmp/claude-prompt-${session_id}` so the App can echo it in the notification row.
- **Hook script must not block CC**:
  - Use `curl --max-time 2`
  - Always `exit 0`, even on curl failure / non-2xx response
- **Fallback path**: if the App is unreachable (no `runtime.json`, or curl fails), fall back to `terminal-notifier` when installed (silent skip otherwise). The plan-B hook should never surface errors to the user during a CC session.

### For Plan C (IDEA plugin) implementer

- **App side stub to replace**: `Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift` currently throws `AdapterError.pluginNotInstalled`. Plan C must rewrite it into an HTTP client that:
  - `POST http://127.0.0.1:6790/sendText` for approve (body includes tab id + text `"1\n"`)
  - `POST http://127.0.0.1:6790/focusTab` for jump (body includes tab id)
  - Healthcheck path **TBD** — Plan C should specify, suggested `GET /healthz` returning `200 OK`
- **Tab id source**: the plugin must read tab UUID from `item.ideaTabId` (which is populated from hook payload `terminal.idea_tab_id`).
- **Env injection**: the IDEA plugin's `TerminalEnvCustomizer` must inject `CLAUDE_IDEA_TAB_ID=<uuid>` whenever a new terminal tab spawns, so the hook script can pick it up and put it into the payload.
- **Lifecycle reminder**: see §2(a) — when the new HTTP-based `IdeaAdapter` lands, ensure its owning object (likely `TerminalRouter` or a new `IdeaClient`) is held by a stored property on the AppDelegate, not by a local variable.

---

*End of implementation notes.*

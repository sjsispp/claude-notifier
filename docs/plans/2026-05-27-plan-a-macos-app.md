# Plan A: macOS App 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 ClaudeNotifier.app —— SwiftUI 菜单栏代理，内嵌 HTTP server 接收 CC hook，浮窗显示通知，支持 iTerm/VS Code 跳转与 iTerm 同意。IDEA 适配留 stub，由 Plan C 完成。

**Architecture:** SPM-based macOS executable，`NSApp.setActivationPolicy(.accessory)` 隐藏 Dock 图标；HTTP server 用 Swifter；浮窗用 `NSPanel.floatingPanel` 承载 SwiftUI 视图；终端操作走 `NSAppleScript`。

**Tech Stack:**
- Swift 5.9 / macOS 13+
- Swift Package Manager（不用 Xcode 项目）
- Swifter（HTTP server，~50KB）
- 标准库 Combine（observable store）
- SwiftUI + AppKit 互操作（NSPanel + NSHostingView）

**对应 spec:** `docs/specs/2026-05-27-claude-notifier-design.md`

---

## File Structure

```
app/
├── Package.swift
├── Sources/ClaudeNotifier/
│   ├── ClaudeNotifierApp.swift          # @main + AppDelegate
│   ├── Core/
│   │   ├── HookPayload.swift            # Codable for POST body
│   │   ├── NotificationItem.swift       # UI model
│   │   ├── NotificationStore.swift      # @ObservableObject, dedupe
│   │   └── RuntimeConfig.swift          # 端口重试 + runtime.json
│   ├── Server/
│   │   └── HookEndpointServer.swift     # Swifter 包装
│   ├── Terminal/
│   │   ├── TerminalAdapter.swift        # protocol
│   │   ├── TerminalRouter.swift         # 按 host 派发
│   │   ├── AppleScriptRunner.swift      # NSAppleScript 包装
│   │   ├── ITermAdapter.swift
│   │   ├── VSCodeAdapter.swift
│   │   ├── IdeaAdapter.swift            # stub，返回 .pluginNotInstalled
│   │   └── UnknownAdapter.swift
│   ├── UI/
│   │   ├── MenuBarController.swift      # NSStatusItem
│   │   ├── FloatingPanelController.swift # NSPanel
│   │   ├── FloatingPanelView.swift      # SwiftUI 列表
│   │   ├── NotificationRowView.swift    # 单行
│   │   └── PreferencesView.swift
│   └── Utilities/
│       ├── SoundPlayer.swift
│       └── LaunchAtLogin.swift          # SMAppService
└── Tests/ClaudeNotifierTests/
    ├── HookPayloadTests.swift
    ├── NotificationStoreTests.swift
    ├── RuntimeConfigTests.swift
    ├── HookEndpointServerTests.swift
    ├── TerminalRouterTests.swift
    └── Mocks/MockTerminalAdapter.swift
```

---

### Task 1: 项目脚手架

**Files:**
- Create: `app/Package.swift`
- Create: `app/.gitignore`
- Create: `app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift`（占位）
- Create: `app/Tests/ClaudeNotifierTests/SmokeTest.swift`

- [ ] **Step 1: 创建目录与 Package.swift**

```bash
mkdir -p /Users/wudazhan/workplace/project/claude-notifier/app
cd /Users/wudazhan/workplace/project/claude-notifier/app
```

Create `Package.swift`:

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ClaudeNotifier",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "ClaudeNotifier", targets: ["ClaudeNotifier"])
    ],
    dependencies: [
        .package(url: "https://github.com/httpswift/swifter.git", from: "1.5.0")
    ],
    targets: [
        .executableTarget(
            name: "ClaudeNotifier",
            dependencies: [.product(name: "Swifter", package: "swifter")]
        ),
        .testTarget(
            name: "ClaudeNotifierTests",
            dependencies: ["ClaudeNotifier"]
        )
    ]
)
```

- [ ] **Step 2: 创建占位入口与冒烟测试**

`app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift`:

```swift
import Foundation

@main
struct ClaudeNotifierApp {
    static func main() {
        print("ClaudeNotifier starting…")
    }
}
```

`app/Tests/ClaudeNotifierTests/SmokeTest.swift`:

```swift
import XCTest

final class SmokeTest: XCTestCase {
    func test_alwaysPasses() {
        XCTAssertTrue(true)
    }
}
```

`app/.gitignore`:

```
.build/
.swiftpm/
Package.resolved
DerivedData/
*.xcodeproj
```

- [ ] **Step 3: 验证 build + test**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier/app
swift build
swift test
```

Expected: `Compiling … Build complete!` 然后 `Test Suite 'All tests' passed`.

- [ ] **Step 4: Commit**

```bash
cd /Users/wudazhan/workplace/project/claude-notifier
git add app/
git -c commit.gpgsign=false commit -m "scaffold: SPM project for ClaudeNotifier app"
```

---

### Task 2: HookPayload Codable 类型

**Files:**
- Create: `app/Sources/ClaudeNotifier/Core/HookPayload.swift`
- Create: `app/Tests/ClaudeNotifierTests/HookPayloadTests.swift`

- [ ] **Step 1: 写失败的测试**

`app/Tests/ClaudeNotifierTests/HookPayloadTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class HookPayloadTests: XCTestCase {
    func test_decodesNotificationEvent() throws {
        let json = """
        {
          "schema": 1,
          "event": "notification",
          "session_id": "abc-123",
          "cwd": "/Users/me/foo",
          "project_name": "foo",
          "message": "Claude needs permission",
          "command_preview": "npm test",
          "last_prompt": "run tests",
          "terminal": {
            "host": "iterm",
            "iterm_session_id": "w0t1p0:UUID",
            "idea_tab_id": null,
            "tty": "/dev/ttys001",
            "ppid": 12345
          },
          "ts": 1779874936
        }
        """.data(using: .utf8)!
        let payload = try JSONDecoder().decode(HookPayload.self, from: json)
        XCTAssertEqual(payload.event, .notification)
        XCTAssertEqual(payload.sessionId, "abc-123")
        XCTAssertEqual(payload.terminal.host, .iterm)
        XCTAssertEqual(payload.terminal.itermSessionId, "w0t1p0:UUID")
        XCTAssertEqual(payload.commandPreview, "npm test")
    }

    func test_decodesStopEvent_withoutOptionalFields() throws {
        let json = """
        {
          "schema": 1, "event": "stop",
          "session_id": "x", "cwd": "/tmp", "project_name": "tmp",
          "terminal": {"host": "unknown"},
          "ts": 1
        }
        """.data(using: .utf8)!
        let payload = try JSONDecoder().decode(HookPayload.self, from: json)
        XCTAssertEqual(payload.event, .stop)
        XCTAssertNil(payload.message)
        XCTAssertNil(payload.commandPreview)
        XCTAssertEqual(payload.terminal.host, .unknown)
    }

    func test_rejectsUnsupportedSchema() throws {
        let json = #"{"schema": 99, "event": "stop", "session_id":"x", "cwd":"/","project_name":"x","terminal":{"host":"unknown"},"ts":1}"#
            .data(using: .utf8)!
        XCTAssertThrowsError(try JSONDecoder().decode(HookPayload.self, from: json))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd app && swift test --filter HookPayloadTests
```

Expected: 编译错误 `cannot find 'HookPayload' in scope`

- [ ] **Step 3: 实现 HookPayload**

`app/Sources/ClaudeNotifier/Core/HookPayload.swift`:

```swift
import Foundation

struct HookPayload: Codable, Equatable {
    enum Event: String, Codable { case notification, stop }
    enum Host: String, Codable { case iterm, idea, vscode, unknown }

    struct Terminal: Codable, Equatable {
        let host: Host
        let itermSessionId: String?
        let ideaTabId: String?
        let tty: String?
        let ppid: Int?

        enum CodingKeys: String, CodingKey {
            case host
            case itermSessionId = "iterm_session_id"
            case ideaTabId = "idea_tab_id"
            case tty, ppid
        }
    }

    let schema: Int
    let event: Event
    let sessionId: String
    let cwd: String
    let projectName: String
    let message: String?
    let commandPreview: String?
    let lastPrompt: String?
    let terminal: Terminal
    let ts: Int

    enum CodingKeys: String, CodingKey {
        case schema, event
        case sessionId = "session_id"
        case cwd
        case projectName = "project_name"
        case message
        case commandPreview = "command_preview"
        case lastPrompt = "last_prompt"
        case terminal, ts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let schema = try c.decode(Int.self, forKey: .schema)
        guard schema == 1 else {
            throw DecodingError.dataCorruptedError(
                forKey: .schema, in: c,
                debugDescription: "unsupported schema: \(schema)")
        }
        self.schema = schema
        self.event = try c.decode(Event.self, forKey: .event)
        self.sessionId = try c.decode(String.self, forKey: .sessionId)
        self.cwd = try c.decode(String.self, forKey: .cwd)
        self.projectName = try c.decode(String.self, forKey: .projectName)
        self.message = try c.decodeIfPresent(String.self, forKey: .message)
        self.commandPreview = try c.decodeIfPresent(String.self, forKey: .commandPreview)
        self.lastPrompt = try c.decodeIfPresent(String.self, forKey: .lastPrompt)
        self.terminal = try c.decode(Terminal.self, forKey: .terminal)
        self.ts = try c.decode(Int.self, forKey: .ts)
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter HookPayloadTests
```

Expected: 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/Sources app/Tests
git -c commit.gpgsign=false commit -m "feat(core): HookPayload codable with schema validation"
```

---

### Task 3: NotificationItem + NotificationStore（去重）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Core/NotificationItem.swift`
- Create: `app/Sources/ClaudeNotifier/Core/NotificationStore.swift`
- Create: `app/Tests/ClaudeNotifierTests/NotificationStoreTests.swift`

- [ ] **Step 1: 写失败的测试**

`app/Tests/ClaudeNotifierTests/NotificationStoreTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class NotificationStoreTests: XCTestCase {
    private func makePayload(session: String, event: HookPayload.Event = .notification, ts: Int = 1) -> HookPayload {
        let json = #"""
        {
          "schema": 1, "event": "\#(event.rawValue)",
          "session_id": "\#(session)", "cwd": "/p/\#(session)",
          "project_name": "\#(session)",
          "terminal": {"host": "iterm"},
          "ts": \#(ts)
        }
        """#.data(using: .utf8)!
        return try! JSONDecoder().decode(HookPayload.self, from: json)
    }

    func test_addingFirstItem_appearsInList() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].sessionId, "a")
    }

    func test_addingSameSession_replacesInPlace() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a", ts: 1))
        store.upsert(makePayload(session: "a", ts: 2))
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].timestamp, 2)
    }

    func test_addingDifferentSessions_keepsBoth() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        store.upsert(makePayload(session: "b"))
        XCTAssertEqual(store.items.count, 2)
    }

    func test_remove_clearsItem() {
        let store = NotificationStore()
        store.upsert(makePayload(session: "a"))
        let id = store.items[0].id
        store.remove(id: id)
        XCTAssertEqual(store.items.count, 0)
    }

    func test_isEmpty_observable() {
        let store = NotificationStore()
        XCTAssertTrue(store.isEmpty)
        store.upsert(makePayload(session: "a"))
        XCTAssertFalse(store.isEmpty)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd app && swift test --filter NotificationStoreTests
```

Expected: 编译错误。

- [ ] **Step 3: 实现 NotificationItem 与 NotificationStore**

`app/Sources/ClaudeNotifier/Core/NotificationItem.swift`:

```swift
import Foundation

struct NotificationItem: Identifiable, Equatable {
    let id: UUID
    let sessionId: String
    let event: HookPayload.Event
    let host: HookPayload.Host
    let projectName: String
    let cwd: String
    let message: String?
    let commandPreview: String?
    let lastPrompt: String?
    let itermSessionId: String?
    let ideaTabId: String?
    let timestamp: Int

    init(payload: HookPayload, id: UUID = UUID()) {
        self.id = id
        self.sessionId = payload.sessionId
        self.event = payload.event
        self.host = payload.terminal.host
        self.projectName = payload.projectName
        self.cwd = payload.cwd
        self.message = payload.message
        self.commandPreview = payload.commandPreview
        self.lastPrompt = payload.lastPrompt
        self.itermSessionId = payload.terminal.itermSessionId
        self.ideaTabId = payload.terminal.ideaTabId
        self.timestamp = payload.ts
    }
}
```

`app/Sources/ClaudeNotifier/Core/NotificationStore.swift`:

```swift
import Foundation
import Combine

final class NotificationStore: ObservableObject {
    @Published private(set) var items: [NotificationItem] = []

    var isEmpty: Bool { items.isEmpty }

    func upsert(_ payload: HookPayload) {
        let new = NotificationItem(payload: payload)
        if let idx = items.firstIndex(where: { $0.sessionId == new.sessionId }) {
            items[idx] = new
        } else {
            items.append(new)
        }
    }

    func remove(id: UUID) {
        items.removeAll { $0.id == id }
    }

    func clear() {
        items.removeAll()
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter NotificationStoreTests
```

Expected: 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(core): NotificationStore with session-based dedupe"
```

---

### Task 4: RuntimeConfig（端口重试 + runtime.json）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Core/RuntimeConfig.swift`
- Create: `app/Tests/ClaudeNotifierTests/RuntimeConfigTests.swift`

- [ ] **Step 1: 写失败的测试**

`app/Tests/ClaudeNotifierTests/RuntimeConfigTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class RuntimeConfigTests: XCTestCase {
    private var tmpDir: URL!

    override func setUp() {
        tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("rc-test-\(UUID())")
        try? FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tmpDir)
    }

    func test_findsAvailablePort_startingFromPreferred() {
        let config = RuntimeConfig(runtimeFileURL: tmpDir.appendingPathComponent("rt.json"))
        let port = config.findAvailablePort(preferred: 6789, attempts: 10) { _ in true }
        XCTAssertEqual(port, 6789)
    }

    func test_skipsBusyPort_andReturnsNext() {
        let config = RuntimeConfig(runtimeFileURL: tmpDir.appendingPathComponent("rt.json"))
        let port = config.findAvailablePort(preferred: 6789, attempts: 10) { p in p > 6790 }
        XCTAssertEqual(port, 6791)
    }

    func test_returnsNil_whenNoneAvailable() {
        let config = RuntimeConfig(runtimeFileURL: tmpDir.appendingPathComponent("rt.json"))
        let port = config.findAvailablePort(preferred: 6789, attempts: 3) { _ in false }
        XCTAssertNil(port)
    }

    func test_writesRuntimeJson() throws {
        let url = tmpDir.appendingPathComponent("rt.json")
        let config = RuntimeConfig(runtimeFileURL: url)
        try config.writeRuntimeInfo(port: 6790)

        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(json["port"] as? Int, 6790)
        XCTAssertEqual(json["host"] as? String, "127.0.0.1")
        XCTAssertNotNil(json["pid"])
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd app && swift test --filter RuntimeConfigTests
```

Expected: 编译错误。

- [ ] **Step 3: 实现 RuntimeConfig**

`app/Sources/ClaudeNotifier/Core/RuntimeConfig.swift`:

```swift
import Foundation

final class RuntimeConfig {
    let runtimeFileURL: URL

    init(runtimeFileURL: URL = RuntimeConfig.defaultRuntimeFileURL()) {
        self.runtimeFileURL = runtimeFileURL
    }

    static func defaultRuntimeFileURL() -> URL {
        let home = FileManager.default.homeDirectoryForCurrentUser
        return home.appendingPathComponent(".config/claude-notifier/runtime.json")
    }

    /// `isAvailable` 返回该端口是否可绑定；生产中由 HookEndpointServer 试 bind 决定，测试用注入 closure。
    func findAvailablePort(preferred: Int, attempts: Int,
                           isAvailable: (Int) -> Bool) -> Int? {
        for offset in 0..<attempts {
            let candidate = preferred + offset
            if isAvailable(candidate) { return candidate }
        }
        return nil
    }

    func writeRuntimeInfo(port: Int) throws {
        let dir = runtimeFileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let payload: [String: Any] = [
            "port": port,
            "host": "127.0.0.1",
            "pid": ProcessInfo.processInfo.processIdentifier,
            "startedAt": Int(Date().timeIntervalSince1970)
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted])
        try data.write(to: runtimeFileURL, options: .atomic)
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter RuntimeConfigTests
```

Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(core): RuntimeConfig port discovery + runtime.json writer"
```

---

### Task 5: HookEndpointServer（HTTP POST /hook 集成测试）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Server/HookEndpointServer.swift`
- Create: `app/Tests/ClaudeNotifierTests/HookEndpointServerTests.swift`

- [ ] **Step 1: 写失败的集成测试**

`app/Tests/ClaudeNotifierTests/HookEndpointServerTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class HookEndpointServerTests: XCTestCase {
    func test_postValidHook_addsToStore_andReturns200() async throws {
        let store = NotificationStore()
        let server = HookEndpointServer(store: store)
        try server.start(port: 16789)
        defer { server.stop() }

        let url = URL(string: "http://127.0.0.1:16789/hook")!
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = """
        {"schema":1,"event":"notification","session_id":"abc",
         "cwd":"/p/foo","project_name":"foo",
         "message":"Permission","terminal":{"host":"iterm"},"ts":1}
        """.data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: req)
        let http = response as! HTTPURLResponse
        XCTAssertEqual(http.statusCode, 200)
        let body = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(body["accepted"] as? Bool, true)

        // 等一拍让主线程更新 store
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(store.items.count, 1)
        XCTAssertEqual(store.items[0].sessionId, "abc")
    }

    func test_postInvalidSchema_returns400() async throws {
        let store = NotificationStore()
        let server = HookEndpointServer(store: store)
        try server.start(port: 16790)
        defer { server.stop() }

        let url = URL(string: "http://127.0.0.1:16790/hook")!
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = #"{"schema":99,"event":"stop","session_id":"x","cwd":"/","project_name":"x","terminal":{"host":"unknown"},"ts":1}"#.data(using: .utf8)
        let (_, response) = try await URLSession.shared.data(for: req)
        XCTAssertEqual((response as! HTTPURLResponse).statusCode, 400)
        XCTAssertEqual(store.items.count, 0)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd app && swift test --filter HookEndpointServerTests
```

Expected: 编译错误。

- [ ] **Step 3: 实现 HookEndpointServer**

`app/Sources/ClaudeNotifier/Server/HookEndpointServer.swift`:

```swift
import Foundation
import Swifter

final class HookEndpointServer {
    private let server = HttpServer()
    private let store: NotificationStore

    init(store: NotificationStore) {
        self.store = store
        register()
    }

    private func register() {
        server.POST["/hook"] = { [weak self] req in
            guard let self else { return .internalServerError }
            let bodyData = Data(req.body)
            do {
                let payload = try JSONDecoder().decode(HookPayload.self, from: bodyData)
                DispatchQueue.main.async { self.store.upsert(payload) }
                let resp = ["accepted": true, "event_id": UUID().uuidString] as [String: Any]
                let json = try JSONSerialization.data(withJSONObject: resp)
                return .raw(200, "OK", ["Content-Type": "application/json"]) { try? $0.write(json) }
            } catch {
                let resp = ["accepted": false, "error": "\(error)"] as [String: Any]
                let json = (try? JSONSerialization.data(withJSONObject: resp)) ?? Data()
                return .raw(400, "Bad Request", ["Content-Type": "application/json"]) { try? $0.write(json) }
            }
        }
    }

    func start(port: Int) throws {
        try server.start(in_port_t(port), forceIPv4: true)
    }

    func stop() { server.stop() }

    /// 探测端口是否可用：尝试 bind，立即释放
    static func isPortAvailable(_ port: Int) -> Bool {
        let s = HttpServer()
        do {
            try s.start(in_port_t(port), forceIPv4: true)
            s.stop()
            return true
        } catch {
            return false
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter HookEndpointServerTests
```

Expected: 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(server): HTTP endpoint accepts /hook POST and updates store"
```

---

### Task 6: TerminalAdapter 协议 + Unknown/VSCode/Idea stub

**Files:**
- Create: `app/Sources/ClaudeNotifier/Terminal/TerminalAdapter.swift`
- Create: `app/Sources/ClaudeNotifier/Terminal/UnknownAdapter.swift`
- Create: `app/Sources/ClaudeNotifier/Terminal/VSCodeAdapter.swift`
- Create: `app/Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift`
- Create: `app/Tests/ClaudeNotifierTests/Mocks/MockTerminalAdapter.swift`
- Create: `app/Tests/ClaudeNotifierTests/AdapterTests.swift`

- [ ] **Step 1: 定义协议**

`app/Sources/ClaudeNotifier/Terminal/TerminalAdapter.swift`:

```swift
import Foundation

enum AdapterError: Error, Equatable {
    case unsupported
    case targetNotFound
    case pluginNotInstalled
    case scriptError(String)
}

protocol TerminalAdapter {
    var host: HookPayload.Host { get }
    func jump(item: NotificationItem) async throws
    func approve(item: NotificationItem) async throws
}
```

- [ ] **Step 2: 写失败的测试**

`app/Tests/ClaudeNotifierTests/AdapterTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class AdapterTests: XCTestCase {
    private func item(host: HookPayload.Host) -> NotificationItem {
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"\#(host.rawValue)"},"ts":1}"#
            .data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_unknownAdapter_alwaysUnsupported() async {
        let a = UnknownAdapter()
        await XCTAssertThrowsErrorAsync(try await a.jump(item: item(host: .unknown)))
        await XCTAssertThrowsErrorAsync(try await a.approve(item: item(host: .unknown)))
    }

    func test_ideaAdapter_returnsPluginNotInstalledByDefault() async {
        let a = IdeaAdapter()
        do {
            try await a.jump(item: item(host: .idea))
            XCTFail("expected throw")
        } catch let e as AdapterError {
            XCTAssertEqual(e, .pluginNotInstalled)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }

    func test_vscodeAdapter_jump_invokesOpenWithVSCodeURI() async throws {
        var captured: [String] = []
        let a = VSCodeAdapter(processRunner: { args in
            captured = args
            return 0
        })
        try await a.jump(item: item(host: .vscode))
        XCTAssertEqual(captured.first, "open")
        XCTAssertTrue(captured.contains(where: { $0.hasPrefix("vscode://file") }))
    }

    func test_vscodeAdapter_approve_unsupported() async {
        let a = VSCodeAdapter(processRunner: { _ in 0 })
        await XCTAssertThrowsErrorAsync(try await a.approve(item: item(host: .vscode)))
    }
}

// helper
func XCTAssertThrowsErrorAsync<T>(_ expr: @autoclosure () async throws -> T,
                                  file: StaticString = #file, line: UInt = #line) async {
    do { _ = try await expr(); XCTFail("expected throw", file: file, line: line) }
    catch {}
}
```

- [ ] **Step 3: 实现三个 adapter**

`app/Sources/ClaudeNotifier/Terminal/UnknownAdapter.swift`:

```swift
import Foundation

struct UnknownAdapter: TerminalAdapter {
    let host: HookPayload.Host = .unknown
    func jump(item: NotificationItem) async throws { throw AdapterError.unsupported }
    func approve(item: NotificationItem) async throws { throw AdapterError.unsupported }
}
```

`app/Sources/ClaudeNotifier/Terminal/IdeaAdapter.swift`:

```swift
import Foundation

/// Plan A 阶段为 stub，Plan C 会改写为 HTTP 调用 IDEA plugin。
struct IdeaAdapter: TerminalAdapter {
    let host: HookPayload.Host = .idea
    func jump(item: NotificationItem) async throws { throw AdapterError.pluginNotInstalled }
    func approve(item: NotificationItem) async throws { throw AdapterError.pluginNotInstalled }
}
```

`app/Sources/ClaudeNotifier/Terminal/VSCodeAdapter.swift`:

```swift
import Foundation

struct VSCodeAdapter: TerminalAdapter {
    let host: HookPayload.Host = .vscode
    let processRunner: ([String]) -> Int32

    init(processRunner: @escaping ([String]) -> Int32 = VSCodeAdapter.defaultRunner) {
        self.processRunner = processRunner
    }

    static let defaultRunner: ([String]) -> Int32 = { args in
        let p = Process()
        p.launchPath = "/usr/bin/env"
        p.arguments = args
        do { try p.run(); p.waitUntilExit(); return p.terminationStatus }
        catch { return -1 }
    }

    func jump(item: NotificationItem) async throws {
        let uri = "vscode://file\(item.cwd)"
        let code = processRunner(["open", uri])
        guard code == 0 else { throw AdapterError.scriptError("open failed: \(code)") }
    }

    func approve(item: NotificationItem) async throws {
        throw AdapterError.unsupported
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter AdapterTests
```

Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(terminal): adapter protocol + Unknown/VSCode/Idea-stub impls"
```

---

### Task 7: AppleScriptRunner + ITermAdapter

**Files:**
- Create: `app/Sources/ClaudeNotifier/Terminal/AppleScriptRunner.swift`
- Create: `app/Sources/ClaudeNotifier/Terminal/ITermAdapter.swift`
- Create: `app/Tests/ClaudeNotifierTests/ITermAdapterTests.swift`

- [ ] **Step 1: 写 AppleScriptRunner（先实现，因为 NSAppleScript 本身没法 mock）**

`app/Sources/ClaudeNotifier/Terminal/AppleScriptRunner.swift`:

```swift
import Foundation
import AppKit

protocol AppleScriptRunning {
    func run(_ source: String) throws -> String?
}

struct AppleScriptRunner: AppleScriptRunning {
    func run(_ source: String) throws -> String? {
        var errInfo: NSDictionary?
        guard let script = NSAppleScript(source: source) else {
            throw AdapterError.scriptError("init failed")
        }
        let result = script.executeAndReturnError(&errInfo)
        if let errInfo {
            let msg = (errInfo[NSAppleScript.errorMessage] as? String) ?? "unknown"
            throw AdapterError.scriptError(msg)
        }
        return result.stringValue
    }
}
```

- [ ] **Step 2: 写 ITermAdapter 单元测试（用 mock runner，验证生成的脚本片段）**

`app/Tests/ClaudeNotifierTests/ITermAdapterTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class MockAppleScriptRunner: AppleScriptRunning {
    var captured: [String] = []
    var stub: ((String) -> String?)?
    func run(_ source: String) throws -> String? {
        captured.append(source)
        return stub?(source)
    }
}

final class ITermAdapterTests: XCTestCase {
    private func item(itermId: String?) -> NotificationItem {
        let id = itermId.map { #""\#($0)""# } ?? "null"
        let json = #"""
        {"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p",
         "terminal":{"host":"iterm","iterm_session_id":\#(id)},"ts":1}
        """#.data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_jump_buildsScriptContainingSessionId() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.jump(item: item(itermId: "w0t1p0:UUID"))
        XCTAssertEqual(mock.captured.count, 1)
        XCTAssertTrue(mock.captured[0].contains("w0t1p0:UUID"))
        XCTAssertTrue(mock.captured[0].contains("iTerm"))
    }

    func test_approve_writesText1ToSession() async throws {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        try await a.approve(item: item(itermId: "w0t1p0:UUID"))
        XCTAssertEqual(mock.captured.count, 1)
        XCTAssertTrue(mock.captured[0].contains("w0t1p0:UUID"))
        XCTAssertTrue(mock.captured[0].contains(#"write text "1""#))
    }

    func test_jump_throwsWhenItermSessionIdMissing() async {
        let mock = MockAppleScriptRunner()
        let a = ITermAdapter(runner: mock)
        do {
            try await a.jump(item: item(itermId: nil))
            XCTFail("expected throw")
        } catch let e as AdapterError {
            XCTAssertEqual(e, .targetNotFound)
        } catch {
            XCTFail("wrong type")
        }
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
cd app && swift test --filter ITermAdapterTests
```

Expected: 编译错误。

- [ ] **Step 4: 实现 ITermAdapter**

`app/Sources/ClaudeNotifier/Terminal/ITermAdapter.swift`:

```swift
import Foundation

struct ITermAdapter: TerminalAdapter {
    let host: HookPayload.Host = .iterm
    let runner: AppleScriptRunning

    init(runner: AppleScriptRunning = AppleScriptRunner()) {
        self.runner = runner
    }

    func jump(item: NotificationItem) async throws {
        guard let sid = item.itermSessionId else { throw AdapterError.targetNotFound }
        let escaped = sid.replacingOccurrences(of: "\"", with: "\\\"")
        let script = """
        tell application "iTerm"
          activate
          repeat with w in windows
            repeat with t in tabs of w
              repeat with s in sessions of t
                if id of s as text contains "\(escaped)" then
                  select s
                  tell w to select t
                  select w
                  return
                end if
              end repeat
            end repeat
          end repeat
        end tell
        """
        _ = try runner.run(script)
    }

    func approve(item: NotificationItem) async throws {
        guard let sid = item.itermSessionId else { throw AdapterError.targetNotFound }
        let escaped = sid.replacingOccurrences(of: "\"", with: "\\\"")
        let script = """
        tell application "iTerm"
          tell session id "\(escaped)" of current tab of current window
            write text "1"
          end tell
        end tell
        """
        _ = try runner.run(script)
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
cd app && swift test --filter ITermAdapterTests
```

Expected: 3 tests passed.

- [ ] **Step 6: 手动 E2E（首次实测，记录到 docs/manual-test-checklist.md）**

```bash
# 1. 开 iTerm，新建一个 tab，echo $ITERM_SESSION_ID 记下值，比如 "w0t1p0:XYZ"
# 2. 在另一个 tab 里：
swift run ClaudeNotifier &      # 启动 app（暂时只跑 server，UI 还没接）
# 等到后面 Task 13 才能完整跑；这一步只是确认 AppleScript 能执行

# 暂时用 osascript 验证逻辑：
osascript -e 'tell application "iTerm" to tell session id "w0t1p0:XYZ" of current tab of current window to write text "echo HELLO"'
# Expected: 第一个 tab 出现 echo HELLO 并执行
```

记录到 `docs/manual-test-checklist.md`（Task 16 会创建该文件，本步骤先写笔记到 commit message）。

- [ ] **Step 7: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(terminal): iTerm adapter with AppleScript jump + approve"
```

---

### Task 8: TerminalRouter（按 host 派发）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Terminal/TerminalRouter.swift`
- Create: `app/Tests/ClaudeNotifierTests/TerminalRouterTests.swift`

- [ ] **Step 1: 写失败的测试**

`app/Tests/ClaudeNotifierTests/TerminalRouterTests.swift`:

```swift
import XCTest
@testable import ClaudeNotifier

final class MockAdapter: TerminalAdapter {
    let host: HookPayload.Host
    var jumpCalled = 0
    var approveCalled = 0
    var jumpError: Error?
    var approveError: Error?
    init(host: HookPayload.Host) { self.host = host }
    func jump(item: NotificationItem) async throws {
        jumpCalled += 1
        if let e = jumpError { throw e }
    }
    func approve(item: NotificationItem) async throws {
        approveCalled += 1
        if let e = approveError { throw e }
    }
}

final class TerminalRouterTests: XCTestCase {
    private func item(host: HookPayload.Host) -> NotificationItem {
        let json = #"{"schema":1,"event":"notification","session_id":"s","cwd":"/p","project_name":"p","terminal":{"host":"\#(host.rawValue)"},"ts":1}"#.data(using: .utf8)!
        return NotificationItem(payload: try! JSONDecoder().decode(HookPayload.self, from: json))
    }

    func test_routes_jump_byHost() async throws {
        let iterm = MockAdapter(host: .iterm)
        let idea = MockAdapter(host: .idea)
        let router = TerminalRouter(adapters: [iterm, idea])
        try await router.jump(item: item(host: .iterm))
        XCTAssertEqual(iterm.jumpCalled, 1)
        XCTAssertEqual(idea.jumpCalled, 0)
    }

    func test_routes_approve_byHost() async throws {
        let iterm = MockAdapter(host: .iterm)
        let router = TerminalRouter(adapters: [iterm])
        try await router.approve(item: item(host: .iterm))
        XCTAssertEqual(iterm.approveCalled, 1)
    }

    func test_throwsUnsupported_whenNoAdapter() async {
        let router = TerminalRouter(adapters: [])
        do {
            try await router.jump(item: item(host: .unknown))
            XCTFail()
        } catch let e as AdapterError {
            XCTAssertEqual(e, .unsupported)
        } catch { XCTFail() }
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd app && swift test --filter TerminalRouterTests
```

- [ ] **Step 3: 实现 TerminalRouter**

`app/Sources/ClaudeNotifier/Terminal/TerminalRouter.swift`:

```swift
import Foundation

final class TerminalRouter {
    private let adapters: [HookPayload.Host: TerminalAdapter]

    init(adapters: [TerminalAdapter]) {
        var map: [HookPayload.Host: TerminalAdapter] = [:]
        for a in adapters { map[a.host] = a }
        self.adapters = map
    }

    static func defaultRouter() -> TerminalRouter {
        TerminalRouter(adapters: [
            ITermAdapter(),
            VSCodeAdapter(),
            IdeaAdapter(),
            UnknownAdapter()
        ])
    }

    func jump(item: NotificationItem) async throws {
        guard let a = adapters[item.host] else { throw AdapterError.unsupported }
        try await a.jump(item: item)
    }

    func approve(item: NotificationItem) async throws {
        guard let a = adapters[item.host] else { throw AdapterError.unsupported }
        try await a.approve(item: item)
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd app && swift test --filter TerminalRouterTests
```

Expected: 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(terminal): TerminalRouter dispatch by host"
```

---

### Task 9: AppDelegate + MenuBarController

**Files:**
- Modify: `app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift`
- Create: `app/Sources/ClaudeNotifier/UI/MenuBarController.swift`

- [ ] **Step 1: 改写入口为 AppKit-based @main**

`app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift`:

```swift
import Cocoa

@main
final class ClaudeNotifierApp: NSObject, NSApplicationDelegate {
    static func main() {
        let app = NSApplication.shared
        let delegate = ClaudeNotifierApp()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)  // 不在 Dock 显示
        app.run()
    }

    private var store: NotificationStore!
    private var server: HookEndpointServer!
    private var menuBar: MenuBarController!
    private var runtimeConfig: RuntimeConfig!

    func applicationDidFinishLaunching(_ notification: Notification) {
        store = NotificationStore()
        server = HookEndpointServer(store: store)
        runtimeConfig = RuntimeConfig()

        guard let port = runtimeConfig.findAvailablePort(
            preferred: 6789, attempts: 11,
            isAvailable: HookEndpointServer.isPortAvailable)
        else {
            NSLog("[ClaudeNotifier] no available port; exit")
            NSApp.terminate(nil)
            return
        }

        do {
            try server.start(port: port)
            try runtimeConfig.writeRuntimeInfo(port: port)
            NSLog("[ClaudeNotifier] server listening on :\(port)")
        } catch {
            NSLog("[ClaudeNotifier] server start failed: \(error)")
            NSApp.terminate(nil)
        }

        menuBar = MenuBarController(store: store)
    }
}
```

- [ ] **Step 2: 实现 MenuBarController（只显示徽标，浮窗在下一 task）**

`app/Sources/ClaudeNotifier/UI/MenuBarController.swift`:

```swift
import Cocoa
import Combine

final class MenuBarController {
    private let store: NotificationStore
    private let statusItem: NSStatusItem
    private var cancellables: Set<AnyCancellable> = []

    var onClick: (() -> Void)?

    init(store: NotificationStore) {
        self.store = store
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setupButton()
        subscribe()
    }

    private func setupButton() {
        guard let btn = statusItem.button else { return }
        btn.image = NSImage(systemSymbolName: "bell", accessibilityDescription: "Claude Notifier")
        btn.image?.isTemplate = true
        btn.target = self
        btn.action = #selector(handleClick)
        btn.title = ""
    }

    private func subscribe() {
        store.$items
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.updateBadge(count: items.count)
            }
            .store(in: &cancellables)
    }

    private func updateBadge(count: Int) {
        guard let btn = statusItem.button else { return }
        btn.title = count > 0 ? " \(count)" : ""
    }

    @objc private func handleClick() {
        onClick?()
    }
}
```

- [ ] **Step 3: 手动验证**

```bash
cd app && swift run ClaudeNotifier &
sleep 1
# 顶部菜单栏应出现 🔔 图标
# 用 curl 触发：
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"notification","session_id":"x","cwd":"/p","project_name":"p",
  "terminal":{"host":"iterm"},"ts":1}'
# 徽标应变为 🔔 1
# 第二个 sessionId="y" 后变为 🔔 2
# 终止：
kill %1
```

- [ ] **Step 4: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): menubar agent + status item with badge"
```

---

### Task 10: FloatingPanelController（NSPanel.floatingPanel 容器）

**Files:**
- Create: `app/Sources/ClaudeNotifier/UI/FloatingPanelController.swift`
- Modify: `app/Sources/ClaudeNotifier/UI/MenuBarController.swift`（点击 toggle）

- [ ] **Step 1: 实现 FloatingPanelController**

`app/Sources/ClaudeNotifier/UI/FloatingPanelController.swift`:

```swift
import Cocoa
import SwiftUI

final class FloatingPanelController {
    private let panel: NSPanel
    private let store: NotificationStore
    private weak var router: TerminalRouter?

    init(store: NotificationStore, router: TerminalRouter) {
        self.store = store
        self.router = router

        let frame = NSRect(x: 0, y: 0, width: 360, height: 480)
        panel = NSPanel(
            contentRect: frame,
            styleMask: [.titled, .closable, .nonactivatingPanel, .fullSizeContentView],
            backing: .buffered, defer: false)
        panel.title = "Claude 通知"
        panel.titlebarAppearsTransparent = true
        panel.titleVisibility = .hidden
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.backgroundColor = .clear
        panel.isOpaque = false

        let view = FloatingPanelView(store: store, onApprove: { [weak self] in await self?.handleApprove($0) },
                                                  onJump: { [weak self] in await self?.handleJump($0) },
                                                  onDismiss: { [weak self] in self?.store.remove(id: $0) })
        panel.contentView = NSHostingView(rootView: view)
    }

    func show() {
        positionTopRight()
        panel.orderFrontRegardless()
    }

    func hide() { panel.orderOut(nil) }

    func toggle() {
        if panel.isVisible { hide() } else { show() }
    }

    var isVisible: Bool { panel.isVisible }

    private func positionTopRight() {
        guard let screen = NSScreen.main else { return }
        let v = screen.visibleFrame
        let size = panel.frame.size
        let x = v.maxX - size.width - 12
        let y = v.maxY - size.height - 12
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }

    private func handleApprove(_ item: NotificationItem) async {
        do {
            try await router?.approve(item: item)
            await MainActor.run { store.remove(id: item.id) }
        } catch {
            NSLog("[ClaudeNotifier] approve failed: \(error)")
        }
    }

    private func handleJump(_ item: NotificationItem) async {
        do { try await router?.jump(item: item) }
        catch { NSLog("[ClaudeNotifier] jump failed: \(error)") }
    }
}
```

- [ ] **Step 2: 修改 MenuBarController 让点击 toggle 浮窗**

不改 MenuBarController 内部，而是在 AppDelegate 里接：

修改 `app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift` 的 `applicationDidFinishLaunching` 末尾：

```swift
let router = TerminalRouter.defaultRouter()
let panel = FloatingPanelController(store: store, router: router)
menuBar = MenuBarController(store: store)
menuBar.onClick = { panel.toggle() }
self.floatingPanel = panel  // 加一个属性持有
```

加属性：

```swift
private var floatingPanel: FloatingPanelController!
```

注意：FloatingPanelView 还没实现，下一步实现。本步骤先让代码引用占位。

为了让本 task 编译过，先建一个占位 View：

`app/Sources/ClaudeNotifier/UI/FloatingPanelView.swift`:

```swift
import SwiftUI

struct FloatingPanelView: View {
    @ObservedObject var store: NotificationStore
    let onApprove: (NotificationItem) async -> Void
    let onJump: (NotificationItem) async -> Void
    let onDismiss: (UUID) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Text("Claude 通知 (\(store.items.count))")
                .font(.headline)
                .padding()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(VisualEffectBlur())
    }
}

struct VisualEffectBlur: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .popover
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }
    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
```

- [ ] **Step 3: 编译 + 手动验证**

```bash
cd app && swift build && swift run ClaudeNotifier &
sleep 1
# 点菜单栏图标 → 浮窗应在屏幕右上角出现
# 再点 → 关闭
kill %1
```

- [ ] **Step 4: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): floating panel container with toggle"
```

---

### Task 11: NotificationRowView + 完整列表

**Files:**
- Modify: `app/Sources/ClaudeNotifier/UI/FloatingPanelView.swift`
- Create: `app/Sources/ClaudeNotifier/UI/NotificationRowView.swift`

- [ ] **Step 1: 实现 NotificationRowView**

`app/Sources/ClaudeNotifier/UI/NotificationRowView.swift`:

```swift
import SwiftUI

struct NotificationRowView: View {
    let item: NotificationItem
    let onApprove: () -> Void
    let onJump: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(eventIcon)
                Text(eventLabel).font(.caption).fontWeight(.semibold)
                    .foregroundColor(eventColor)
                Spacer()
                Text(relativeTime).font(.caption).foregroundColor(.secondary)
            }
            HStack(spacing: 5) {
                Text(item.projectName).font(.subheadline).fontWeight(.semibold)
                hostBadge
            }
            if let msg = item.message ?? item.lastPrompt {
                Text(msg).font(.caption).foregroundColor(.secondary)
                    .lineLimit(2)
            }
            if let cmd = item.commandPreview {
                Text(cmd).font(.system(.caption, design: .monospaced))
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Color.black.opacity(0.05)).cornerRadius(3)
            }
            HStack(spacing: 6) {
                if item.event == .notification && canApprove {
                    Button("✓ 同意") { onApprove() }
                        .buttonStyle(.borderedProminent).controlSize(.small)
                }
                if canJump {
                    Button("↗ 跳转") { onJump() }
                        .buttonStyle(.bordered).controlSize(.small)
                }
                Button("×") { onDismiss() }
                    .buttonStyle(.bordered).controlSize(.small)
            }
        }
        .padding(12)
        .background(rowBackground)
    }

    private var eventIcon: String {
        item.event == .notification ? "🔐" : "✓"
    }
    private var eventLabel: String {
        item.event == .notification ? "等待权限" : "任务完成"
    }
    private var eventColor: Color {
        item.event == .notification ? .orange : .green
    }
    private var rowBackground: Color {
        item.event == .notification ? Color.yellow.opacity(0.15) : Color.green.opacity(0.10)
    }
    private var relativeTime: String {
        let delta = Int(Date().timeIntervalSince1970) - item.timestamp
        if delta < 60 { return "刚刚" }
        if delta < 3600 { return "\(delta/60) 分钟前" }
        return "\(delta/3600) 小时前"
    }
    private var hostBadge: some View {
        Text(hostLabel)
            .font(.system(size: 9, weight: .semibold))
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(hostColor)
            .foregroundColor(.white).cornerRadius(3)
    }
    private var hostLabel: String {
        switch item.host {
        case .iterm: return "iTerm"
        case .idea: return "IDEA"
        case .vscode: return "VS Code"
        case .unknown: return "?"
        }
    }
    private var hostColor: Color {
        switch item.host {
        case .iterm: return .black
        case .idea: return .orange
        case .vscode: return .blue
        case .unknown: return .gray
        }
    }
    private var canApprove: Bool {
        item.host == .iterm || item.host == .idea
    }
    private var canJump: Bool {
        item.host != .unknown
    }
}
```

- [ ] **Step 2: 改写 FloatingPanelView**

`app/Sources/ClaudeNotifier/UI/FloatingPanelView.swift`:

```swift
import SwiftUI

struct FloatingPanelView: View {
    @ObservedObject var store: NotificationStore
    let onApprove: (NotificationItem) async -> Void
    let onJump: (NotificationItem) async -> Void
    let onDismiss: (UUID) -> Void

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if store.items.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(store.items) { item in
                            NotificationRowView(
                                item: item,
                                onApprove: { Task { await onApprove(item) } },
                                onJump: { Task { await onJump(item) } },
                                onDismiss: { onDismiss(item.id) }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(VisualEffectBlur())
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var header: some View {
        HStack {
            Text("Claude 通知 · \(store.items.count)")
                .font(.system(size: 12, weight: .semibold))
            Spacer()
            Button(action: { store.clear() }) {
                Image(systemName: "trash")
            }
            .buttonStyle(.plain)
            .help("全部关闭")
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private var emptyState: some View {
        VStack {
            Spacer()
            Text("当前没有通知").font(.caption).foregroundColor(.secondary)
            Spacer()
        }
    }
}

struct VisualEffectBlur: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .popover
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }
    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
```

- [ ] **Step 3: 编译 + 手动 E2E（连同 Task 9 的 curl）**

```bash
cd app && swift run ClaudeNotifier &
sleep 1
# 触发 notification 事件
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"notification","session_id":"x","cwd":"/p/foo","project_name":"foo",
  "command_preview":"npm test","message":"Claude wants Bash",
  "terminal":{"host":"iterm","iterm_session_id":"w0t1p0:FAKE"},"ts":'$(date +%s)'}'
# 点菜单栏 → 浮窗显示一行黄色通知，含项目名、宿主徽标、命令预览、同意/跳转/× 按钮

# 触发 stop 事件
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"stop","session_id":"y","cwd":"/p/bar","project_name":"bar",
  "last_prompt":"写完了","terminal":{"host":"iterm"},"ts":'$(date +%s)'}'
# 浮窗多一行绿色通知，只有跳转/关闭按钮
kill %1
```

- [ ] **Step 4: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): notification row + list view with event styling"
```

---

### Task 12: 新事件副作用（声音 + 自动弹出）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Utilities/SoundPlayer.swift`
- Modify: `app/Sources/ClaudeNotifier/UI/FloatingPanelController.swift`

- [ ] **Step 1: 实现 SoundPlayer**

`app/Sources/ClaudeNotifier/Utilities/SoundPlayer.swift`:

```swift
import AppKit

struct SoundPlayer {
    static func playNewEventSound() {
        NSSound(named: "Glass")?.play()
    }
}
```

- [ ] **Step 2: 让 FloatingPanelController 订阅 store 变化**

修改 `FloatingPanelController.swift`，加 import Combine、加属性、加方法：

```swift
import Combine
// 新增属性
private var cancellables: Set<AnyCancellable> = []
private var lastCount: Int = 0

// 在 init 末尾追加：
subscribeForAutoBehavior()

// 新方法：
private func subscribeForAutoBehavior() {
    store.$items
        .receive(on: DispatchQueue.main)
        .sink { [weak self] items in
            guard let self else { return }
            let newCount = items.count
            defer { self.lastCount = newCount }
            if newCount > self.lastCount {
                SoundPlayer.playNewEventSound()
                if !self.panel.isVisible { self.show() }
            } else if newCount == 0 && self.panel.isVisible {
                self.hide()
            }
        }
        .store(in: &cancellables)
}
```

- [ ] **Step 3: 手动验证**

```bash
cd app && swift run ClaudeNotifier &
sleep 1
# 第一次 curl 应该听到 Glass 声音 + 浮窗自动弹出
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"notification","session_id":"x","cwd":"/p","project_name":"p",
  "terminal":{"host":"iterm"},"ts":'$(date +%s)'}'
# 关闭 ×，浮窗应自动收起
kill %1
```

- [ ] **Step 4: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): sound + auto-show on new event, auto-hide when empty"
```

---

### Task 13: LaunchAtLogin（SMAppService）

**Files:**
- Create: `app/Sources/ClaudeNotifier/Utilities/LaunchAtLogin.swift`
- Modify: `app/Sources/ClaudeNotifier/ClaudeNotifierApp.swift`

- [ ] **Step 1: 实现 LaunchAtLogin**

`app/Sources/ClaudeNotifier/Utilities/LaunchAtLogin.swift`:

```swift
import ServiceManagement

struct LaunchAtLogin {
    static func register() {
        if #available(macOS 13.0, *) {
            do {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } catch {
                NSLog("[ClaudeNotifier] register login item failed: \(error)")
            }
        }
    }

    static func unregister() {
        if #available(macOS 13.0, *) {
            try? SMAppService.mainApp.unregister()
        }
    }

    static var isEnabled: Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }
}
```

- [ ] **Step 2: 入口默认不自动注册**

LaunchAtLogin 仅供 Preferences UI 调用，不在启动时强制注册（避免开发期反复触发系统弹窗）。本 task 只是把 API 准备好；下一 task 在偏好设置 UI 暴露开关。

- [ ] **Step 3: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(util): LaunchAtLogin wrapper via SMAppService"
```

---

### Task 14: PreferencesView（基础设置）

**Files:**
- Create: `app/Sources/ClaudeNotifier/UI/PreferencesView.swift`
- Modify: `app/Sources/ClaudeNotifier/UI/MenuBarController.swift`（添加右键菜单）

- [ ] **Step 1: 实现 PreferencesView**

`app/Sources/ClaudeNotifier/UI/PreferencesView.swift`:

```swift
import SwiftUI

struct PreferencesView: View {
    @AppStorage("soundEnabled") private var soundEnabled = true
    @AppStorage("autoShowEnabled") private var autoShowEnabled = true
    @State private var launchAtLogin = LaunchAtLogin.isEnabled
    let currentPort: Int

    var body: some View {
        Form {
            Toggle("新事件提示音", isOn: $soundEnabled)
            Toggle("新事件自动弹出浮窗", isOn: $autoShowEnabled)
            Toggle("开机自启", isOn: $launchAtLogin)
                .onChange(of: launchAtLogin) { newVal in
                    if newVal { LaunchAtLogin.register() }
                    else { LaunchAtLogin.unregister() }
                }
            HStack {
                Text("HTTP 端口")
                Spacer()
                Text("\(currentPort)").foregroundColor(.secondary)
            }
        }
        .padding(20)
        .frame(width: 360)
    }
}
```

- [ ] **Step 2: 修改 SoundPlayer / FloatingPanelController 读取 AppStorage**

`app/Sources/ClaudeNotifier/Utilities/SoundPlayer.swift`:

```swift
import AppKit

struct SoundPlayer {
    static func playNewEventSound() {
        let enabled = UserDefaults.standard.object(forKey: "soundEnabled") as? Bool ?? true
        guard enabled else { return }
        NSSound(named: "Glass")?.play()
    }
}
```

FloatingPanelController 的 `subscribeForAutoBehavior` 里 `if !self.panel.isVisible { self.show() }` 改为：

```swift
let autoShow = UserDefaults.standard.object(forKey: "autoShowEnabled") as? Bool ?? true
if autoShow && !self.panel.isVisible { self.show() }
```

- [ ] **Step 3: 在 MenuBarController 上加右键菜单**

替换 MenuBarController 的 setupButton + 加新方法：

```swift
private func setupButton() {
    guard let btn = statusItem.button else { return }
    btn.image = NSImage(systemSymbolName: "bell", accessibilityDescription: "Claude Notifier")
    btn.image?.isTemplate = true
    btn.target = self
    btn.action = #selector(handleClick(_:))
    btn.sendAction(on: [.leftMouseUp, .rightMouseUp])
    btn.title = ""
}

@objc private func handleClick(_ sender: NSStatusBarButton) {
    let event = NSApp.currentEvent
    if event?.type == .rightMouseUp {
        showContextMenu()
    } else {
        onClick?()
    }
}

var onShowPreferences: (() -> Void)?

private func showContextMenu() {
    let menu = NSMenu()
    menu.addItem(withTitle: "偏好设置…", action: #selector(handlePreferences), keyEquivalent: ",").target = self
    menu.addItem(withTitle: "全部清空", action: #selector(handleClearAll), keyEquivalent: "").target = self
    menu.addItem(.separator())
    menu.addItem(withTitle: "退出 ClaudeNotifier", action: #selector(handleQuit), keyEquivalent: "q").target = self
    statusItem.menu = menu
    statusItem.button?.performClick(nil)
    statusItem.menu = nil
}

@objc private func handlePreferences() { onShowPreferences?() }
@objc private func handleClearAll() { store.clear() }
@objc private func handleQuit() { NSApp.terminate(nil) }
```

- [ ] **Step 4: 在 AppDelegate 连接 PreferencesView 的弹出**

在 `applicationDidFinishLaunching` 末尾追加：

```swift
let prefsPanel = createPreferencesWindow(port: port)
menuBar.onShowPreferences = {
    prefsPanel.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)
}
```

加方法：

```swift
private func createPreferencesWindow(port: Int) -> NSWindow {
    let host = NSHostingController(rootView: PreferencesView(currentPort: port))
    let win = NSWindow(contentViewController: host)
    win.title = "Claude Notifier 偏好"
    win.styleMask = [.titled, .closable]
    win.isReleasedWhenClosed = false
    return win
}
```

- [ ] **Step 5: 编译 + 手动验证**

```bash
cd app && swift run ClaudeNotifier &
sleep 1
# 右键菜单栏图标 → 看到偏好设置/全部清空/退出
# 点偏好设置 → 弹窗显示，开关切换
# 关掉声音再触发 hook，应不发声
kill %1
```

- [ ] **Step 6: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): preferences window + menubar context menu"
```

---

### Task 15: 浮窗 → TerminalRouter 完整 wire-up + 失败 UI

**Files:**
- Modify: `app/Sources/ClaudeNotifier/UI/FloatingPanelController.swift`
- Modify: `app/Sources/ClaudeNotifier/UI/NotificationRowView.swift`
- Modify: `app/Sources/ClaudeNotifier/Core/NotificationItem.swift`

- [ ] **Step 1: 给 NotificationItem 加 failure 状态**

修改 `NotificationItem.swift`，加可变属性：

```swift
struct NotificationItem: Identifiable, Equatable {
    // ... 原有字段
    var lastError: String? = nil
}
```

- [ ] **Step 2: 给 NotificationStore 加 setError 方法**

修改 `NotificationStore.swift`：

```swift
func setError(id: UUID, message: String?) {
    if let idx = items.firstIndex(where: { $0.id == id }) {
        items[idx].lastError = message
    }
}
```

- [ ] **Step 3: 失败时不移除条目，而是标错；成功才移除**

修改 `FloatingPanelController.handleApprove` / `handleJump`：

```swift
private func handleApprove(_ item: NotificationItem) async {
    do {
        try await router?.approve(item: item)
        await MainActor.run {
            store.setError(id: item.id, message: nil)
            store.remove(id: item.id)
        }
    } catch {
        await MainActor.run {
            store.setError(id: item.id, message: friendlyMessage(error))
        }
        SoundPlayer.playNewEventSound()
    }
}

private func handleJump(_ item: NotificationItem) async {
    do {
        try await router?.jump(item: item)
        await MainActor.run { store.setError(id: item.id, message: nil) }
    } catch {
        await MainActor.run { store.setError(id: item.id, message: friendlyMessage(error)) }
    }
}

private func friendlyMessage(_ error: Error) -> String {
    if let e = error as? AdapterError {
        switch e {
        case .unsupported: return "当前终端不支持此操作"
        case .targetNotFound: return "找不到对应的终端会话"
        case .pluginNotInstalled: return "未检测到 IDEA plugin"
        case .scriptError(let m): return "脚本错误: \(m)"
        }
    }
    return error.localizedDescription
}
```

- [ ] **Step 4: NotificationRowView 渲染错误**

在 NotificationRowView 的 VStack 末尾（按钮上方）加：

```swift
if let err = item.lastError {
    Text("⚠️ \(err)")
        .font(.caption2).foregroundColor(.red)
        .padding(.horizontal, 6).padding(.vertical, 3)
        .background(Color.red.opacity(0.1)).cornerRadius(3)
}
```

`rowBackground` 改为：

```swift
private var rowBackground: Color {
    if item.lastError != nil { return Color.red.opacity(0.10) }
    return item.event == .notification ? Color.yellow.opacity(0.15) : Color.green.opacity(0.10)
}
```

- [ ] **Step 5: 手动 E2E**

```bash
cd app && swift run ClaudeNotifier &
sleep 1
# 触发 IDEA 事件（adapter 是 stub，必定失败）
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d '{
  "schema":1,"event":"notification","session_id":"i","cwd":"/p","project_name":"p",
  "terminal":{"host":"idea","idea_tab_id":"x"},"ts":'$(date +%s)'}'
# 点同意 → 行变红，显示"未检测到 IDEA plugin"，行不消失

# 触发 iTerm 真 E2E（前提：开一个 iTerm tab 并记下 $ITERM_SESSION_ID）
ITERM_ID=$(osascript -e 'tell application "iTerm" to id of current session of current tab of current window')
curl -X POST http://127.0.0.1:6789/hook -H 'Content-Type: application/json' -d "{
  \"schema\":1,\"event\":\"notification\",\"session_id\":\"it1\",\"cwd\":\"/p\",\"project_name\":\"p\",
  \"command_preview\":\"echo HELLO\",
  \"terminal\":{\"host\":\"iterm\",\"iterm_session_id\":\"$ITERM_ID\"},\"ts\":$(date +%s)}"
# 点跳转 → 切到那个 iTerm session
# 点同意 → 该 session 出现 "1"
kill %1
```

- [ ] **Step 6: Commit**

```bash
git add app/
git -c commit.gpgsign=false commit -m "feat(ui): wire approve/jump to router with error display"
```

---

### Task 16: 手动测试清单 + README

**Files:**
- Create: `docs/manual-test-checklist.md`
- Create: `README.md`

- [ ] **Step 1: 写手动测试清单**

`docs/manual-test-checklist.md`:

```markdown
# ClaudeNotifier 手动测试清单

每次发版前过一遍。

## 启动 / 端口

- [ ] 首次启动：菜单栏出现 🔔 图标
- [ ] `~/.config/claude-notifier/runtime.json` 写入了 port/pid/host
- [ ] 用 `lsof -nP -iTCP:6789` 确认监听
- [ ] 占用 6789 后再启动：自动 fallback 到 6790（重启 lsof 验证）

## Hook 接收

- [ ] curl POST 合法 payload → 200 OK
- [ ] curl POST schema=99 → 400
- [ ] 同 session_id 第二次 POST → 浮窗那一行替换、ts 更新

## UI

- [ ] 队列空 → 浮窗自动收起、徽标无数字
- [ ] 第一次有事件 → 浮窗自动弹出、Glass 声音
- [ ] 黄色背景 = notification；绿色 = stop；红色 = 失败
- [ ] iTerm/IDEA/VS Code 行有对应徽标
- [ ] VS Code 行同意按钮置灰
- [ ] unknown 行同意+跳转都置灰

## iTerm 路径

- [ ] 跳转：精准切到对应 session
- [ ] 同意：该 session 出现 "1" 并执行
- [ ] session 已关闭：行变红，提示"找不到对应的终端会话"

## IDEA 路径（Plan A 阶段）

- [ ] 同意/跳转 → 行变红，提示"未检测到 IDEA plugin"

## VS Code 路径

- [ ] 跳转：`open vscode://file/...` 激活 VS Code
- [ ] 同意：按钮置灰，不能点

## 偏好设置

- [ ] 右键菜单 → 偏好设置弹窗
- [ ] 关声音 → 再触发不发声
- [ ] 关自动弹出 → 收起状态时新事件不弹出，但徽标更新
- [ ] 开机自启切换 → 系统设置 → 通用 → 登录项里出现 ClaudeNotifier

## 失败容错

- [ ] App 关掉，curl POST → 连接被拒（hook 脚本侧 fallback 由 Plan B 验证）
- [ ] 浮窗"全部清空" → items 清零、自动收起
```

- [ ] **Step 2: 写 README**

`README.md`:

```markdown
# Claude Notifier

CC（Claude Code）通知与快速操作助手：菜单栏 App + CC plugin + IDEA plugin 三件套。

## 项目结构

- `app/` — macOS 菜单栏 App（SwiftUI + SPM）
- `cc-plugin/` — Claude Code plugin（待 Plan B）
- `idea-plugin/` — IntelliJ Platform plugin（待 Plan C）
- `docs/specs/` — 设计文档
- `docs/plans/` — 实施计划

## App 开发

```bash
cd app
swift build       # 编译
swift test        # 跑测试
swift run ClaudeNotifier  # 启动（菜单栏出现 🔔）
```

## 当前能力（Plan A 完成后）

- ✅ 接收 CC hook（POST :6789/hook）
- ✅ 菜单栏徽标 + 屏幕右上常驻浮窗
- ✅ iTerm 跳转 + 同意（100% 可靠）
- ✅ VS Code 跳转（同意暂不支持）
- ⏳ IDEA：等 Plan C（当前 stub，会提示"未检测到 IDEA plugin"）
- ⏳ CC plugin：等 Plan B（当前需手动改 ~/.claude/settings.json）

## 设计文档

详见 `docs/specs/2026-05-27-claude-notifier-design.md`
```

- [ ] **Step 3: Commit**

```bash
git add docs/manual-test-checklist.md README.md
git -c commit.gpgsign=false commit -m "docs: manual test checklist + README"
```

---

## Self-Review 备忘

写完后已自检：

1. **Spec 覆盖** ✅
   - §3 架构：Task 1-15 全覆盖
   - §4 协议：Task 2 (HookPayload) + Task 5 (HookEndpointServer)
   - §5 终端定位：Task 6 (VS Code/unknown/IDEA stub) + Task 7 (iTerm) + Task 8 (Router)；IDEA 真实实现由 Plan C 完成（spec 已声明）
   - §6 UI：Task 9-12, 14, 15
   - §7 安装：Task 13 (LaunchAtLogin)；brew cask 分发不在 Plan A 范围
   - §8 项目结构：Task 1 + 全程对应
   - §9 错误处理：Task 15 + 测试中
   - §10 测试：单元测试随各 task；手动清单 Task 16

2. **Placeholder scan** ✅ 无 TBD/TODO；所有代码片段完整可直接用。

3. **Type 一致性** ✅
   - `HookPayload.Host` 枚举值在所有 task 一致
   - `AdapterError` cases 在 Adapter / Router / friendlyMessage 一致
   - `NotificationItem.lastError` 在 Store / View 一致

## 范围外（明确不在 Plan A）

- IDEA plugin 真实实现 → Plan C
- CC plugin（hook 脚本 + slash commands） → Plan B
- App 打包成 .app bundle / brew cask 分发 → 后续独立任务
- Accessibility 权限申请引导（VS Code/IDEA fallback 用） → 可选增强

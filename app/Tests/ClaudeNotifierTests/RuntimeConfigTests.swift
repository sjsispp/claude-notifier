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

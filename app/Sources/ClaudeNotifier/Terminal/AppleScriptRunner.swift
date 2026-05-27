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

import Foundation

/// 通过 HTTP 调用 IDEA plugin（默认 :6790）实现精准 jump / approve。
struct IdeaAdapter: TerminalAdapter {
    let host: HookPayload.Host = .idea
    let baseURL: URL
    let session: URLSession

    init(baseURL: URL = URL(string: "http://127.0.0.1:6790")!,
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func jump(item: NotificationItem) async throws {
        guard let tabId = item.ideaTabId else { throw AdapterError.targetNotFound }
        try await post(path: "/focusTab", body: ["tabId": tabId])
    }

    func approve(item: NotificationItem) async throws {
        guard let tabId = item.ideaTabId else { throw AdapterError.targetNotFound }
        try await post(path: "/sendText", body: ["tabId": tabId, "text": "1\n"])
    }

    private func post(path: String, body: [String: String]) async throws {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 2

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw AdapterError.scriptError("invalid response")
            }
            switch http.statusCode {
            case 200: return
            case 410: throw AdapterError.targetNotFound
            case 400, 405: throw AdapterError.scriptError("bad request: \(String(data: data, encoding: .utf8) ?? "")")
            default: throw AdapterError.scriptError("HTTP \(http.statusCode)")
            }
        } catch let e as AdapterError {
            throw e
        } catch let e as URLError where e.code == .cannotConnectToHost || e.code == .timedOut {
            throw AdapterError.pluginNotInstalled
        } catch {
            throw AdapterError.scriptError("\(error)")
        }
    }
}

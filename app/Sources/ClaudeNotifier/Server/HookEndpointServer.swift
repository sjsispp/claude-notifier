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

import AppKit

struct SoundPlayer {
    static func playNewEventSound() {
        let enabled = UserDefaults.standard.object(forKey: "soundEnabled") as? Bool ?? true
        guard enabled else { return }
        NSSound(named: "Glass")?.play()
    }
}

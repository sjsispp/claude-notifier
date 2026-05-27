import AppKit

struct SoundPlayer {
    static func playNewEventSound() {
        NSSound(named: "Glass")?.play()
    }
}

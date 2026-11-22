import Foundation
import Observation
import AVFAudio
import AlarmCore

@Observable @MainActor final class SoundPreview: NSObject, AVAudioPlayerDelegate {
    private(set) var playing = false
    @ObservationIgnored private var player: AVAudioPlayer?
    func play(_ url: URL) throws {
        stop()
        let next = try AVAudioPlayer(contentsOf: url); next.delegate = self
        guard next.play() else { throw LoopFailure.invalid("This sound could not be previewed. Choose another sound.") }
        player = next; playing = true
    }
    func stop() { player?.stop(); player = nil; playing = false }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { finish(ObjectIdentifier(player)) }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: (any Error)?) { finish(ObjectIdentifier(player)) }
    nonisolated private func finish(_ id: ObjectIdentifier) {
        Task { @MainActor [weak self] in
            guard let self, let current = self.player, ObjectIdentifier(current) == id else { return }
            self.stop()
        }
    }
}

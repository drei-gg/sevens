import Foundation
import Testing
import AVFAudio
import AlarmCore
@testable import AlarmUI

@MainActor struct ImportedSoundsTests {
    private func fixture(_ source: URL) throws {
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 8000, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]
        let file = try AVAudioFile(forWriting: source, settings: settings, commonFormat: .pcmFormatInt16, interleaved: true)
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 8000)); buffer.frameLength = 8000
        let samples = try #require(buffer.int16ChannelData?[0])
        for index in 0..<8000 { samples[index] = Int16(index % 1000) }
        try file.write(from: buffer)
    }
    @Test func previewStopsExplicitlyAndOnCompletion() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("Preview.wav"); try fixture(source)
        let preview = SoundPreview(); try preview.play(source); #expect(preview.playing)
        preview.stop(); #expect(!preview.playing)
        try preview.play(source)
        for _ in 0..<50 { if !preview.playing { break }; try await Task.sleep(for: .milliseconds(100)) }
        #expect(!preview.playing)
    }
    @Test func copyReopenFallbackPruningAndCorruption() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("Morning.wav"); try fixture(source)
        let original = try Data(contentsOf: source)
        let store = ImportedSounds(directory: root.appendingPathComponent("Sounds"))
        let sound = try store.load(source)
        #expect(sound.name == "Morning.wav")
        #expect(store.available(sound))
        #expect(try Data(contentsOf: source) == original)
        let preview = try AVAudioPlayer(contentsOf: store.file(sound)); #expect(preview.duration > 0)
        var routine = Routine(); routine.importedSound = sound; routine.tone = .pulse
        #expect(store.soundName(routine) == store.file(sound).lastPathComponent)
        var archive = LoopArchive(); archive.routines = [routine]
        let orphan = try store.load(source)
        store.prune(archive)
        #expect(!store.available(orphan) && store.available(sound))
        store.discard(sound, unlessUsedBy: archive); #expect(store.available(sound))
        #expect(ImportedSounds(directory: store.directory).available(sound))
        try Data("not audio".utf8).write(to: source)
        #expect(throws: LoopFailure.invalid("Choose a readable PCM WAV file.")) { try store.load(source) }
        #expect(store.available(sound))
        archive.routines = []; archive.installed = [Registration(id: UUID(), routine: routine)]
        store.prune(archive); #expect(store.available(sound))
        archive.pending = archive.installed.first; archive.installed = []
        store.prune(archive); #expect(store.available(sound))
        store.prune(LoopArchive()); #expect(!store.available(sound))
        #expect(store.soundName(routine) == "alarm-pulse.wav")
    }
}

import Foundation
import AlarmCore

/// Copies are owned by the app, in the directory AlarmKit's named-sound API reads.
struct ImportedSounds: Sendable {
    static var live: ImportedSounds { ImportedSounds(directory: FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Sounds", isDirectory: true)) }
    let directory: URL
    func file(_ sound: ImportedSound) -> URL { directory.appendingPathComponent("custom-\(sound.id.uuidString.lowercased()).wav") }
    func available(_ sound: ImportedSound) -> Bool {
        guard let size = try? file(sound).resourceValues(forKeys: [.fileSizeKey]).fileSize else { return false }
        return size > 0 && UInt64(size) <= importedSoundMaxBytes()
    }
    func load(_ url: URL) throws -> ImportedSound {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var data = Data(); let limit = Int(importedSoundMaxBytes())
        while data.count <= limit {
            let next = try handle.read(upToCount: min(65536, limit + 1 - data.count)) ?? Data()
            if next.isEmpty { break }; data.append(next)
        }
        let prepared = try LoopFailure.sentence { try importedSoundPrepare(data: data) }
        let name = String(url.lastPathComponent.filter { !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }.prefix(80)).trimmingCharacters(in: .whitespacesAndNewlines)
        let sound = ImportedSound(id: UUID(), name: name.isEmpty ? "Imported sound" : name)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let existing = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        let bytes = try existing.reduce(0) { try $0 + ($1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        guard bytes + prepared.wav.count <= 128 * 1024 * 1024 else { throw LoopFailure.invalid("Sound storage is full. Close the editor to clear unused imports, then try again.") }
        try prepared.wav.write(to: file(sound), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return sound
    }
    func discard(_ sound: ImportedSound, unlessUsedBy archive: LoopArchive) {
        guard !importedSoundReferences(archive: archive).contains(sound.id) else { return }
        try? FileManager.default.removeItem(at: file(sound))
    }
    func prune(_ archive: LoopArchive) {
        let retained = Set(importedSoundReferences(archive: archive).map { id in "custom-\(id.uuidString.lowercased()).wav" })
        for url in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] where url.lastPathComponent.hasPrefix("custom-") && url.pathExtension == "wav" && !retained.contains(url.lastPathComponent) { try? FileManager.default.removeItem(at: url) }
    }
    /// A missing owned file is a recoverable selection; scheduling uses the visible fallback.
    func soundName(_ routine: Routine) -> String? {
        if let sound = routine.importedSound, available(sound) { return file(sound).lastPathComponent }
        guard let tone = routine.tone, tone != .system else { return nil }
        return "alarm-\(tone.rawValue).wav"
    }
}

import Foundation

/// The app's archive file. The file, its atomic write and publishing only after the write stay
/// native (AlarmStore); validating, encoding and the 32 MB limit are the core's `storeEncode`.
public typealias LoopRepository = ArchiveFile<LoopArchive>

public extension ArchiveFile where Archive == LoopArchive {
    init(file: URL?, seed: LoopArchive = LoopArchive(), writer: @escaping Writer = AtomicFile.write) {
        self.init(file: file, seed: seed, maxBytes: BackupCodec.maxBytes, tooLarge: LoopFailure.invalid("Archive exceeds 32 MB."),
                  decode: { try BackupCodec.decode($0) },
                  encode: { archive in try LoopFailure.sentence { try storeEncode(archive: archive) } },
                  writer: writer)
    }
    static var atomicWrite: Writer { AtomicFile.write }
}

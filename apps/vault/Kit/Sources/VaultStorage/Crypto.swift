#if canImport(CryptoKit)
import Foundation
import CryptoKit
import CommonCrypto
import Security
import AppleArchive
import System
import VaultCore

struct KeyEnvelope: Codable, Sendable {
    let salt: Data
    let rounds: UInt32
    let sealed: Data
}
struct VaultHeader: Codable, Sendable {
    var version = 1
    let id: UUID
    var password: KeyEnvelope
    var recovery: KeyEnvelope
    var credentialKind: VaultCredentialKind? = nil
}
enum VaultCrypto {
    static let rounds: UInt32 = 600_000
    static func random(_ count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { throw VaultError.damaged }
        return Data(bytes)
    }
    static func derive(_ credential: String, salt: Data, rounds: UInt32) throws -> SymmetricKey {
        guard salt.count == 32, rounds == Self.rounds, credential.utf8.count <= 4096 else { throw VaultError.unsupported }
        var output = [UInt8](repeating: 0, count: 32)
        let password = Array(credential.utf8)
        let status = password.withUnsafeBytes { pass in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pass.baseAddress?.assumingMemoryBound(to: Int8.self), password.count,
                                    saltBytes.baseAddress?.assumingMemoryBound(to: UInt8.self), salt.count,
                                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds, &output, output.count)
            }
        }
        guard status == kCCSuccess else { throw VaultError.damaged }
        defer { _ = output.withUnsafeMutableBytes { $0.initializeMemory(as: UInt8.self, repeating: 0) } }
        return SymmetricKey(data: output)
    }
    static func wrap(_ key: SymmetricKey, credential: String, context: String) throws -> KeyEnvelope {
        let salt = try random(32); let wrapping = try derive(credential, salt: salt, rounds: rounds)
        return KeyEnvelope(salt: salt, rounds: rounds, sealed: try seal(key.withUnsafeBytes { Data($0) }, key: wrapping, context: context))
    }
    static func unwrap(_ envelope: KeyEnvelope, credential: String, context: String) throws -> SymmetricKey {
        let key = try derive(credential, salt: envelope.salt, rounds: envelope.rounds)
        let bytes = try open(envelope.sealed, key: key, context: context)
        guard bytes.count == 32 else { throw VaultError.damaged }
        return SymmetricKey(data: bytes)
    }
    static func seal(_ data: Data, key: SymmetricKey, context: String) throws -> Data {
        guard let sealed = try AES.GCM.seal(data, using: key, authenticating: Data(context.utf8)).combined else { throw VaultError.damaged }
        return sealed
    }
    static func open(_ data: Data, key: SymmetricKey, context: String) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: key, authenticating: Data(context.utf8))
    }
    static func digest(_ url: URL) throws -> (Int64, String) {
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var hash = SHA256(); var size: Int64 = 0
        while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty { hash.update(data: data); size += Int64(data.count) }
        return (size, hash.finalize().map { String(format: "%02x", $0) }.joined())
    }
    static func fileKey(_ key: SymmetricKey, id: UUID) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: Data(id.uuidString.utf8), info: Data("Vault/file/v1".utf8), outputByteCount: 32)
    }
    static func encrypt(_ source: URL, to destination: URL, key: SymmetricKey) throws {
        let context = ArchiveEncryptionContext(profile: .hkdf_sha256_aesctr_hmac__symmetric__none, compressionAlgorithm: .none)
        try context.setSymmetricKey(key)
        guard let input = ArchiveByteStream.fileStream(path: FilePath(source.path), mode: .readOnly, options: [], permissions: FilePermissions(rawValue: 0o600)) else { throw VaultError.missingFile }
        defer { try? input.close() }
        guard let output = ArchiveByteStream.fileStream(path: FilePath(destination.path), mode: .writeOnly, options: [.create, .exclusiveCreate], permissions: FilePermissions(rawValue: 0o600)) else { throw VaultError.damaged }
        defer { try? output.close() }
        guard let stream = ArchiveByteStream.encryptionStream(writingTo: output, encryptionContext: context) else { throw VaultError.damaged }
        do {
            _ = try ArchiveByteStream.process(readingFrom: input, writingTo: stream)
            try stream.close(); try output.close()
        } catch { try? stream.close(); try? FileManager.default.removeItem(at: destination); throw error }
    }
    /// Read authenticated chunks with bounded memory. A consumer sees only authenticated stream output;
    /// callers publish files only after complete-stream authentication and expected digest/size checks.
    static func decrypt(_ source: URL, key: SymmetricKey, consume: (Data) throws -> Void) throws {
        guard let input = ArchiveByteStream.fileStream(path: FilePath(source.path), mode: .readOnly, options: [], permissions: FilePermissions(rawValue: 0o600)) else { throw VaultError.missingFile }
        defer { try? input.close() }
        guard let context = ArchiveEncryptionContext(from: input), context.profile == .hkdf_sha256_aesctr_hmac__symmetric__none else { throw VaultError.damaged }
        try context.setSymmetricKey(key)
        guard let stream = ArchiveByteStream.decryptionStream(readingFrom: input, encryptionContext: context) else { throw VaultError.damaged }
        defer { try? stream.close() }
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: 1_048_576, alignment: 16)
        defer { buffer.initializeMemory(as: UInt8.self, repeating: 0); buffer.deallocate() }
        while true {
            let count = try stream.read(into: buffer)
            guard count >= 0 else { throw VaultError.damaged }
            if count == 0 { break }
            try consume(Data(buffer.prefix(count)))
        }
        try stream.close()
    }
    static func verify(_ source: URL, key: SymmetricKey, item: VaultItem, output: FileHandle? = nil) throws {
        var hash = SHA256(); var bytes: Int64 = 0
        try decrypt(source, key: fileKey(key, id: item.id)) { data in
            bytes += Int64(data.count); guard bytes <= item.bytes else { throw VaultError.damaged }
            hash.update(data: data); try output?.write(contentsOf: data)
        }
        let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard bytes == item.bytes, digest == item.digest else { throw VaultError.damaged }
    }
}

enum ProtectedFiles {
    static func directory(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try protect(url)
    }
    static func protect(_ url: URL) throws {
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        #endif
        var resource = url; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try resource.setResourceValues(values)
    }
    static func write(_ data: Data, to url: URL) throws {
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
        try protect(url)
    }
    static func boundedRead(_ url: URL, max: Int = 32_000_000) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= max else { throw VaultError.damaged }
        return try Data(contentsOf: url)
    }
}
#endif

@_exported import BloomWire
import CryptoKit
import Foundation
import Security

public enum BloomFailure: Swift.Error, LocalizedError {
  case message(String)
  public var errorDescription: String? {
    if case .message(let s) = self { return s }
    return nil
  }
}
public actor BloomRepository {
  private let file: URL?
  private let suppliedKey: Data?
  private var cache: Archive?
  public init(file: URL?, seed: Archive? = nil, key: Data? = nil) {
    self.file = file
    cache = seed
    suppliedKey = key
  }
  private func key(create: Bool) throws -> SymmetricKey {
    if let suppliedKey { return SymmetricKey(data: suppliedKey) }
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "bloom.records.v1",
      kSecAttrAccount as String: file?.lastPathComponent ?? "records",
      kSecReturnData as String: true,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecSuccess, let data = item as? Data { return SymmetricKey(data: data) }
    guard status == errSecItemNotFound, create else {
      throw BloomFailure.message(
        "The records key is unavailable. Unlock your phone and retry. Existing records have not been changed."
      )
    }
    var bytes = Data(count: 32)
    let result = bytes.withUnsafeMutableBytes {
      SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
    }
    guard result == errSecSuccess else {
      throw BloomFailure.message("Could not create the records key.")
    }
    var add = query
    add.removeValue(forKey: kSecReturnData as String)
    add[kSecValueData as String] = bytes
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else {
      throw BloomFailure.message("Could not store the records key.")
    }
    return SymmetricKey(data: bytes)
  }
  public func read() throws -> Archive {
    if let cache { return cache }
    guard let file, FileManager.default.fileExists(atPath: file.path) else {
      let empty = Archive()
      cache = empty
      return empty
    }
    let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
    guard size <= 17_000_000 else {
      throw BloomFailure.message("The records file is too large. It has not been changed.")
    }
    let encrypted = try Data(contentsOf: file)
    let plain = try AES.GCM.open(
      AES.GCM.SealedBox(combined: encrypted), using: key(create: false),
      authenticating: Data("bloom.records.v1".utf8))
    let normalized = try bloomValidate(archive: String(decoding: plain, as: UTF8.self))
    let a = try BloomCodec.decode(Archive.self, from: Data(normalized.utf8))
    cache = a
    return a
  }
  private func write(_ a: Archive) throws -> Archive {
    let normalized = try bloomValidate(archive: BloomCodec.string(a))
    if let file {
      let exists = FileManager.default.fileExists(atPath: file.path)
      let encrypted = try AES.GCM.seal(
        Data(normalized.utf8), using: key(create: !exists),
        authenticating: Data("bloom.records.v1".utf8)
      ).combined!
      try FileManager.default.createDirectory(
        at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
      var directory = file.deletingLastPathComponent()
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      try directory.setResourceValues(values)
      try encrypted.write(
        to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    cache = a
    return a
  }
  public func apply(
    _ command: Command, paid: Bool, now: Date = Date(), zone: String = TimeZone.current.identifier
  ) throws -> Archive {
    let current = try read()
    let json = try bloomApply(
      archive: BloomCodec.string(current), command: BloomCodec.string(command),
      now: Int64(now.timeIntervalSince1970), zone: zone, paid: paid)
    return try write(BloomCodec.decode(Archive.self, from: Data(json.utf8)))
  }
  public func applyWatch(_ request: WatchRequest, paid: Bool, now: Date = Date(), zone: String = TimeZone.current.identifier) throws -> Archive {
    let current = try read()
    let json = try bloomWatchApply(archive: BloomCodec.string(current), request: BloomCodec.string(request), now: Int64(now.timeIntervalSince1970), zone: zone, paid: paid)
    if request.command == nil { return current }
    return try write(BloomCodec.decode(Archive.self, from: Data(json.utf8)))
  }
  public func dashboard(now: Date = Date(), zone: String = TimeZone.current.identifier) throws
    -> Dashboard
  {
    let json = try bloomDashboard(
      archive: BloomCodec.string(read()), now: Int64(now.timeIntervalSince1970), zone: zone)
    return try BloomCodec.decode(Dashboard.self, from: Data(json.utf8))
  }
  public func exportJSON() throws -> Data { try BloomCodec.encode(read()) }
  public func exportCSV(contacts: Bool, notes: Bool) throws -> Data {
    Data(
      try bloomCsv(
        archive: BloomCodec.string(read()), includeContacts: contacts, includeNotes: notes
      ).utf8)
  }
  /// Text and PDF reports print temperatures in the unit the app shows; CSV stays in Celsius.
  public func report(from: String, to: String, contacts: Bool, notes: Bool, fahrenheit: Bool)
    throws -> String
  {
    try bloomReport(
      archive: BloomCodec.string(read()), from: from, to: to, includeContacts: contacts,
      includeNotes: notes, fahrenheit: fahrenheit)
  }
  public func seal(password: String) throws -> Data {
    Data(try bloomSeal(archive: BloomCodec.string(read()), password: password))
  }
  public func inspectImport(_ data: Data, password: String?) throws -> Archive {
    guard data.count <= 17_000_000 else {
      throw BloomFailure.message("The import file is too large.")
    }
    let json: String
    if let password {
      json = try bloomOpen(data: data, password: password)
    } else {
      json = try bloomValidate(archive: String(decoding: data, as: UTF8.self))
    }
    return try BloomCodec.decode(Archive.self, from: Data(json.utf8))
  }
  public func merge(_ incoming: Archive) throws -> Archive {
    let json = try bloomMerge(
      archive: BloomCodec.string(read()), incoming: BloomCodec.string(incoming))
    return try write(BloomCodec.decode(Archive.self, from: Data(json.utf8)))
  }
  public func importHealth(_ reviewed: Archive) throws -> Archive {
    let json = try bloomHealthImport(
      archive: BloomCodec.string(read()), reviewed: BloomCodec.string(reviewed))
    return try write(BloomCodec.decode(Archive.self, from: Data(json.utf8)))
  }
  public func erase() throws -> Archive {
    // Commit an empty valid archive atomically; never leave cache claiming a deletion that failed.
    try write(Archive())
  }
  public func retry() throws -> Archive {
    cache = nil
    return try read()
  }
}

import Foundation
import Security

/// Where `TrialPolicy.onFirstLaunch` keeps its start date (C12).
public protocol TrialStartStore: Sendable {
    func read() -> Date?
    /// Writes `date` only if nothing is stored; returns the date that is stored afterwards
    /// (nil if the store is unavailable).
    func writeOnce(_ date: Date) -> Date?
}

/// Keychain: `kSecAttrAccessibleAfterFirstUnlock`, not synchronized, so it survives a reinstall
/// but stays on this device.
public struct KeychainTrialStartStore: TrialStartStore {
    private let service: String
    private let account = "seven.trialStart"

    public init(service: String) { self.service = service }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }

    public func read() -> Date? {
        var query = baseQuery
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let text = String(data: data, encoding: .utf8),
              let seconds = Double(text) else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    public func writeOnce(_ date: Date) -> Date? {
        if let existing = read() { return existing }
        var item = baseQuery
        item[kSecValueData as String] = Data(String(date.timeIntervalSince1970).utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecDuplicateItem { return read() }
        return status == errSecSuccess ? date : nil
    }

    /// Removes the date (tests, debug).
    public func erase() { SecItemDelete(baseQuery as CFDictionary) }
}

/// In-memory store for tests and previews.
public final class MemoryTrialStartStore: TrialStartStore, @unchecked Sendable {
    private let lock = NSLock()
    private var date: Date?
    public init(_ date: Date? = nil) { self.date = date }
    public func read() -> Date? { lock.withLock { date } }
    public func writeOnce(_ new: Date) -> Date? {
        lock.withLock {
            if date == nil { date = new }
            return date
        }
    }
}

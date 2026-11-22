#if canImport(CleanerCore)
import Foundation
import SQLite3
import CleanerCore

/// SQLite owns atomic checkpoint writes; the core owns reuse/version rules. All connection access
/// is under this lock. This derived, excluded-from-backup cache never writes ReviewStore decisions.
public final class MeasurementCache: @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    public init(url: URL) { self.url = url }
    deinit { if let handle { sqlite3_close(handle) } }

    private func database() throws -> OpaquePointer {
        if let handle { return handle }
        var directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        var db: OpaquePointer?
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK, let db else {
            if let db { sqlite3_close(db) }; throw CleanerError.blocked("Saved scan progress could not be opened. Clear scan progress to compare again.")
        }
        do {
            let version = try scalar(db, "PRAGMA user_version")
            guard version == 0 || version == 1 else { throw CleanerError.blocked("Saved scan progress needs a newer app. Clear scan progress to compare again.") }
            try execute(db, "PRAGMA journal_mode = WAL")
            if version == 0 {
                try execute(db, "BEGIN IMMEDIATE")
                do {
                    try execute(db, "CREATE TABLE measurements (asset_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
                    try execute(db, "PRAGMA user_version = 1"); try execute(db, "COMMIT")
                } catch { try? execute(db, "ROLLBACK"); throw error }
            }
            handle = db; return db
        } catch { sqlite3_close(db); throw error }
    }
    private func failure() -> CleanerError { .blocked("Saved scan progress could not be read or written. Your media and review decisions are unchanged.") }
    private func statement(_ db: OpaquePointer, _ sql: String, _ values: [String] = []) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw failure() }
        for (offset, value) in values.enumerated() {
            guard sqlite3_bind_text(stmt, Int32(offset + 1), value, -1, transient) == SQLITE_OK else { sqlite3_finalize(stmt); throw failure() }
        }
        return stmt
    }
    private func execute(_ db: OpaquePointer, _ sql: String, _ values: [String] = []) throws {
        let stmt = try statement(db, sql, values); defer { sqlite3_finalize(stmt) }
        var code = sqlite3_step(stmt)
        while code == SQLITE_ROW { code = sqlite3_step(stmt) }
        guard code == SQLITE_DONE else { throw failure() }
    }
    private func scalar(_ db: OpaquePointer, _ sql: String) throws -> Int {
        let stmt = try statement(db, sql); defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { throw failure() }
        return Int(sqlite3_column_int64(stmt, 0))
    }
    public func count() throws -> Int { try lock.withLock { try scalar(database(), "SELECT COUNT(*) FROM measurements") } }
    public func restore(_ current: [CleanupItem]) throws -> [CleanupItem] {
        try lock.withLock {
            let db = try database(); let accessible = Set(current.map(\.id))
            try execute(db, "BEGIN IMMEDIATE")
            do {
                var cached: [String: String] = [:]; var removed: [String] = []
                let stmt = try statement(db, "SELECT asset_id, payload FROM measurements")
                do {
                    defer { sqlite3_finalize(stmt) }
                    var code = sqlite3_step(stmt)
                    while code == SQLITE_ROW {
                        guard let key = sqlite3_column_text(stmt, 0), let value = sqlite3_column_text(stmt, 1) else { throw failure() }
                        let id = String(cString: key)
                        if accessible.contains(id) { cached[id] = String(cString: value) } else { removed.append(id) }
                        code = sqlite3_step(stmt)
                    }
                    guard code == SQLITE_DONE else { throw failure() }
                }
                let result = try current.map { item in try cached[item.id].map { try scanRestore(item: item, checkpoint: $0) } ?? item }
                for item in result where try scanCheckpoint(item: item) == nil { removed.append(item.id) }
                for id in removed { try execute(db, "DELETE FROM measurements WHERE asset_id = ?", [id]) }
                try execute(db, "COMMIT"); return result
            } catch { try? execute(db, "ROLLBACK"); throw error }
        }
    }
    public func checkpoint(_ item: CleanupItem) throws {
        try lock.withLock {
            let db = try database()
            if let text = try scanCheckpoint(item: item) {
                try execute(db, "INSERT OR REPLACE INTO measurements(asset_id,payload) VALUES (?,?)", [item.id, text])
            } else { try execute(db, "DELETE FROM measurements WHERE asset_id = ?", [item.id]) }
        }
    }
    public func clear() throws {
        try lock.withLock {
            if let handle { sqlite3_close(handle); self.handle = nil }
            for suffix in ["", "-wal", "-shm"] {
                let file = URL(fileURLWithPath: url.path + suffix)
                if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
            }
        }
    }
}

#endif

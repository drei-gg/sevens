import Foundation
import Testing
@testable import QRCore

// Phase 0 owns this file. Shared by every package's fixture tests (PLAN §5.5).

struct FixtureCase: Decodable, Sendable, CustomTestStringConvertible {
    struct Expect: Decodable, Sendable {
        var type: String
        var level: Int
        var signals: [String]
        /// Optional: info signal IDs expected across the analysis and its embedded analyses.
        /// Compared only when present (PLAN §5.5 compares non-info signals; this extends it).
        var info: [String]?
        var fields: [String: String]?
    }
    var id: String
    var spec: String?
    var input: String?
    var inputBase64: String?
    var symbology: String?
    var note: String?
    var expect: Expect

    var testDescription: String { id }

    var scannedCode: ScannedCode {
        let sym = symbology.flatMap(Symbology.init(rawValue:)) ?? .qr
        if let input { return ScannedCode(text: input, symbology: sym) }
        let bytes = inputBase64.flatMap { Data(base64Encoded: $0) }.map { [UInt8]($0) } ?? []
        return ScannedCode(text: String(bytes: bytes, encoding: .utf8), bytes: bytes, symbology: sym)
    }
}

enum Fixtures {
    static var directory: URL? { Bundle.module.url(forResource: "Fixtures", withExtension: nil) }

    /// Top-level `*.json` files, sorted.
    static func fileNames() -> [String] {
        guard let dir = directory,
              let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return names.filter { $0.hasSuffix(".json") }.sorted()
    }

    /// Cases of one file, e.g. `Fixtures.load("payments-emv.json")`. Throws on bad JSON.
    static func load(_ name: String) throws -> [FixtureCase] {
        guard let dir = directory else { return [] }
        let data = try Data(contentsOf: dir.appendingPathComponent(name))
        return try JSONDecoder().decode([FixtureCase].self, from: data)
    }

    static func all() throws -> [FixtureCase] {
        try fileNames().flatMap { try load($0) }
    }

    /// Every fixture case's comparison against an analysis; empty when it matches.
    static func mismatches(_ c: FixtureCase, _ a: Analysis) -> [String] {
        var out: [String] = []
        if a.payload.typeID != c.expect.type { out.append("type \(a.payload.typeID) ≠ \(c.expect.type)") }
        if a.level.rawValue != c.expect.level { out.append("level \(a.level.rawValue) ≠ \(c.expect.level)") }
        let got = Set(a.allSignals.filter { $0.severity != .info }.map(\.id.rawValue))
        if got != Set(c.expect.signals) { out.append("signals \(got.sorted()) ≠ \(c.expect.signals.sorted())") }
        if let info = c.expect.info {
            let gotInfo = Set(a.allSignals.filter { $0.severity == .info }.map(\.id.rawValue))
            if gotInfo != Set(info) { out.append("info \(gotInfo.sorted()) ≠ \(info.sorted())") }
        }
        let fields = a.payload.fields
        for (k, v) in c.expect.fields ?? [:] where fields[k] != v {
            out.append("fields[\(k)] \(fields[k].map { "\"\($0)\"" } ?? "nil") ≠ \"\(v)\"")
        }
        return out
    }
}

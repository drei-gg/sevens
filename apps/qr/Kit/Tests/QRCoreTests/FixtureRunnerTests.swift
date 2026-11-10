import Foundation
import Testing
@testable import QRCore

// Phase 0 owns this file. The one generic runner (PLAN §5.5).

@Suite struct FixtureRunner {
    static let knownTypes: Set<String> = ["link", "appLink", "payment", "crypto", "wifi", "contact", "event",
                                          "phone", "sms", "email", "geo", "text", "binary"]

    @Test func everyFixtureFileIsWellFormed() throws {
        var ids = Set<String>()
        for name in Fixtures.fileNames() {
            let cases = try Fixtures.load(name)
            for c in cases {
                #expect(!ids.contains(c.id), "duplicate fixture id \(c.id) in \(name)")
                ids.insert(c.id)
                #expect((c.input == nil) != (c.inputBase64 == nil), "\(c.id): exactly one of input/inputBase64")
                #expect(Self.knownTypes.contains(c.expect.type), "\(c.id): unknown type \(c.expect.type)")
                #expect((0...2).contains(c.expect.level), "\(c.id): level \(c.expect.level)")
                for s in c.expect.info ?? [] {
                    #expect(SignalID(rawValue: s)?.severity == .info, "\(c.id): \(s) in info isn't an info signal")
                }
                for s in c.expect.signals {
                    let id = SignalID(rawValue: s)
                    #expect(id != nil, "\(c.id): unknown signal \(s)")
                    #expect(id?.severity != .info, "\(c.id): info signal \(s) listed (only non-info signals are compared)")
                }
                if let sym = c.symbology { #expect(Symbology(rawValue: sym) != nil, "\(c.id): unknown symbology \(sym)") }
            }
        }
    }

    @Test(.enabled(if: Analyzer.fixturesEnabled, "enabled by WP 2.1"))
    func everyFixturePasses() throws {
        let analyzer = Analyzer(data: BundledData.shared)
        for c in try Fixtures.all() {
            let problems = Fixtures.mismatches(c, analyzer.analyze(c.scannedCode))
            #expect(problems.isEmpty, "\(c.id): \(problems.joined(separator: "; "))")
        }
    }

    /// Gate G4: every SignalID appears in at least three fixtures (top-level cases' `signals`
    /// and `info`, plus Peek pairs' `expect.signals` and `expect.info`).
    @Test(.enabled(if: Analyzer.fixturesEnabled, "enabled by WP 2.1"))
    func everySignalHasThreeFixtures() throws {
        var counts: [String: Int] = [:]
        for c in try Fixtures.all() {
            for s in Set(c.expect.signals + (c.expect.info ?? [])) { counts[s, default: 0] += 1 }
        }
        if let dir = Fixtures.directory?.appendingPathComponent("peek"),
           let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            struct Pair: Decodable { struct E: Decodable { var signals: [String]?; var info: [String]? }; var expect: E }
            for n in names where n.hasSuffix(".json") {
                guard let p = try? JSONDecoder().decode(Pair.self, from: Data(contentsOf: dir.appendingPathComponent(n))) else { continue }
                for s in Set((p.expect.signals ?? []) + (p.expect.info ?? [])) { counts[s, default: 0] += 1 }
            }
        }
        for id in SignalID.allCases {
            #expect(counts[id.rawValue, default: 0] >= 3, "G4: \(id.rawValue) has \(counts[id.rawValue, default: 0]) fixtures")
        }
    }
}

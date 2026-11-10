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
}

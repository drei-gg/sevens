import Foundation
import QRCore
import Testing
@testable import QRUI

@Suite struct MakeAutoCorrectionTests {
    /// A spread from tiny to large, and in each mode (numeric, alphanumeric, bytes, Unicode).
    static let inputs: [String] = {
        var out = ["a", "hello", "12345678", "HTTPS://EXAMPLE.COM", "https://example.com",
                   "https://example.org/menu", "WIFI:T:WPA;S:Home;P:secret123;;", "café ☕ 日本語", "TEL:+639171234567"]
        for n in [7, 11, 14, 17, 20, 26, 32, 42, 53, 62, 78, 106, 134, 154, 200, 300, 500, 900] {
            out.append(String((0..<n).map { i in Character(UnicodeScalar(UInt8(97 + (i * 7) % 26))) }))
        }
        return out
    }()

    private func version(_ text: String, _ c: Correction) throws -> Int {
        try #require(CodeGenerator.qrVersion(of: try CodeGenerator.generate(text: text, symbology: .qr, correction: c)))
    }

    @Test(arguments: MakeAutoCorrectionTests.inputs)
    func autoNeverLargerThanMedium(text: String) throws {
        #expect(try version(text, .auto) == (try version(text, .M)))
    }

    @Test(arguments: MakeAutoCorrectionTests.inputs)
    func autoLevelIsTheHighestThatFits(text: String) throws {
        let level = try #require(CodeGenerator.autoLevel(for: text))
        let m = try version(text, .M)
        #expect(try version(text, level) == m)
        switch level {
        case .H: break
        case .Q: #expect(try version(text, .H) > m)
        default:
            #expect(level == .M)
            #expect(try version(text, .Q) > m)
        }
    }

    @Test func autoRaisesTheLevelWhenThereIsRoom() {
        #expect(CodeGenerator.autoLevel(for: "hello") == .H)
        #expect(CodeGenerator.autoLevel(for: "a") == .H)
    }

    @Test func autoStaysAtMediumWhenFull() throws {
        // 14 bytes fill version 1 at M exactly; Q would need version 2.
        #expect(CodeGenerator.autoLevel(for: "abcdefghijklmn") == .M)
        #expect(try version("abcdefghijklmn", .auto) == 1)
    }

    @Test func manualLevelsAreUnchanged() throws {
        for c in [Correction.L, .M, .Q, .H] {
            let direct = try CodeGenerator.generate(text: "hello", symbology: .qr, correction: c)
            #expect(CodeGenerator.qrVersion(of: direct) != nil)
        }
        // H at 5 bytes is still version 1; L stays version 1; a long text grows with level.
        let long = String(repeating: "x", count: 100)
        #expect(try version(long, .L) <= version(long, .M))
        #expect(try version(long, .M) <= version(long, .Q))
        #expect(try version(long, .Q) <= version(long, .H))
    }

    @Test func aztecAutoIsTwentyThreePercent() throws {
        #expect(Correction.auto.aztecPercent == 23)
        let a = try CodeGenerator.generate(text: "hello", symbology: .aztec, correction: .auto)
        let m = try CodeGenerator.generate(text: "hello", symbology: .aztec, correction: .M)
        #expect(a == m)
    }
}

@MainActor
@Suite struct MakeLinkCompactionModelTests {
    private func model(_ link: String, tracking: [TrackingParam] = []) -> MakeScreenModel {
        let m = MakeScreenModel(prefill: .link(link))
        m.trackingList = tracking
        return m
    }

    @Test func capitalsAreUsedWhenTheCodeShrinks() async throws {
        let long = "https://example.com"
        let m = model(long)
        await m.refresh()
        let typedV = try #require(CodeGenerator.qrVersion(of: CodeGenerator.generate(text: long, symbology: .qr, correction: .auto)))
        let capsV = try #require(CodeGenerator.qrVersion(of: CodeGenerator.generate(text: "HTTPS://EXAMPLE.COM", symbology: .qr, correction: .auto)))
        #expect(capsV < typedV)
        #expect(m.smallerCodeAvailable)
        #expect(m.payloadText == "HTTPS://EXAMPLE.COM")
        #expect(m.codeAccessibilityLabel == "QR code for https://example.com")
    }

    @Test func switchOffKeepsTheTypedText() async {
        let m = model("https://example.com/")
        m.useSmallerCode = false
        await m.refresh()
        #expect(m.smallerCodeAvailable)
        #expect(m.payloadText == "https://example.com/")
    }

    @Test func nothingChangesWhenCapitalsGainNothing() async {
        let link = "https://example.com/some/long/lowercase/path/that/goes/on?and=on&on=and"
        let m = model(link)
        await m.refresh()
        #expect(!m.smallerCodeAvailable)
        #expect(m.payloadText == link)
    }

    @Test func otherKindsAndSymbologiesAreNeverRewritten() async {
        let m = model("https://example.com")
        m.symbology = .aztec
        await m.refresh()
        #expect(m.payloadText == "https://example.com")
        let t = MakeScreenModel(prefill: .text("https://example.com"))
        await t.refresh()
        #expect(t.payloadText == "https://example.com")
    }

    @Test func historyStoresTheTextActuallyEncoded() async {
        let app = AppModel.preview(state: .unlocked, history: [])
        await app.start()
        let m = model("https://example.com")
        await m.refresh()
        await m.recordUse(in: app)
        await app.refreshHistory()
        #expect(app.historyItems.first { $0.kind == .made }?.payload == "HTTPS://EXAMPLE.COM")
    }

    @Test func trackingIsOfferedNeverApplied() async {
        let list = [TrackingParam(name: "utm_*", source: "x")]
        let m = model("https://example.com/p?id=1&utm_source=a", tracking: list)
        await m.refresh()
        #expect(m.trackingTags == ["utm_source"])
        #expect(m.trackingFreeLink == "https://example.com/p?id=1")
        #expect(m.form.link == "https://example.com/p?id=1&utm_source=a")
        m.removeTrackingTags()
        #expect(m.form.link == "https://example.com/p?id=1")
        await m.refresh()
        #expect(m.trackingTags.isEmpty)
    }

    @Test func noTrackingOfferOutsideLinks() async {
        let m = MakeScreenModel(prefill: .text("https://example.com/?utm_source=a"))
        m.trackingList = [TrackingParam(name: "utm_*", source: "x")]
        await m.refresh()
        #expect(m.trackingTags.isEmpty)
        m.removeTrackingTags()
        #expect(m.form.link.isEmpty)
    }
}

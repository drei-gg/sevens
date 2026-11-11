import Foundation
import SwiftUI
import Testing
@testable import SevenKit
import SevenKitCore

@Suite struct StoreContentTests {
    // BeforeYouBuy: the price comes from the store at runtime (G7).
    @Test func priceIsSubstitutedWhenKnown() {
        let content = BeforeYouBuyContent(title: "t", paragraphs: [
            "Plain.", .init("Costs {price}, once.", withoutPrice: "A one-time price."),
        ])
        #expect(content.resolvedParagraphs(price: "X1") == ["Plain.", "Costs X1, once."])
    }

    @Test func missingPriceUsesTheParagraphFallbackThenTheDefault() {
        let content = BeforeYouBuyContent(title: "t", paragraphs: [
            .init("Costs {price}.", withoutPrice: "One-time price."), "Also {price}.",
        ])
        let out = content.resolvedParagraphs(price: nil)
        #expect(out[0] == "One-time price.")
        #expect(out[1] == "Then a one-time price, if you want to keep it.")
        #expect(!out.joined().contains("{price}"))
    }

    @Test func customFallbackSentence() {
        let content = BeforeYouBuyContent(title: "t", paragraphs: ["{price}"], priceFreeFallback: "Keep your money.")
        #expect(content.resolvedParagraphs(price: nil) == ["Keep your money."])
    }

    @Test func statusLines() {
        let end = Date(timeIntervalSince1970: 1_800_604_800)
        let lines = [
            AboutStatus.line(for: .notStarted, day: nil, totalDays: 7),
            AboutStatus.line(for: .trial(endsAt: end), day: 3, totalDays: 7),
            AboutStatus.line(for: .trial(endsAt: end), day: nil, totalDays: 7),
            AboutStatus.line(for: .expired(endedAt: end), day: nil, totalDays: 7),
            AboutStatus.line(for: .unlocked, day: nil, totalDays: 7),
            AboutStatus.line(for: .pending, day: nil, totalDays: 7),
        ]
        #expect(lines[1].contains("3") && lines[1].contains("7"))
        for l in lines {
            #expect(!l.isEmpty)
            #expect(l.range(of: "safe", options: .caseInsensitive) == nil)
        }
    }

    @Test func mailtoLink() {
        #expect(AboutLinks.mailto(address: "help@example.org")?.absoluteString == "mailto:help@example.org")
        #expect(AboutLinks.mailto(address: "help@example.org", subject: "Hi there")?.absoluteString
                == "mailto:help@example.org?subject=Hi%20there")
        #expect(AboutLinks.mailto(address: "") == nil)
        #expect(AboutLinks.mailto(address: "nobody") == nil)
    }

    @Test func flagsAreSetOnce() {
        let defaults = makeTestDefaults()
        #expect(!SevenFlags.beforeYouBuyShown(defaults))
        SevenFlags.markBeforeYouBuyShown(defaults)
        #expect(SevenFlags.beforeYouBuyShown(defaults))
        #expect(!SevenFlags.trialEndedShown(defaults))
    }

    func bundle(_ dict: [String: String]) throws -> Bundle {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("seven-\(UUID().uuidString).bundle")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let plist = try PropertyListSerialization.data(fromPropertyList: dict, format: .xml, options: 0)
        try plist.write(to: dir.appendingPathComponent("Info.plist"))
        return try #require(Bundle(url: dir))
    }

    @Test func configReadsInfoPlistKeys() throws {
        let b = try bundle(["SevensProductTrial": "a.trial", "SevensProductUnlock": "a.unlock", "SevensAppGroup": "group.a"])
        #expect(SevenConfig(bundle: b) == SevenConfig(trialProductID: "a.trial", unlockProductID: "a.unlock", appGroup: "group.a"))
    }

    @Test func configRejectsUnexpandedBuildSettings() throws {
        let b = try bundle(["SevensProductTrial": "$(X).trial7", "SevensProductUnlock": "a.unlock"])
        #expect(SevenConfig(bundle: b) == nil)
    }

    #if canImport(UIKit)
    @Test func tokensResolveToTheDocumentedHex() {
        func rgb(_ color: Color, _ style: UIUserInterfaceStyle) -> Int {
            let ui = UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            ui.getRed(&r, green: &g, blue: &b, alpha: &a)
            return (Int((r * 255).rounded()) << 16) | (Int((g * 255).rounded()) << 8) | Int((b * 255).rounded())
        }
        #expect(rgb(Tokens.paper, .light) == 0xF3EFE6 && rgb(Tokens.paper, .dark) == 0x121110)
        #expect(rgb(Tokens.ink, .light) == 0x161513 && rgb(Tokens.ink, .dark) == 0xEEE9DF)
        #expect(rgb(Tokens.muted, .light) == 0x55504A && rgb(Tokens.muted, .dark) == 0xA8A196)
        // Every token resolves to its Palette value, so the Linux contrast gate covers what renders.
        let tokens: [(Color, Palette.Pair)] = [
            (Tokens.paper, Palette.paper), (Tokens.card, Palette.card), (Tokens.ink, Palette.ink),
            (Tokens.onInk, Palette.onInk), (Tokens.muted, Palette.muted), (Tokens.red, Palette.red),
            (Tokens.onRed, Palette.onRed), (Tokens.caution, Palette.caution), (Tokens.danger, Palette.danger),
        ]
        for (color, pair) in tokens {
            #expect(rgb(color, .light) == pair.light && rgb(color, .dark) == pair.dark)
        }
    }
    #endif

    @Test func verdictStylesUseTheDocumentedSymbolsAndStatusTokens() {
        #expect(Tokens.VerdictStyle.normal.symbolName == "checkmark.circle")
        #expect(Tokens.VerdictStyle.careful.symbolName == "exclamationmark.triangle")
        #expect(Tokens.VerdictStyle.likelyScam.symbolName == "xmark.octagon")
        #expect(Tokens.VerdictStyle.normal.color == Tokens.muted)
        #expect(Tokens.VerdictStyle.careful.color == Tokens.caution)
        #expect(Tokens.VerdictStyle.likelyScam.color == Tokens.danger)
        #expect(Tokens.VerdictStyle.likelyScam.color != Tokens.red)
    }
}

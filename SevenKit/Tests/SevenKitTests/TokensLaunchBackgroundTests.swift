import Foundation
import Testing
@testable import SevenKit

/// The launch screen has no splash (SPEC §1): its background colour must equal the paper
/// token the scan screen draws, in light and dark, so launch shows no flash of another colour.
@Suite struct TokensLaunchBackgroundTests {
    private func components(_ appearance: String?) throws -> Int {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("apps/qr/Brand/Brand.xcassets/LaunchBackground.colorset/Contents.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        let colors = try #require(json?["colors"] as? [[String: Any]])
        let entry = try #require(colors.first { entry in
            let value = (entry["appearances"] as? [[String: String]])?.first?["value"]
            return value == appearance
        })
        let parts = try #require((entry["color"] as? [String: Any])?["components"] as? [String: String])
        func channel(_ key: String) throws -> Int {
            let text = try #require(parts[key])
            return try #require(Int(text.dropFirst(2), radix: 16))
        }
        return try (channel("red") << 16) | (channel("green") << 8) | channel("blue")
    }

    @Test func launchBackgroundEqualsPaperInBothThemes() throws {
        #expect(try components(nil) == Tokens.Hex.paper.light)
        #expect(try components("dark") == Tokens.Hex.paper.dark)
    }
}

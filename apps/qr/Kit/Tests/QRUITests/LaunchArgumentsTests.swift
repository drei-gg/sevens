import Foundation
import Testing
import QRCore
import SevenKit
@testable import QRUI

@Suite struct LaunchArgumentsTests {
    @Test func parsesEveryArgument() {
        let b64 = Data("https://example.org".utf8).base64EncodedString()
        let parsed = LaunchArguments.parse(["app", "-qr.inject", b64, "-qr.skipFirstLaunch",
                                            "-qr.entitlement", "expired", "-qr.resetHistory"])
        #expect(parsed.inject == Array("https://example.org".utf8))
        #expect(parsed.skipFirstLaunch && parsed.resetHistory)
        #expect(parsed.entitlement == "expired")
    }

    @Test func emptyAndUnknownArgumentsGiveDefaults() {
        #expect(LaunchArguments.parse([]) == LaunchArguments())
        #expect(LaunchArguments.parse(["-other", "x", "-NSDocumentRevisionsDebugMode", "YES"]) == LaunchArguments())
    }

    @Test func badValuesAreIgnored() {
        #expect(LaunchArguments.parse(["-qr.inject"]).inject == nil)
        #expect(LaunchArguments.parse(["-qr.inject", "!!!not base64!!!"]).inject == nil)
        #expect(LaunchArguments.parse(["-qr.inject", ""]).inject == nil)
        #expect(LaunchArguments.parse(["-qr.entitlement", "bogus"]).entitlement == nil)
        #expect(LaunchArguments.parse(["-qr.entitlement"]).entitlement == nil)
    }

    @Test func valueIsNotReadAsAnotherFlag() {
        // A missing value must not swallow the next flag.
        let parsed = LaunchArguments.parse(["-qr.entitlement", "-qr.skipFirstLaunch"])
        #expect(parsed.entitlement == nil)
        #expect(parsed.skipFirstLaunch)
    }

    @Test func base64AcceptsUnpaddedAndURLSafe() {
        let bytes: [UInt8] = [0xfb, 0xff, 0xfe, 0x01]
        let standard = Data(bytes).base64EncodedString()           // "+//+AQ=="
        let urlSafe = standard.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        #expect(LaunchArguments.decodeBase64(standard) == bytes)
        #expect(LaunchArguments.decodeBase64(urlSafe) == bytes)
    }

    @Test func binaryPayloadDecodesToBytes() {
        let bytes: [UInt8] = [0xff, 0xfe, 0x00, 0x41]
        let parsed = LaunchArguments.parse(["-qr.inject", Data(bytes).base64EncodedString()])
        #expect(parsed.inject == bytes)
        #expect(String(bytes: bytes, encoding: .utf8) == nil)
    }

    @Test func oversizeInjectIsIgnored() {
        let big = Data(repeating: 0x41, count: LaunchArguments.maxInjectBytes + 1).base64EncodedString()
        #expect(LaunchArguments.parse(["-qr.inject", big]).inject == nil)
    }

    #if DEBUG
    @Test func entitlementNamesMatchSevenKitPresets() {
        #expect(Set(LaunchArguments.entitlementNames) == Set(LocalSource.Preset.allCases.map(\.rawValue)))
    }
    #endif
}

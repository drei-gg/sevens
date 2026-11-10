import Foundation
import Testing
@testable import QRCore

/// SPEC §4.3: a downloaded bundle is used only when it passes the same shape rules as the files
/// that ship, and only when it is newer.
@Suite struct ListsBundleTests {
    static let shipped = BundledData.load()
    static var newer: Int { shipped.lists.version + 1 }

    @Test func listsBundledFilesFormAValidBundle() throws {
        try BundledData.validate(try ListsBundleSupport.bundle(version: Self.newer))
    }

    @Test func listsBundleRoundTripsThroughJSON() throws {
        let bundle = try ListsBundleSupport.bundle(version: Self.newer)
        let again = try JSONDecoder().decode(ListsBundle.self, from: try ListsBundleSupport.bytes(bundle))
        #expect(again == bundle)
    }

    @Test func listsUnknownSchemaIsRefused() throws {
        var bundle = try ListsBundleSupport.bundle(version: Self.newer)
        bundle.schema = 2
        #expect(throws: ListsBundleError.unknownSchema(2)) { try BundledData.validate(bundle) }
    }

    @Test func listsBadBuiltDateIsRefused() throws {
        var bundle = try ListsBundleSupport.bundle(version: Self.newer, built: "yesterday")
        #expect(throws: ListsBundleError.badShape("built")) { try BundledData.validate(bundle) }
        bundle.built = "2026-13-40"
        #expect(throws: ListsBundleError.badShape("built")) { try BundledData.validate(bundle) }
    }

    @Test func listsEachBrokenFileNamesItself() throws {
        func broken(_ change: (inout ListsFiles) -> Void) throws -> ListsBundleError? {
            var bundle = try ListsBundleSupport.bundle(version: Self.newer)
            change(&bundle.files)
            do { try BundledData.validate(bundle) } catch let error as ListsBundleError { return error } catch { return nil }
            return nil
        }
        #expect(try broken { $0.brands = Array($0.brands.prefix(10)) } == .badShape("brands"))
        #expect(try broken { $0.brands[0].keywords = ["ab"] } == .badShape("brands"))
        #expect(try broken { $0.brands[0].keywords = ["Paypal"] } == .badShape("brands"))
        #expect(try broken { $0.brands[0].domains = ["no-dot"] } == .badShape("brands"))
        #expect(try broken { $0.shorteners = ["bit.ly"] } == .badShape("shorteners"))
        #expect(try broken { $0.shorteners[1] = $0.shorteners[0] } == .badShape("shorteners"))
        #expect(try broken { $0.riskyTLDs[0].source = "http://x" } == .badShape("riskyTLDs"))
        #expect(try broken { $0.baitWords = [] } == .badShape("baitWords"))
        #expect(try broken { $0.paymentNetworks.emv[0].party = "robot" } == .badShape("paymentNetworks"))
        #expect(try broken { $0.confusables["0"] = "O" } == .badShape("confusables"))
        #expect(try broken { $0.publicSuffixList = "com" } == .badShape("publicSuffixList"))
        #expect(try broken { $0.rdap.services = [] } == .badShape("rdap"))
        #expect(try broken { $0.trackingParams = [TrackingParam(name: "UTM_*", source: "https://example.org/utm")] } == .badShape("trackingParams"))
        #expect(try broken { $0.trackingParams = [TrackingParam(name: "utm_*", source: "http://example.org")] } == .badShape("trackingParams"))
    }

    @Test func listsTrackingNamesAllowLettersDigitsUnderscoreAndOneStar() throws {
        var bundle = try ListsBundleSupport.bundle(version: Self.newer)
        bundle.files.trackingParams = [TrackingParam(name: "utm_*", source: "https://example.org/utm"),
                                       TrackingParam(name: "fbclid", source: "https://example.org/fb"),
                                       TrackingParam(name: "mc_cid2", source: "https://example.org/mc")]
        try BundledData.validate(bundle)
        bundle.files.trackingParams = [TrackingParam(name: "a-b", source: "https://example.org/x")]
        #expect(throws: ListsBundleError.badShape("trackingParams")) { try BundledData.validate(bundle) }
        bundle.files.trackingParams = [TrackingParam(name: "*", source: "https://example.org/x")]
        #expect(throws: ListsBundleError.badShape("trackingParams")) { try BundledData.validate(bundle) }
    }

    @Test func listsBundleDataKeepsCurrenciesAndTakesTheHeader() throws {
        let bundle = try ListsBundleSupport.bundle(version: Self.newer, built: "2027-01-02")
        let data = BundledData(bundle: bundle, base: Self.shipped)
        #expect(data.lists == ListsVersion(schema: 1, version: Self.newer, built: "2027-01-02"))
        #expect(data.currencies == Self.shipped.currencies)
        #expect(data.brands == bundle.files.brands)
        #expect(data.shorteners == Set(bundle.files.shorteners))
        #expect(data.rdap.baseURL(forTLD: "com") != nil)
        #expect(data.publicSuffixes.match(hostASCII: "www.example.co.uk").registrableDomain == "example.co.uk")
    }

    @Test func listsMissingTrackingParamsFallBackToTheBundledOnes() throws {
        var bundle = try ListsBundleSupport.bundle(version: Self.newer)
        bundle.files.trackingParams = nil
        try BundledData.validate(bundle)
        #expect(BundledData(bundle: bundle, base: Self.shipped).trackingParams == Self.shipped.trackingParams)
    }
}

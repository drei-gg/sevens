import Foundation
@_exported import QRFiles

// QR's logic is the Rust crate apps/qr/core; its Swift bindings (Generated/QRCoreFFI.swift) compile
// into this module. This file keeps the Swift spellings QRUI and the extensions were written
// against (SignalID, SMSInfo, hostASCII, `Signal(.shortener)`, `BundledData.shared`, the parser and
// file enums) and only converts: Int to the core's Int64, [UInt8] to Data, labels, default
// arguments. No rule, check or format lives here. Storage (ListsStore, the History and known-address
// stores), networking, the lists signature, Vision, CoreImage and every pixel stay native.

// MARK: names

public typealias SignalID = SignalId
public typealias SMSInfo = SmsInfo
public typealias RGBColor = RgbColor
public typealias RiskyTLD = RiskyTld
public typealias EMVNetwork = EmvNetwork
public typealias HTMLSniff = HtmlSniff

public extension PaymentInfo {
    typealias Format = PaymentFormat
    typealias Party = PaymentParty
    typealias Checksum = PaymentChecksum
}

public extension HistoryItem {
    typealias Kind = HistoryKind
    typealias PayloadEncoding = QRCore.PayloadEncoding
}

public extension KnownAddresses {
    typealias Entry = KnownAddressEntry
}

// MARK: scanned code

public extension ScannedCode {
    /// A text code; `bytes` are its UTF-8.
    init(text: String, symbology: Symbology = .qr) {
        self = scannedCodeFromText(text: text, symbology: symbology)
    }

    init(text: String?, bytes: [UInt8], symbology: Symbology) {
        self.init(text: text, bytes: Data(bytes), symbology: symbology)
    }
}

// MARK: verdict

extension Level: Comparable {
    /// 0 normal, 1 careful, 2 likely scam: the order the cases are declared in.
    public var rawValue: Int { Self.allCases.firstIndex(of: self) ?? 0 }
    public static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
}

public extension SignalId {
    /// The Swift spelling stored in History files ("plainHTTP", "riskyTLD", …).
    var rawValue: String { signalIdRaw(id: self) }
    init?(rawValue: String) {
        guard let id = signalIdFromRaw(raw: rawValue) else { return nil }
        self = id
    }
    var severity: Severity { signalIdSeverity(id: self) }
}

public extension Signal {
    init(_ id: SignalId, _ args: [String: String] = [:]) {
        self.init(id: id, args: args)
    }
    var severity: Severity { id.severity }
}

public extension Array where Element == Signal {
    /// Strong first, then warning, then info, each in declaration order; stable.
    func inStableOrder() -> [Signal] { signalsInStableOrder(signals: self) }
}

public extension Analysis {
    /// Every signal of this analysis and its embedded analyses, own first.
    var allSignals: [Signal] { analysisAllSignals(analysis: self) }
}

// MARK: payloads

public extension Payload {
    /// "link", "payment", … used by fixtures and history.
    var typeID: String { payloadTypeId(payload: self) }
    /// Flat view, used by fixtures and CSV export.
    var fields: [String: String] { payloadFields(payload: self) }

    static func link(_ info: LinkInfo) -> Payload { .link(info: info) }
    static func appLink(_ info: AppLinkInfo) -> Payload { .appLink(info: info) }
    static func payment(_ info: PaymentInfo) -> Payload { .payment(info: info) }
    static func crypto(_ info: CryptoInfo) -> Payload { .crypto(info: info) }
    static func wifi(_ info: WiFiInfo) -> Payload { .wifi(info: info) }
    static func contact(_ info: ContactInfo) -> Payload { .contact(info: info) }
    static func event(_ info: EventInfo) -> Payload { .event(info: info) }
    static func phone(_ info: PhoneInfo) -> Payload { .phone(info: info) }
    static func sms(_ info: SmsInfo) -> Payload { .sms(info: info) }
    static func email(_ info: EmailInfo) -> Payload { .email(info: info) }
    static func geo(_ info: GeoInfo) -> Payload { .geo(info: info) }
    static func text(_ info: TextInfo) -> Payload { .text(info: info) }
}

public extension MakeRequest {
    static func link(_ text: String) -> MakeRequest { .link(text: text) }
    static func text(_ text: String) -> MakeRequest { .text(text: text) }
    static func wifi(_ info: WiFiInfo) -> MakeRequest { .wifi(info: info) }
    static func contact(_ info: ContactInfo) -> MakeRequest { .contact(info: info) }
    static func event(_ info: EventInfo) -> MakeRequest { .event(info: info) }
    static func email(_ info: EmailInfo) -> MakeRequest { .email(info: info) }
    static func sms(_ info: SmsInfo) -> MakeRequest { .sms(info: info) }
    static func phone(_ number: String) -> MakeRequest { .phone(number: number) }
    static func geo(_ info: GeoInfo) -> MakeRequest { .geo(info: info) }
}

public extension LinkInfo {
    init(original: String, scheme: String, userinfo: String? = nil, hostASCII: String,
         hostUnicode: String, registrableDomain: String? = nil, publicSuffix: String? = nil,
         suffixIsPrivate: Bool = false, port: Int? = nil, pathAndQuery: String = "",
         isIPAddress: Bool = false, normalized: String) {
        self.init(original: original, scheme: scheme, userinfo: userinfo, hostAscii: hostASCII,
                  hostUnicode: hostUnicode, registrableDomain: registrableDomain, publicSuffix: publicSuffix,
                  suffixIsPrivate: suffixIsPrivate, port: port.map(Int64.init), pathAndQuery: pathAndQuery,
                  isIpAddress: isIPAddress, normalized: normalized)
    }
    /// xn-- form, lowercased.
    var hostASCII: String { get { hostAscii } set { hostAscii = newValue } }
    var isIPAddress: Bool { get { isIpAddress } set { isIpAddress = newValue } }
}

public extension CryptoInfo {
    init(scheme: String? = nil, network: String, address: String, amount: String? = nil,
         label: String? = nil, message: String? = nil, chainID: String? = nil, isLightningInvoice: Bool = false) {
        self.init(scheme: scheme, network: network, address: address, amount: amount, label: label,
                  message: message, chainId: chainID, isLightningInvoice: isLightningInvoice)
    }
    var chainID: String? { get { chainId } set { chainId = newValue } }
    /// The address in groups of four, for display.
    var addressGroups: [String] { cryptoAddressGroups(info: self) }
}

public extension WiFiInfo {
    var isEnterprise: Bool { wifiIsEnterprise(info: self) }
    var isOpen: Bool { wifiIsOpen(info: self) }
}

public extension EventInfo {
    var timeZoneID: String? { get { timeZoneId } set { timeZoneId = newValue } }
}

// MARK: the lists

public extension BundledData {
    /// The lists in use: the bundled ones, or the saved download when it is newer. The first use
    /// hands the core what ListsStore kept.
    static var shared: BundledData {
        _ = sharedStart
        return bundledDataShared()
    }

    /// Swaps in the bundled lists or a newer download, whichever has the higher version. QRUI
    /// calls this after accepting a download (with its bytes) or after a reset (with nil).
    static func reload(downloaded: Data?) {
        _ = sharedStart
        bundledDataReload(downloaded: downloaded)
    }

    /// Starts loading `shared` on a background task. Cheap; call once at launch.
    static func preload() {
        _ = Task.detached(priority: .utility) { _ = BundledData.shared }
    }

    /// Only what shipped with the app.
    static func load() -> BundledData { bundledDataLoad(downloaded: nil) }

    /// The bundled lists, or `downloaded` when it is valid and newer.
    static func load(downloaded: Data?) -> BundledData { bundledDataLoad(downloaded: downloaded) }

    /// The version of the lists that shipped with the app, whatever is saved.
    static func bundledListsVersion() -> ListsVersion { QRCore.bundledListsVersion() }

    var lists: ListsVersion { listsVersion() }
    var brands: [Brand] { brandList() }
    var trackingParams: [TrackingParam] { trackingParamList() }
    var paymentNetworks: PaymentNetworks { paymentNetworkList() }
    var currencies: [String: String] { currencyTable() }
    var publicSuffixes: PublicSuffixList { PublicSuffixList(data: self) }
    var rdap: RDAPBootstrap { RDAPBootstrap(data: self) }
}

/// Loaded once: the saved download, read by the native store, handed to the core.
private let sharedStart: Void = bundledDataReload(downloaded: ListsStore().read())

/// The Public Suffix List inside a `BundledData`.
public struct PublicSuffixList: Sendable {
    public typealias Match = PublicSuffixMatch
    let data: BundledData
    public func match(hostASCII: String) -> PublicSuffixMatch { data.publicSuffixMatch(hostAscii: hostASCII) }
}

/// IANA's RDAP bootstrap inside a `BundledData`.
public struct RDAPBootstrap: Sendable {
    let data: BundledData
    public func baseURL(forTLD tld: String) -> String? { data.rdapBaseUrl(tld: tld) }
    public func queryURL(forDomain asciiDomain: String) -> String? { data.rdapQueryUrl(asciiDomain: asciiDomain) }
}

extension ListsVersion: Comparable {
    public static func < (a: ListsVersion, b: ListsVersion) -> Bool { a.version < b.version }
}

public enum ListsBundle {
    /// Larger downloads are refused before decoding.
    public static let maxBytes = Int(listsMaxBytes())
}

public extension TrackingParam {
    /// Case-insensitive; `utm_*` matches `utm_source` but not `utm` or `xutm_source`.
    func matches(_ key: String) -> Bool { trackingParamMatches(param: self, key: key) }
}

// MARK: addresses

public extension WebAddress {
    var assumedHTTPS: Bool { get { assumedHttps } set { assumedHttps = newValue } }
    static func scheme(of text: String) -> String? { webAddressScheme(text: text) }
    static func parse(_ text: String, psl: PublicSuffixList) -> WebAddress? { webAddressParse(text: text, data: psl.data) }
    static func parseSchemeless(_ text: String, psl: PublicSuffixList) -> WebAddress? {
        webAddressParseSchemeless(text: text, data: psl.data)
    }
    static func resolve(_ reference: String, against base: LinkInfo, psl: PublicSuffixList) -> WebAddress? {
        webAddressResolve(reference: reference, base: base, data: psl.data)
    }
}

public enum Punycode {
    public static func encode(_ label: String) -> String? { punycodeEncode(label: label) }
    public static func decode(_ label: String) -> String? { punycodeDecode(label: label) }
}

// MARK: analysis

public extension Analyzer {
    convenience init(data: BundledData) {
        self.init(data: data, known: KnownAddresses())
    }
    func analyze(_ code: ScannedCode) -> Analysis { analyze(code: code) }
    func level(for signals: [Signal]) -> Level { level(signals: signals) }
}

// MARK: parsers

public enum PhoneParser { public static func parse(_ text: String) -> PhoneInfo? { phoneParse(text: text) } }
public enum SMSParser { public static func parse(_ text: String) -> SmsInfo? { smsParse(text: text) } }
public enum EmailParser { public static func parse(_ text: String) -> EmailInfo? { emailParse(text: text) } }
public enum GeoParser { public static func parse(_ text: String) -> GeoInfo? { geoParse(text: text) } }
public enum WiFiParser { public static func parse(_ text: String) -> WiFiInfo? { wifiParse(text: text) } }
public enum ContactParser { public static func parse(_ text: String) -> ContactInfo? { contactParse(text: text) } }
public enum EventParser { public static func parse(_ text: String) -> EventInfo? { eventParse(text: text) } }
public enum EPCParser { public static func parse(_ text: String) -> PaymentInfo? { epcParse(text: text) } }
public enum UPIParser { public static func parse(_ text: String) -> PaymentInfo? { upiParse(text: text) } }
public enum SwissQRParser { public static func parse(_ text: String) -> PaymentInfo? { swissQrParse(text: text) } }
public enum EMVParser {
    public static func parse(_ text: String, networks: [EmvNetwork], currencies: [String: String]) -> PaymentInfo? {
        emvParse(text: text, networks: networks, currencies: currencies)
    }
}
public enum CryptoURIParser {
    public static func parse(_ text: String, schemes: [CryptoScheme]) -> CryptoInfo? { cryptoUriParse(text: text, schemes: schemes) }
}
public enum CryptoAddress { public static func detect(_ text: String) -> CryptoInfo? { cryptoAddressDetect(text: text) } }
public enum IBAN { public static func isValid(_ iban: String) -> Bool { ibanIsValid(iban: iban) } }
public enum CRC16 {
    public static func ccittFalse<S: Sequence>(_ bytes: S) -> UInt16 where S.Element == UInt8 { crc16(bytes: Data(bytes)) }
}

// MARK: Make

public enum PayloadEncoder {
    public static func encode(_ request: MakeRequest) -> String { payloadEncode(request: request) }
}

public enum PhoneNumber {
    /// `+` and digits (`+639171234567`) from `+63 (917) 123-4567` or `0063 917 123 4567`; nil
    /// for anything that isn't an international number (SPEC §8).
    public static func international(_ text: String) -> String? { phoneNumberInternational(text: text) }
}

public enum LinkCompactor {
    public static func trimmed(_ link: String) -> String { linkTrimmed(link: link) }
    public static func capitalized(_ link: String) -> String? { linkCapitalized(link: link) }
    public static func compacted(_ link: String) -> String? { linkCompacted(link: link) }
    public static func trackingParameters(in link: String, list: [TrackingParam]) -> [String] {
        linkTrackingParameters(link: link, list: list)
    }
    public static func removingTracking(from link: String, list: [TrackingParam]) -> String? {
        linkRemovingTracking(link: link, list: list)
    }
}

// MARK: render data

public extension BitMatrix {
    /// A matrix whose bits don't fill width × height is empty (0 × 0).
    init(width: Int, height: Int, bits: [Bool]) {
        self = bitMatrixNew(width: Int64(width), height: Int64(height), bits: bits)
    }
    init(width: Int, height: Int) {
        self.init(width: width, height: height, bits: Array(repeating: false, count: max(0, width) * max(0, height)))
    }
    var columns: Int { Int(width) }
    var rows: Int { Int(height) }
    /// Out of range reads as light.
    subscript(x: Int, y: Int) -> Bool {
        guard x >= 0, y >= 0, x < columns, y < rows else { return false }
        return bits[y * columns + x]
    }
}

public extension RgbColor {
    static let black = RgbColor(red: 0, green: 0, blue: 0)
    static let white = RgbColor(red: 1, green: 1, blue: 1)
    /// "#rrggbb".
    var hex: String { rgbHex(color: self) }
}

public enum Contrast {
    public static func ratio(_ a: RgbColor, _ b: RgbColor) -> Double { contrastRatio(a: a, b: b) }
    public static func warnings(foreground: RgbColor, background: RgbColor) -> [ContrastWarning] {
        contrastWarnings(foreground: foreground, background: background)
    }
}

public enum QuietZone {
    public static func modules(for symbology: Symbology) -> (horizontal: Int, vertical: Int) {
        let m = quietZoneModules(symbology: symbology)
        return (Int(m.horizontal), Int(m.vertical))
    }
    public static func add(to matrix: BitMatrix, horizontal: Int, vertical: Int) -> BitMatrix {
        quietZoneAdd(matrix: matrix, horizontal: Int64(horizontal), vertical: Int64(vertical))
    }
}

public enum SVGWriter {
    public static func svg(_ matrix: BitMatrix, moduleSize: Int, foreground: RgbColor, background: RgbColor?) -> String {
        QRCore.svg(matrix: matrix, moduleSize: Int64(moduleSize), foreground: foreground, background: background)
    }
}

public extension PaperSize {
    /// Points.
    var size: (width: Double, height: Double) { let p = paperSizePoints(paper: self); return (p.width, p.height) }
    static func forRegion(_ regionCode: String?) -> PaperSize { paperSizeForRegion(regionCode: regionCode) }
}

public extension SheetGrid {
    var columns: Int { Int(sheetGridColumns(grid: self)) }
    var rows: Int { Int(sheetGridRows(grid: self)) }
}

public enum SheetLayout {
    public static let margin: Double = sheetMargin()
    public static let gutter: Double = sheetGutter()
    public static func cells(grid: SheetGrid, paper: PaperSize) -> [SheetCell] { sheetCells(grid: grid, paper: paper) }
}

// MARK: Peek's pure half

public enum HTMLSniffer {
    public static func sniff(body: [UInt8], contentType: String?) -> HtmlSniff { htmlSniff(body: Data(body), contentType: contentType) }
}

public enum RDAPResponse {
    public static func registrationDate(json: Data) -> Date? { rdapRegistrationDate(json: json) }
}

public extension Analysis {
    /// Peek can look at this scan (SPEC §6): an http or https link. https is fetched; a plain
    /// http link isn't, and only has its domain's age looked up (§6.1).
    var canPeek: Bool { peekAvailable(analysis: self) }
}

public enum PeekAge {
    /// The address whose registration age a Peek reports (SPEC §6.2): the last hop that
    /// answered when it has a registrable domain, otherwise the scanned link.
    public static func link(original: Analysis, capture: PeekCapture, data: BundledData) -> LinkInfo? {
        peekAgeLink(original: original, capture: capture, data: data)
    }
}

public enum DomainAge {
    /// Whole days, never negative.
    public static func days(registeredAt: Date, now: Date) -> Int {
        Int(clamping: domainAgeDays(registeredAt: registeredAt, now: now))
    }
}

// MARK: known addresses

public extension KnownAddresses {
    static let maxEntries = Int(knownAddressesMaxEntries())
    /// The signals that come only from a bundled list; only these can be overridden.
    static let listBased: Set<SignalId> = Set(knownAddressesListBased())

    var isEmpty: Bool { entries.isEmpty }
    func contains(_ registrableDomain: String) -> Bool { knownAddressesContains(known: self, domain: registrableDomain) }
    mutating func add(_ registrableDomain: String, at date: Date) {
        self = knownAddressesAdd(known: self, domain: registrableDomain, at: date)
    }
    mutating func remove(_ registrableDomain: String) { self = knownAddressesRemove(known: self, domain: registrableDomain) }
    mutating func removeAll() { entries = [] }
    /// The registrable domain to offer "I know this address" for, or nil.
    func offer(for analysis: Analysis) -> String? { knownAddressesOffer(known: self, analysis: analysis) }
    func apply(to signals: [Signal], payload: Payload) -> [Signal] {
        knownAddressesApply(known: self, signals: signals, payload: payload)
    }
}

public enum KnownAddressesFile {
    public typealias Decoded = KnownAddressesDecoded
    public static let fileName = knownAddressesFileName()
    public static func decode(_ data: Data) -> KnownAddressesDecoded { knownAddressesFileDecode(bytes: data) }
    public static func encode(_ known: KnownAddresses) throws -> Data { knownAddressesFileEncode(known: known) }
}

// MARK: history

extension HistoryItem: Identifiable {}

public extension HistoryItem {
    init(id: UUID = UUID(), kind: HistoryKind, payload: String, payloadEncoding: PayloadEncoding = .text,
         symbology: Symbology, typeID: String, level: Level, signalIDs: [String], date: Date, note: String? = nil) {
        self.init(id: id, kind: kind, payload: payload, payloadEncoding: payloadEncoding, symbology: symbology,
                  typeId: typeID, level: level, signalIds: signalIDs, date: date, note: note)
    }
    init(analysis: Analysis, kind: HistoryKind, date: Date) {
        self = historyItemFromAnalysis(analysis: analysis, kind: kind, date: date)
    }
    var typeID: String { get { typeId } set { typeId = newValue } }
    var signalIDs: [String] { get { signalIds } set { signalIds = newValue } }
    /// What to analyse again: the text, or the decoded bytes.
    var scannedCode: ScannedCode { historyItemScannedCode(item: self) }
}

public extension HistoryDecoded {
    static func items(_ items: [HistoryItem]) -> HistoryDecoded { .items(items: items) }
}

public enum HistoryFile {
    public typealias Decoded = HistoryDecoded
    public static let fileName = historyFileName()
    public static func decode(_ data: Data) -> HistoryDecoded { historyFileDecode(bytes: data) }
    public static func encode(_ items: [HistoryItem]) throws -> Data { historyFileEncode(items: items) }
    /// Newest first; an empty query lists everything.
    public static func search(_ items: [HistoryItem], query: String) -> [HistoryItem] { historySearch(items: items, query: query) }
}

public enum HistoryExport {
    public static func json(_ items: [HistoryItem], known: KnownAddresses? = nil) throws -> Data {
        historyExportJson(items: items, known: known)
    }
    public static func knownAddresses(in data: Data) -> KnownAddresses { historyExportKnownAddresses(bytes: data) }
    /// With `known`, one `knownAddress` row per address the person knows follows the items (SPEC §10).
    public static func csv(_ items: [HistoryItem], known: KnownAddresses? = nil) -> String {
        historyExportCsvWithKnown(items: items, known: known)
    }
}

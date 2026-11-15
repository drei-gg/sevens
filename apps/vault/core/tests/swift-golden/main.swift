import Foundation

// Golden fixtures from the app's real encoder settings: plain JSONEncoder() (no .sortedKeys) for
// the index and header, BackupContainer.write for the .vault7 framing. Synthetic bytes only.
extension KeyEnvelope: Equatable {
    static func == (a: KeyEnvelope, b: KeyEnvelope) -> Bool { a.salt == b.salt && a.rounds == b.rounds && a.sealed == b.sealed }
}
extension VaultHeader: Equatable {
    static func == (a: VaultHeader, b: VaultHeader) -> Bool {
        a.version == b.version && a.id == b.id && a.password == b.password && a.recovery == b.recovery && a.credentialKind == b.credentialKind
    }
}

let out = URL(fileURLWithPath: "/w/out"); try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
func write(_ name: String, _ data: Data) throws { try data.write(to: out.appendingPathComponent(name)) }
func uuid(_ s: String) -> UUID { UUID(uuidString: s)! }

// Library: every field, optional ones present and absent, fractional and whole dates.
var library = VaultLibrary(id: uuid("6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D"), name: "Travel & receipts")
let work = VaultFolder(id: uuid("11111111-2222-4333-8444-555555555555"), name: "Work")
let sub = VaultFolder(id: uuid("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"), name: "Résumés", parent: work.id)
library.folders = [work, sub]
var a = VaultItem(id: uuid("0A0B0C0D-0E0F-4A1B-8C2D-3E4F5A6B7C8D"), name: "Boarding pass.pdf", type: "com.adobe.pdf", bytes: 900, digest: String(repeating: "a", count: 64), folder: work.id, order: 1)
a.created = Date(timeIntervalSinceReferenceDate: 784111777.123456)
a.favorite = true
var b = VaultItem(id: uuid("FEDCBA98-7654-4321-8FED-CBA987654321"), name: "Passport 🛂.png", type: "public.png", bytes: 3_000_000_000, digest: String(repeating: "0123456789abcdef", count: 4), order: 0)
b.created = Date(timeIntervalSinceReferenceDate: 784000000)
b.deleted = Date(timeIntervalSinceReferenceDate: 784200000.5)
var c = VaultItem(id: uuid("12345678-9ABC-4DEF-8123-456789ABCDEF"), name: "Plan.txt", type: "public.plain-text", bytes: 0, digest: String(repeating: "E3", count: 32), folder: sub.id, order: 2)
c.created = Date(timeIntervalSinceReferenceDate: -12.25)
library.items = [a, b, c]; library.sort = .largest; library.grid = true
library.backupPrepared = Date(timeIntervalSinceReferenceDate: 784300000.987654321)
try library.validate()
try write("library-swift.json", JSONEncoder().encode(library))
let empty = VaultLibrary(id: uuid("00000000-0000-4000-8000-000000000001"), name: "Empty")
try write("library-empty-swift.json", JSONEncoder().encode(empty))

// Header: PIN-routed and legacy (no credentialKind). Salt/sealed bytes are patterns, not keys.
let pw = KeyEnvelope(salt: Data((0..<32).map { UInt8($0) }), rounds: 600_000, sealed: Data((0..<60).map { UInt8(255 - $0) }))
let rc = KeyEnvelope(salt: Data(repeating: 0x5A, count: 32), rounds: 600_000, sealed: Data((0..<60).map { UInt8(($0 * 7) % 256) }))
let header = VaultHeader(id: library.id, password: pw, recovery: rc, credentialKind: .pin)
let headerData = try JSONEncoder().encode(header)
try write("header-swift.json", headerData)
let legacy = VaultHeader(id: library.id, password: pw, recovery: rc)
try write("header-legacy-swift.json", JSONEncoder().encode(legacy))

// Container: the real writer over a directory of synthetic files.
let dir = URL(fileURLWithPath: "/tmp/vault-src"); try? FileManager.default.removeItem(at: dir)
try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
try headerData.write(to: dir.appendingPathComponent("header.json"))
try Data((0..<100).map { UInt8($0 % 13) }).write(to: dir.appendingPathComponent("index.sealed"))
try Data("synthetic ciphertext".utf8).write(to: dir.appendingPathComponent(a.id.uuidString + ".aea"))
try Data().write(to: dir.appendingPathComponent(c.id.uuidString + ".aea"))
let names = ["header.json", "index.sealed", a.id.uuidString + ".aea", c.id.uuidString + ".aea"]
try BackupContainer.write(directory: dir, names: names, to: out.appendingPathComponent("container-swift.vault7"))
let back = URL(fileURLWithPath: "/tmp/vault-back"); try? FileManager.default.removeItem(at: back)
try FileManager.default.createDirectory(at: back, withIntermediateDirectories: true)
let readBack = try BackupContainer.read(out.appendingPathComponent("container-swift.vault7"), into: back)
precondition(readBack == Set(names))

// Behaviour table: credential validation, pattern canonicalization, names, search and name order.
func outcome(_ body: () throws -> Void) -> String {
    do { try body(); return "ok" } catch let e as VaultError { return "\(e)" } catch { return "other" }
}
var table: [String: Any] = [:]
let credentials: [(VaultCredentialKind, String)] = [
    (.passphrase, "short"), (.passphrase, "eleven char"), (.passphrase, "twelve chars"), (.passphrase, String(repeating: "é", count: 11)),
    (.passphrase, String(repeating: "👨‍👩‍👧", count: 12)), (.passphrase, String(repeating: "👨‍👩‍👧", count: 11)), (.passphrase, String(repeating: "a", count: 4096)),
    (.passphrase, String(repeating: "a", count: 4097)), (.passphrase, String(repeating: "é", count: 2048)), (.passphrase, String(repeating: "é", count: 2049)),
    (.pin, "00123456"), (.pin, "1234567"), (.pin, "１２３４５６７８"), (.pin, "1234567a"), (.pin, "1234 5678"), (.pin, String(repeating: "1", count: 32)),
    (.pin, String(repeating: "1", count: 33)), (.pin, ""), (.pin, "+1234567"), (.pin, "١٢٣٤٥٦٧٨"),
    (.pattern, "0,1,2,3,4,9,14,19,24"), (.pattern, "0,4,24"), (.pattern, "0,1,2"), (.pattern, "24,23,22,21,20,15,10,5"), (.pattern, "0,6,12,18,24,19,14,9"),
    (.pattern, "0,12,24"), (.pattern, ""), (.pattern, "0,,1"), (.pattern, " 0,1"), (.pattern, (0..<25).map(String.init).joined(separator: ",")),
    (.pattern, "-0,1,2,3,4,9,14,19"), (.pattern, "+0,1,2,3,4,9,14,19"), (.pattern, "0,1,2,3,4,9,14,19,"), (.pattern, "0,1,2,3,4,9,14,19,24,25"),
    (.pattern, "0,1,2,3,4,9,14,19,99999999999999999999"), (.pattern, "3,7,11,15,16,17,18,19"), (.pattern, "3,15,16,17,18,19,24,23"),
]
table["credentials"] = credentials.map { (kind, value) -> [String: Any] in ["kind": kind.rawValue, "value": value, "result": outcome { try kind.validate(value) }, "material": kind.material(value), "title": kind.title] }
let gestures: [[Int]] = [[0, 4, 24], [0, 24], [0, 7], [0, 10, 2], [12, 0, 24], [24, 0], [3, 15], [0, 0, 5, 30, -1], [20, 4], [2, 22, 10, 14], [0, 14], [0, 2, 1]]
table["gestures"] = gestures.map { samples -> [String: Any] in
    var path: [Int] = []; for dot in samples { PatternPath.append(dot, to: &path) }
    return ["samples": samples, "path": path]
}
let names2 = ["", " ", "\t\n", "..", ".", "...", "../file", "a/b", "a\\b", "bad\0file", String(repeating: "a", count: 200), String(repeating: "a", count: 201),
              String(repeating: "é", count: 200), String(repeating: "👨‍👩‍👧", count: 201), "Travel & receipts.pdf", "\u{00A0}", "\u{2028}", "\u{3000}x", "\u{200B}"]
table["names"] = names2.map { n in ["name": n, "result": outcome { try VaultLibrary.validateName(n) }] }
let digests = [String(repeating: "a", count: 64), String(repeating: "A", count: 64), String(repeating: "g", count: 64), String(repeating: "a", count: 63),
               String(repeating: "ａ", count: 64), String(repeating: "０", count: 64), String(repeating: "a", count: 63) + "e\u{301}", String(repeating: "Ａ", count: 64)]
table["digests"] = digests.map { d -> [String: Any] in
    var v = VaultLibrary(id: UUID(), name: "D")
    v.items = [VaultItem(name: "x", type: "public.data", bytes: 1, digest: d)]
    return ["digest": d, "result": outcome { try v.validate() }]
}
let searches: [(String, String)] = [("Boarding pass.pdf", "BOARDING"), ("Résumé.pdf", "resume"), ("resume.pdf", "RÉSUMÉ"), ("ｆｉｌｅ", "file"), ("file", "ｆｉｌｅ"),
    ("Straße", "STRASSE"), ("abc", ""), ("abc", "abd"), ("İstanbul", "istanbul"), ("ﬁle", "fi"), ("Ångström", "angstrom"), ("Crème brûlée", "creme brulee"), ("ΣΊΣΥΦΟΣ", "σίσυφος")]
table["searches"] = searches.map { s in
    ["name": s.0, "query": s.1, "matches": VaultItem(name: s.0, type: "t", bytes: 0, digest: "").matches(s.1)] as [String: Any]
}
let order = ["file10.pdf", "file2.pdf", "File1.pdf", "file1.pdf", "Éclair.png", "eclair.png", "apple", "Apple", "_notes", "2024 taxes", "10 receipts", "zebra",
             "Zoë", "zoe", "a b", "ab", "a-b", "résumé.pdf", "resume.pdf", "file02.pdf", "file2a", "ｆｉｌｅ3", "Banana", "banana10", "banana9", "(draft)", "#1", "~tmp", "Ω"]
table["names_sorted"] = order.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
let pairs: [(String, String)] = [("file2.pdf", "file02.pdf"), ("file02.pdf", "file2.pdf"), ("apple", "Apple"), ("eclair", "Éclair"), ("a", "a"), ("ｆｉｌｅ3", "file3"),
    ("e\u{301}", "\u{e9}"), ("x 2", "x2"), ("Straße", "Strasse"), ("ﬁle", "file"), ("a1b", "a01b"), ("z", "Ω"), ("Ω", "я"), ("я", "あ"), ("1", "a"), ("$", "1"), ("€", "$"), ("½", "1"), ("€", "1")]
table["compares"] = pairs.map { p -> [String: Any] in ["a": p.0, "b": p.1, "result": p.0.localizedStandardCompare(p.1).rawValue] }
let tableData = try JSONSerialization.data(withJSONObject: table, options: [.prettyPrinted, .sortedKeys])
try write("behaviour-swift.json", tableData)

// Swift reads what Rust wrote (present after the Rust suite has run once).
func check<T: Decodable & Equatable>(_ file: String, _ type: T.Type, _ expected: T) {
    let url = URL(fileURLWithPath: "/w/rust/" + file)
    guard let data = try? Data(contentsOf: url) else { print("skip \(file)"); return }
    do { let value = try JSONDecoder().decode(type, from: data); print(value == expected ? "Swift reads \(file): equal" : "Swift reads \(file): DIFFERENT") }
    catch { print("Swift reads \(file): FAILED \(error)") }
}
check("library-rust.json", VaultLibrary.self, library)
check("library-empty-rust.json", VaultLibrary.self, empty)
check("header-rust.json", VaultHeader.self, header)
check("header-legacy-rust.json", VaultHeader.self, legacy)
// The Rust container re-encodes the committed Swift container (whose header bytes came from an
// earlier run, since key order varies): Swift must read both to the same files.
func entries(_ file: URL, _ into: String) throws -> [String: Data] {
    let dir = URL(fileURLWithPath: into); try? FileManager.default.removeItem(at: dir)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    var result: [String: Data] = [:]
    for name in try BackupContainer.read(file, into: dir) { result[name] = try Data(contentsOf: dir.appendingPathComponent(name)) }
    return result
}
let rc7 = URL(fileURLWithPath: "/w/rust/container-rust.vault7"), committed = URL(fileURLWithPath: "/w/rust/container-swift.vault7")
if FileManager.default.fileExists(atPath: rc7.path) {
    let ours = try entries(committed, "/tmp/a"), theirs = try entries(rc7, "/tmp/b")
    print(ours == theirs && Set(ours.keys) == Set(names) ? "Swift reads container-rust.vault7: equal (\(theirs.count) entries)" : "container DIFFERENT")
}
print("done")

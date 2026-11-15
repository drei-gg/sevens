import Foundation

// The contacts export package, copied verbatim from CleanerPlatform/ContactsService.swift (export(_:to:)).
struct Package: Codable { var version = 1; var unavailable = ["notes", "account credentials", "group membership and linked-contact relationships"]; var records: [ContactRecord]; var archives: [String: Data] }

func sampleItems() -> [CleanupItem] {
    [
        CleanupItem(id: "A1B2/L0/001", name: "IMG_0001.HEIC", kind: .photo, revision: "784891800.25:4032x3024:2", bytes: 2_345_678,
                    date: Date(timeIntervalSinceReferenceDate: 784_891_800.25), width: 4032, height: 3024, favorite: true,
                    tags: ["Screenshots", "HDR", "Live Photos"], fingerprint: "1:public.heic:abc|2:com.apple.quicktime-movie:def"),
        CleanupItem(id: "file:///Docs/report%20final.pdf", name: "Docs/report final.pdf", kind: .file, revision: "0.0:-1"),
        CleanupItem(id: "C3/L0/001", name: "Clip.MOV", kind: .video, revision: "1.5:1920x1080:1", bytes: 0,
                    date: Date(timeIntervalSinceReferenceDate: -12.125), width: 1920, height: 1080, duration: 12.5,
                    fingerprint: "1:public.heic:abc|2:com.apple.quicktime-movie:def"),
        CleanupItem(id: "D4/L0/001", name: "Zoë.jpg", kind: .photo, bytes: 9_007_199_254_740_993,
                    date: Date(timeIntervalSinceReferenceDate: 784_891_800.123456789), fingerprint: "1:public.heic:abc|2:com.apple.quicktime-movie:def"),
    ]
}
func sampleAssessment() -> CompressionAssessment {
    let source = ImageFacts(width: 4000, height: 3000, orientation: 6, colorSpace: "kCGColorSpaceSRGB", bytes: 8_000_000)
    var output = source; output.bytes = 7_998_000; output.orientation = 1
    return CompressionAssessment(source: source, output: output, fullResolutionPSNR: 41.123456789, worstTilePSNR: 29.5)
}
func sampleState() -> ReviewState {
    var state = ReviewState()
    state.decisions = ["A1B2/L0/001": .remove, "file:///Docs/report%20final.pdf": .keep, "C3/L0/001": .later, "D4/L0/001": .undecided]
    state.revisions = ["A1B2/L0/001": "784891800.25:4032x3024:2", "C3/L0/001": "1.5:1920x1080:1"]
    var first = HistoryEntry("Moved 2 items to Recently Deleted.")
    first.id = UUID(uuidString: "6F9619FF-8B86-D011-B42D-00C04FC964FF")!
    first.date = Date(timeIntervalSinceReferenceDate: 784_891_900.5)
    var second = HistoryEntry("Saved a compressed copy of “Zoë.jpg”.")
    second.id = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
    second.date = Date(timeIntervalSinceReferenceDate: 784_000_000)
    state.history = [first, second]
    state.processedSources = ["A1B2/L0/001", "D4/L0/001"]
    return state
}
func samplePackage() -> Package {
    let a = ContactRecord(id: "A:ABPerson", name: "Jean Example Test", container: "local",
                          fields: [ContactField("email", "jean@example.com", label: "Work"), ContactField("givenName", "Jean"), ContactField("phone", "+44 20 7946 0018 ext 12", label: "_$!<Home>!$_")],
                          phoneKeys: ["+442079460018;ext=12", "+442079460018"], emailKeys: ["jean@example.com"])
    let b = ContactRecord(id: "B:ABPerson", name: "Zoë", container: "exchange", fields: [ContactField("givenName", "Zoë")], inaccessibleFields: [])
    return Package(records: [a, b], archives: ["A:ABPerson": Data([0, 1, 2, 250, 255]), "B:ABPerson": Data("bplist00".utf8)])
}

let args = CommandLine.arguments
if args.count == 3 && args[1] == "read" {
    // Swift reading files the Rust core wrote.
    let dir = URL(fileURLWithPath: args[2])
    let state = try JSONDecoder().decode(ReviewState.self, from: Data(contentsOf: dir.appendingPathComponent("rust-review-state.json")))
    let package = try JSONDecoder().decode(Package.self, from: Data(contentsOf: dir.appendingPathComponent("rust-contacts-package.json")))
    let items = try JSONDecoder().decode([CleanupItem].self, from: Data(contentsOf: dir.appendingPathComponent("rust-items.json")))
    let groups = try JSONDecoder().decode([MatchGroup].self, from: Data(contentsOf: dir.appendingPathComponent("rust-groups.json")))
    let assessment = try JSONDecoder().decode(CompressionAssessment.self, from: Data(contentsOf: dir.appendingPathComponent("rust-assessment.json")))
    let reference = samplePackage()
    print("review-state equal:", state == sampleState())
    print("package equal:", package.records == reference.records && package.archives == reference.archives && package.version == 1 && package.unavailable == reference.unavailable)
    print("items equal:", items == sampleItems())
    print("groups equal:", groups == Matching.exact(sampleItems()))
    print("assessment equal:", assessment == sampleAssessment())
    // Rust's store must read as a store: version gate included.
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try Data(contentsOf: dir.appendingPathComponent("rust-review-state.json")).write(to: url)
    print("ReviewStore.read equal:", try await ReviewStore(url: url).read() == sampleState())
    exit(0)
}

let out = URL(fileURLWithPath: args.count > 1 ? args[1] : "out")
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
// The app's exact encoders: ReviewStore.write and ContactsService.export both use a plain JSONEncoder().
try JSONEncoder().encode(sampleState()).write(to: out.appendingPathComponent("swift-review-state.json"))
try JSONEncoder().encode(samplePackage()).write(to: out.appendingPathComponent("swift-contacts-package.json"))
try JSONEncoder().encode(sampleItems()).write(to: out.appendingPathComponent("swift-items.json"))
try JSONEncoder().encode(Matching.exact(sampleItems())).write(to: out.appendingPathComponent("swift-groups.json"))
try JSONEncoder().encode(sampleAssessment()).write(to: out.appendingPathComponent("swift-assessment.json"))

// Revision strings exactly as FilesService and PhotosService interpolate them.
var lines: [String] = []
let stamps: [Double?] = [nil, 0, 1, 1.5, 1_700_000_000, 1_700_000_000.5, 1_731_657_600.123456, 1_763_197_200.987654321, 0.0001, 1e-7, 1e16, 123456789012345.67, -1.25, 9007199254740992, 9007199254740991, 0.00009999, 5e-324, 1.7976931348623157e308, 1.5e-5, 2.5e20, -0.0, 9007199254740994, 18014398509481984, 18014398509481988, 0.0001000000001]
let sizes: [Int?] = [nil, 0, 42, 9_007_199_254_740_993]
for stamp in stamps {
    for size in sizes { lines.append("file\t\(stamp.map { String($0) } ?? "nil")\t\(size.map(String.init) ?? "nil")\t\(stamp ?? 0):\(size ?? -1)") }
    lines.append("photo\t\(stamp.map { String($0) } ?? "nil")\t4032x3024x2\t\(stamp ?? 0):\(4032)x\(3024):\(2)")
}
try (lines.joined(separator: "\n") + "\n").write(to: out.appendingPathComponent("swift-revisions.tsv"), atomically: true, encoding: .utf8)
print("wrote", out.path)

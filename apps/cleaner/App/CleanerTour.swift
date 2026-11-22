import SwiftUI
import SevenKit

struct CleanerLaunchRoot: View {
    @Environment(CleanerModel.self) private var model
    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && (ProcessInfo.processInfo.arguments.contains("-cleaner.batch-audit") || ProcessInfo.processInfo.arguments.contains("-cleaner.batch-cleanup") || ProcessInfo.processInfo.arguments.contains("-cleaner.video-audit") || ProcessInfo.processInfo.arguments.contains("-cleaner.video-cleanup")) {
            Text(model.batchAuditStatus).padding()
        }
        else if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && ProcessInfo.processInfo.arguments.contains("-cleaner.resume-cleanup") {
            Text(model.error ?? (model.resumeFixtureFinished ? "Fixture cleanup complete" : "Cleaning test photos…"))
        }
        else if CleanerFixtures.active { CleanerTourHost().preferredColorScheme(CleanerFixtures.argument("-cleaner.theme") == "dark" ? .dark : .light) }
        else { CleanerHome() }
        #else
        CleanerHome()
        #endif
    }
}
#if DEBUG
import UIKit
import AVFoundation
@preconcurrency import Photos
import CleanerCore
import CleanerPlatform

/// Deterministic, isolated tour data. No Photos/Contacts store permissions or writes.
@MainActor enum CleanerFixtures {
    static var active: Bool { ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && argument("-cleaner.tour") != nil }
    static func argument(_ key: String) -> String? { let args = ProcessInfo.processInfo.arguments; guard let i = args.firstIndex(of: key), i + 1 < args.count else { return nil }; return args[i+1] }
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerTour")
    static var videoURL: URL { directory.appendingPathComponent("sample.mov") }
    static func image(id: String) -> UIImage? { UIImage(contentsOfFile: directory.appendingPathComponent(id.contains("receipt") ? "receipt.png" : "landscape.png").path) }
    static func seed(_ model: CleanerModel) async {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let size = CGSize(width: 900, height: 600)
            let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true; format.preferredRange = .standard
            let landscape = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
                let c = ctx.cgContext
                for y in 0..<600 {
                    UIColor(red: 0.16 + Double(y)*0.0003, green: 0.42 + Double(y)*0.0003, blue: 0.62 + Double(y)*0.0002, alpha: 1).setFill()
                    c.fill(CGRect(x: 0, y: y, width: 900, height: 1))
                }
                UIColor(red: 0.98, green: 0.87, blue: 0.62, alpha: 1).setFill(); c.fillEllipse(in: CGRect(x: 640, y: 80, width: 100, height: 100))
                for (index, offset) in [220,340,440].enumerated() {
                    UIColor(red: 0.12 + Double(index)*0.02, green: 0.27 + Double(index)*0.05, blue: 0.29 + Double(index)*0.02, alpha: 1).setFill()
                    c.beginPath(); c.move(to: CGPoint(x: 0,y: 600)); c.addLine(to: CGPoint(x: 0,y: offset+100)); c.addLine(to: CGPoint(x: 180,y: offset-100)); c.addLine(to: CGPoint(x: 430,y: offset+30)); c.addLine(to: CGPoint(x: 670,y: offset-70)); c.addLine(to: CGPoint(x: 900,y: offset+70)); c.addLine(to: CGPoint(x: 900,y: 600)); c.closePath(); c.fillPath()
                }
            }
            try landscape.pngData()!.write(to: directory.appendingPathComponent("landscape.png"))
            let receipt = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
                UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero,size: size))
                ("THE WEEKEND\nCoffee, coast, repeat.\n\nTwo coffees                 280\nBreakfast                    420\n\nTOTAL                         700\n\nNovember 13, 2026" as NSString).draw(in: CGRect(x: 70,y: 60,width: 760,height: 480), withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 28,weight: .regular), .foregroundColor: UIColor.black])
            }
            try receipt.pngData()!.write(to: directory.appendingPathComponent("receipt.png"))
            try await makeVideo(landscape)
            let date = Date(timeIntervalSince1970: 1794528000)
            model.media = [
                CleanupItem(id: "tour-a",name: "Coast at golden hour.heic",kind: .photo,revision: "1",bytes: 8_400_000,date: date,width: 4032,height: 3024,favorite: true,fingerprint: "same"),
                CleanupItem(id: "tour-b",name: "Coast at golden hour copy.heic",kind: .photo,revision: "1",bytes: 8_400_000,date: date,width: 4032,height: 3024,fingerprint: "same"),
                CleanupItem(id: "tour-c",name: "Weekend walk.heic",kind: .photo,revision: "1",bytes: 6_200_000,date: date,width: 4032,height: 3024,tags: ["Live Photos"]),
                CleanupItem(id: "tour-receipt",name: "Coffee receipt.png",kind: .photo,revision: "1",bytes: 2_900_000,date: date,width: 1179,height: 2556,tags: ["Screenshots"]),
                CleanupItem(id: "tour-d",name: "Another view.heic",kind: .photo,revision: "1",date: date,width: 4032,height: 3024),
                CleanupItem(id: "tour-video",name: "The coast.mov",kind: .video,revision: "1",bytes: 482_000_000,date: date,width: 3840,height: 2160,duration: 95),
                CleanupItem(id: "tour-recording",name: "Screen recording.mov",kind: .video,revision: "1",bytes: 176_000_000,date: date,width: 1179,height: 2556,duration: 42,tags: ["Screen recordings"])]
            model.groups = Matching.exact(model.media) + [MatchGroup(id: "similar",kind: .similar,members: ["tour-a","tour-c"],reason: "Nearby capture times and similar image features. Review both moments.")]
            model.state.decisions = ["tour-a": .keep,"tour-b": .remove,"tour-c": .later]
            model.state.history = [HistoryEntry("Reviewed two matching photos. The favorite is kept."),HistoryEntry("Measured the accessible photo and video resources.")]
            let files = [CleanupItem(id: "file-a",name: "Weekend footage.mov",kind: .file,bytes: 1_450_000_000),CleanupItem(id: "file-b",name: "Project archive.zip",kind: .file,bytes: 680_000_000,fingerprint: "zip"),CleanupItem(id: "file-c",name: "Project archive copy.zip",kind: .file,bytes: 680_000_000,fingerprint: "zip"),CleanupItem(id: "file-d",name: "Reference.pdf",kind: .file,bytes: nil)]
            model.folderScan = FolderScan(items: files,groups: Matching.exact(files),skipped: 1)
            let people = [ContactRecord(id: "contact-a",name: "Alex Rivera",container: "personal",fields: [ContactField("givenName","Alex"),ContactField("familyName","Rivera"),ContactField("phone","+63 917 555 0123",label: "Mobile"),ContactField("email","alex@example.com",label: "Personal"),ContactField("birthday","14 March"),ContactField("organization","Weekend Studio")],phoneKeys: ["+639175550123"]),ContactRecord(id: "contact-b",name: "Alex Rivera",container: "personal",fields: [ContactField("givenName","Alex"),ContactField("familyName","Rivera"),ContactField("phone","0917 555 0123",label: "Mobile"),ContactField("email","alex@studio.example",label: "Work")],phoneKeys: ["+639175550123"]),ContactRecord(id: "contact-c",name: "Weekend Studio",container: "personal",fields: [ContactField("organization","Weekend Studio")])]
            model.contactSnapshot = ContactSnapshot(records: people,archives: [:],limited: true); model.contactMatches = ContactMatching.candidates(people)
            if argument("-cleaner.tour") == "compression" { model.candidate = try ImageCompression.makeCandidate(sourceURL: directory.appendingPathComponent("landscape.png"),directory: directory); model.candidateSource = "tour-a" }
            if argument("-cleaner.tour")?.hasPrefix("empty-") == true { model.media = []; model.groups = []; model.folderScan = nil; model.contactSnapshot = nil; model.contactMatches = []; model.state = ReviewState() }
        } catch { model.error = error.cleanerMessage }
    }
    /// Native PhotoKit fixture for checkpoint/relaunch verification. Only test-created identifiers
    /// from this isolated manifest can be removed; ordinary tours never request Photos access.
    static func resumeFixture(_ model: CleanerModel, cleanup: Bool) async throws {
        let manifest = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerResumeFixture.json")
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard status == .authorized || status == .limited else { throw CleanerError.blocked("Fixture Photos permission was not granted.") }
        if let data = try? Data(contentsOf: manifest), let ids = try? JSONDecoder().decode([String].self, from: data) {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            try await PHPhotoLibrary.shared().performChanges { @Sendable in PHAssetChangeRequest.deleteAssets(assets) }
            try FileManager.default.removeItem(at: manifest)
        }
        if cleanup { return }
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 480))
        let data = renderer.image { context in
            UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 640, height: 480))
        }.jpegData(compressionQuality: 1)!
        let identifiers = ResumeFixtureIdentifiers()
        try await PHPhotoLibrary.shared().performChanges { @Sendable in
            for _ in 0..<2 {
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .photo, data: data, options: nil)
                if let id = request.placeholderForCreatedAsset?.localIdentifier { identifiers.append(id) }
            }
        }
        let ids = identifiers.read()
        try JSONEncoder().encode(ids).write(to: manifest, options: .atomic)
        guard ids.count == 2 else { throw CleanerError.blocked("Fixture did not create both original resources.") }
        let inventory = try await model.photos.inventory()
        let owned = inventory.items.filter { ids.contains($0.id) }
        let cache = model.measurements
        do {
            _ = try await model.photos.analyze(owned, allowCloud: false, checkpoint: { item in
                try cache.checkpoint(item)
                throw CancellationError()
            }) { _, _ in }
        } catch is CancellationError { }
        model.savedMeasurements = try cache.count()
    }

    private static func makeVideo(_ image: UIImage) async throws {
        if FileManager.default.fileExists(atPath: videoURL.path) { return }
        let writer = try AVAssetWriter(outputURL: videoURL,fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video,outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,AVVideoWidthKey: 900,AVVideoHeightKey: 600])
        let adapter = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String: 900,kCVPixelBufferHeightKey as String: 600,kCVPixelBufferCGImageCompatibilityKey as String: true,kCVPixelBufferCGBitmapContextCompatibilityKey as String: true])
        writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)
        for i in 0..<12 {
            while !input.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(10)) }
            var pixel: CVPixelBuffer?; CVPixelBufferPoolCreatePixelBuffer(nil,adapter.pixelBufferPool!,&pixel)
            guard let pixel else { throw CleanerError.blocked("Tour video buffer unavailable.") }
            CVPixelBufferLockBaseAddress(pixel,[])
            let context = CGContext(data: CVPixelBufferGetBaseAddress(pixel),width: 900,height: 600,bitsPerComponent: 8,bytesPerRow: CVPixelBufferGetBytesPerRow(pixel),space: CGColorSpaceCreateDeviceRGB(),bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
            context.draw(image.cgImage!,in: CGRect(x: 0,y: 0,width: 900,height: 600)); CVPixelBufferUnlockBaseAddress(pixel,[])
            adapter.append(pixel,withPresentationTime: CMTime(value: Int64(i),timescale: 6))
        }
        input.markAsFinished(); await writer.finishWriting()
    }
}
private final class ResumeFixtureIdentifiers: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []
    func append(_ id: String) { lock.withLock { values.append(id) } }
    func read() -> [String] { lock.withLock { values } }
}
struct CleanerTourHost: View {
    @Environment(CleanerModel.self) private var model
    private var route: String { (CleanerFixtures.argument("-cleaner.tour") ?? "overview").replacingOccurrences(of: "empty-",with: "") }
    var body: some View {
        Group {
            if model.tourReady {
                NavigationStack { destination }
                    .accessibilityIdentifier("cleaner.tour.ready")
            } else { ProgressView("Preparing sample library…") }
        }
    }
    @ViewBuilder private var destination: some View {
        switch route {
        case "photos": MediaLibrary(kind: .photo)
        case "videos": MediaLibrary(kind: .video)
        case "files": FilesScreen()
        case "contacts", "country", "contact-groups": ContactsScreen()
        case "groups": GroupsScreen()
        case "pending", "delete-dialog": PendingScreen()
        case "photo": if let item = model.media.first { MediaDetail(item: item) }
        case "video": if let item = model.media.first(where: { $0.kind == .video }) { MediaDetail(item: item) }
        case "compression": CompressionScreen()
        case "contact-comparison": ContactGroupsScreen()
        case "contact-fields": if let record = model.contactSnapshot?.records.first { ContactFields(record: record) }
        case "settings": CleanerSettings()
        case "activity": ActivityScreen()
        case "onboarding": BeforeYouBuyView(content: CleanerGate.before) { _ in }
        case "unlock": UnlockView()
        case "expired": TrialEndedView(content: CleanerGate.expired)
        case "license-phone": LicenseScreen(name: "PhoneNumberKit-LICENSE")
        case "license-metadata": LicenseScreen(name: "Libphonenumber-LICENSE")
        default: Dashboard()
        }
    }
}
#endif

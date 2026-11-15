import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import ImageIO
import VaultCore

struct IncomingMedia: Transferable, Sendable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .data) { received in
            let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("VaultIncoming")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            let folder = root.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            let target = folder.appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: target)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: target.path)
            var values = URLResourceValues(); values.isExcludedFromBackup = true; var protected = folder; try protected.setResourceValues(values)
            return IncomingMedia(url: target)
        }
    }
}
struct VaultThumbnail: View {
    @Environment(VaultModel.self) private var model
    let item: VaultItem
    @State private var thumbnail: UIImage?
    var body: some View {
        Group {
            if let thumbnail { Image(uiImage: thumbnail).resizable().scaledToFit() }
            else { Image(systemName: icon).font(.title).foregroundStyle(.secondary) }
        }.accessibilityHidden(true)
            .task(id: item.id) {
                guard UTType(item.type)?.conforms(to: .image) == true, !model.busy else { return }
                let epoch = model.generation
                let destination = await model.repository.temporary.appendingPathComponent("thumb-" + UUID().uuidString)
                defer { try? FileManager.default.removeItem(at: destination) }
                do {
                    let url = try await model.repository.export(item.id, into: destination)
                    let data = await Task.detached(priority: .utility) { () -> Data? in
                        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil), let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 240, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil }
                        let result = NSMutableData()
                        guard let target = CGImageDestinationCreateWithData(result, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
                        CGImageDestinationAddImage(target, image, nil); guard CGImageDestinationFinalize(target) else { return nil }; return result as Data
                    }.value
                    guard !Task.isCancelled, epoch == model.generation, model.library != nil else { return }
                    if let data { thumbnail = UIImage(data: data) }
                } catch { /* A thumbnail is optional; opening the file reports the underlying error. */ }
            }
    }
    private var icon: String {
        if UTType(item.type)?.conforms(to: .movie) == true { return "video" }
        if UTType(item.type)?.conforms(to: .image) == true { return "photo" }
        if UTType(item.type)?.conforms(to: .plainText) == true { return "note.text" }
        return "doc"
    }
}
struct VaultCamera: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let finish: (URL?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController(); controller.sourceType = .camera; controller.delegate = context.coordinator; return controller
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: VaultCamera; init(_ parent: VaultCamera) { self.parent = parent }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss(); parent.finish(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            defer { parent.dismiss() }
            guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 1) else { parent.finish(nil); return }
            let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("VaultCapture-" + UUID().uuidString + ".jpg")
            do { try data.write(to: url, options: .completeFileProtection); parent.finish(url) } catch { parent.finish(nil) }
        }
    }
}

/// Cover every window, including presented system preview/share controllers, before snapshots.
@MainActor final class PrivacyShield: NSObject {
    private static let shared = PrivacyShield()
    private var covers: [UIView] = []
    private var installed = false
    static func install() {
        guard !shared.installed else { return }; shared.installed = true
        NotificationCenter.default.addObserver(shared, selector: #selector(cover), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(shared, selector: #selector(uncover), name: UIApplication.didBecomeActiveNotification, object: nil)
        // Clear only this app's interrupted picker/camera staging on the next launch.
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        try? FileManager.default.removeItem(at: caches.appendingPathComponent("VaultIncoming"))
        for url in (try? FileManager.default.contentsOfDirectory(at: caches, includingPropertiesForKeys: nil)) ?? [] where url.lastPathComponent.hasPrefix("VaultCapture-") { try? FileManager.default.removeItem(at: url) }
    }
    @objc private func cover() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                let view = UIView(frame: window.bounds); view.backgroundColor = .systemBackground; view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                let label = UILabel(); label.text = "Vault locked"; label.font = .preferredFont(forTextStyle: .title2); label.textAlignment = .center; label.frame = view.bounds; label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.addSubview(label); window.addSubview(view); covers.append(view)
            }
        }
    }
    @objc private func uncover() { covers.forEach { $0.removeFromSuperview() }; covers = [] }
}

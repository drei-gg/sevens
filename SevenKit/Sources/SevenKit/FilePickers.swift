#if canImport(UIKit)
import SwiftUI
import UniformTypeIdentifiers

/// Shared Files-provider export, extracted from Scanner's backup screen.
public struct SevenFileExportPicker: UIViewControllerRepresentable {
    public let urls: [URL]
    public init(urls: [URL]) { self.urls = urls }
    public func makeUIViewController(context: Context) -> UIDocumentPickerViewController { UIDocumentPickerViewController(forExporting: urls, asCopy: true) }
    public func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}
public struct SevenBackupFolderPicker: UIViewControllerRepresentable {
    private let selected: (URL) -> Void
    public init(selected: @escaping (URL) -> Void) { self.selected = selected }
    public func makeCoordinator() -> Coordinator { Coordinator(selected: selected) }
    public func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        controller.delegate = context.coordinator; controller.allowsMultipleSelection = false; return controller
    }
    public func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    public final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selected: (URL) -> Void
        init(selected: @escaping (URL) -> Void) { self.selected = selected }
        public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { if let url = urls.first { selected(url) } }
    }
}
#endif

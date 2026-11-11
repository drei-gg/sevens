import SwiftUI
import VisionKit
import AVFoundation

struct CodeScanner: View {
    @Environment(\.dismiss) private var dismiss
    let onCode: (String) -> Void
    @State private var authorized = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var failure: String?
    var body: some View {
        NavigationStack {
            Group {
                if let failure { ContentUnavailableView("Could not start camera",systemImage:"camera",description:Text(failure)) }
                else if authorized && DataScannerViewController.isSupported && DataScannerViewController.isAvailable { ScannerRepresentable(onCode:onCode,onFailure:{failure = $0}).ignoresSafeArea(edges:.bottom) }
                else { ContentUnavailableView("Camera unavailable",systemImage:"camera",description:Text("Allow camera access in Settings, or try on a supported iPhone. You can always stop a mission without scanning.")) }
            }.task { if AVCaptureDevice.authorizationStatus(for:.video) == .notDetermined { authorized = await AVCaptureDevice.requestAccess(for:.video) } }.navigationTitle("Scan your code").toolbar { Button("Cancel") { dismiss() } }
        }
    }
}
struct ScannerRepresentable: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onFailure: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onCode:onCode) }
    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes:[.barcode()],qualityLevel:.balanced,recognizesMultipleItems:false,isHighFrameRateTrackingEnabled:false,isPinchToZoomEnabled:true,isGuidanceEnabled:true,isHighlightingEnabled:true)
        controller.delegate = context.coordinator
        do { try controller.startScanning() } catch { let message = error.localizedDescription; Task { @MainActor in onFailure(message) } }
        return controller
    }
    func updateUIViewController(_ uiViewController: DataScannerViewController,context: Context) {}
    static func dismantleUIViewController(_ uiViewController: DataScannerViewController,coordinator: Coordinator) { uiViewController.stopScanning() }
    @MainActor final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        var consumed = false
        var failure: String?
        init(onCode:@escaping (String)->Void) { self.onCode = onCode }
        func dataScanner(_ dataScanner:DataScannerViewController,didAdd addedItems:[RecognizedItem],allItems:[RecognizedItem]) { accept(addedItems) }
        func dataScanner(_ dataScanner:DataScannerViewController,didTapOn item:RecognizedItem) { accept([item]) }
        func accept(_ items:[RecognizedItem]) { guard !consumed else { return }; for item in items { if case let .barcode(code) = item, let value = code.payloadStringValue, value.utf8.count <= 4096 { consumed = true; onCode(value); return } } }
    }
}

// ScannerView: the live camera scanner (SPEC §2) for WP 3.1's scan screen.
//
// Public API
//   ScannerView(isActive: Bool,
//               onCode: @MainActor (ScannedCode) -> Void,
//               onMultipleChange: @MainActor (Bool) -> Void = { _ in },
//               onStartFailure: @MainActor (Error) -> Void = { _ in })
//   ScannerView.availability -> ScannerAvailability   (check before showing the view)
//
// Contract with the caller
//   * isActive == true runs the camera; false stops it. The first time it starts, iOS asks for
//     camera permission.
//   * onCode fires once per delivered code and the scanner then stops itself and ignores further
//     frames until the caller flips isActive false and back to true. The caller sets isActive
//     false while the result sheet is up and true when it is dismissed. After the restart the
//     same payload is ignored until it leaves the frame or 3 s pass.
//   * One code in view for 0.4 s is delivered. With several in view nothing is auto-delivered;
//     onMultipleChange(true) tells the screen to show "Tap a code", and tapping a highlighted
//     code delivers that one. onMultipleChange(false) when back to at most one.
//   * Show ScannerView only when `availability == .available`; otherwise show the photo/paste
//     fallback (and, for .denied, a line with a button to Settings).
//   * Torch: use TorchController alongside; it is independent of this view.
//
// The timing and dedup rules live in ScanDebouncer (unit-tested); this file only feeds it.
// Needs a camera: DataScannerViewController is unsupported in the Simulator, so this runs only
// on the phone.

import AVFoundation
import QRCore
import SwiftUI
import VisionKit

/// Whether the live scanner can run on this device right now.
public enum ScannerAvailability: Sendable, Equatable {
    case available
    /// No scanner hardware or OS support (Simulator, old devices).
    case unsupported
    /// The person said no to the camera; they can change it in Settings.
    case denied
    /// Camera use is restricted (parental controls, MDM).
    case restricted
}

public struct ScannerView: UIViewControllerRepresentable {
    public var isActive: Bool
    public var onCode: @MainActor (ScannedCode) -> Void
    public var onMultipleChange: @MainActor (Bool) -> Void
    public var onStartFailure: @MainActor (Error) -> Void

    public init(isActive: Bool,
                onCode: @escaping @MainActor (ScannedCode) -> Void,
                onMultipleChange: @escaping @MainActor (Bool) -> Void = { _ in },
                onStartFailure: @escaping @MainActor (Error) -> Void = { _ in }) {
        self.isActive = isActive
        self.onCode = onCode
        self.onMultipleChange = onMultipleChange
        self.onStartFailure = onStartFailure
    }

    @MainActor
    public static var availability: ScannerAvailability {
        guard DataScannerViewController.isSupported else { return .unsupported }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied: return .denied
        case .restricted: return .restricted
        default: return DataScannerViewController.isAvailable ? .available : .unsupported
        }
    }

    public func makeCoordinator() -> ScannerCoordinator { ScannerCoordinator() }

    public func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode()],
            qualityLevel: .balanced,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        controller.view.accessibilityLabel = String(
            localized: "scan.camera.a11y", defaultValue: "Camera viewfinder for scanning codes", bundle: .module)
        context.coordinator.scanner = controller
        return controller
    }

    public func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        let c = context.coordinator
        c.onCode = onCode
        c.onMultipleChange = onMultipleChange
        c.onStartFailure = onStartFailure
        c.setActive(isActive)
    }

    public static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: ScannerCoordinator) {
        coordinator.setActive(false)
    }
}

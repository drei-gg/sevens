import AVFoundation
import Observation

/// The torch behind `AVCaptureDevice`, abstracted so it can be tested without hardware.
@MainActor
protocol TorchDevice: AnyObject {
    var hasTorch: Bool { get }
    var isTorchOn: Bool { get }
    /// Returns false when the device refused (locked, too hot, no torch).
    func setTorch(_ on: Bool) -> Bool
}

@MainActor
final class CaptureTorchDevice: TorchDevice {
    private let device: AVCaptureDevice?
    init() { device = AVCaptureDevice.default(for: .video) }

    var hasTorch: Bool { device?.hasTorch ?? false }
    var isTorchOn: Bool { device?.torchMode == .on }

    func setTorch(_ on: Bool) -> Bool {
        guard let device, device.hasTorch else { return false }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.torchMode = on ? .on : .off
            return true
        } catch {
            return false
        }
    }
}

/// The scan screen's torch button. Hide the button when `isAvailable` is false (iPad, Simulator).
///
/// Contingency C6: if turning the torch on breaks or freezes `DataScannerViewController` on a real
/// phone, the plan replaces the scanner behind `ScannerView`; nothing here changes. Not needed
/// until the on-device check (G9) says so.
@MainActor @Observable
public final class TorchController {
    public private(set) var isAvailable: Bool
    public private(set) var isOn: Bool

    @ObservationIgnored private let device: TorchDevice

    public convenience init() { self.init(device: CaptureTorchDevice()) }

    init(device: TorchDevice) {
        self.device = device
        isAvailable = device.hasTorch
        isOn = device.isTorchOn
    }

    public func toggle() { set(!isOn) }

    /// Turn the torch on or off. Does nothing when there is no torch; `isOn` reflects the result.
    public func set(_ on: Bool) {
        guard isAvailable else { isOn = false; return }
        if device.setTorch(on) { isOn = on } else { isOn = device.isTorchOn }
    }

    /// Call when the scanner disappears or the result sheet comes up, so the light isn't left on.
    public func turnOff() { if isOn { set(false) } }

    /// Re-read the device (after the app returns from the background).
    public func refresh() {
        isAvailable = device.hasTorch
        isOn = device.isTorchOn
    }
}

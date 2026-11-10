import Foundation
import QRCore
import VisionKit

/// Delegate for `ScannerView`: turns VisionKit callbacks into `ScanDebouncer` input and delivers
/// what it releases.
@MainActor
public final class ScannerCoordinator: NSObject, DataScannerViewControllerDelegate {
    weak var scanner: DataScannerViewController?
    var onCode: @MainActor (ScannedCode) -> Void = { _ in }
    var onMultipleChange: @MainActor (Bool) -> Void = { _ in }
    var onStartFailure: @MainActor (Error) -> Void = { _ in }

    private var debouncer = ScanDebouncer()
    private var wantsActive = false
    private var wasMultiple = false
    private var wakeTask: Task<Void, Never>?

    private var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    func setActive(_ active: Bool) {
        guard active != wantsActive else { return }
        wantsActive = active
        if active {
            debouncer.resume(now: now)
            do { try scanner?.startScanning() } catch { onStartFailure(error) }
        } else {
            debouncer.pause()
            stop()
        }
    }

    private func stop() {
        wakeTask?.cancel()
        wakeTask = nil
        scanner?.stopScanning()
        setMultiple(false)
    }

    private func setMultiple(_ value: Bool) {
        guard value != wasMultiple else { return }
        wasMultiple = value
        onMultipleChange(value)
    }

    static func code(_ item: RecognizedItem) -> ScannedCode? {
        guard case .barcode(let barcode) = item else { return nil }
        let observation = barcode.observation
        return ScanDecoding.code(payloadString: barcode.payloadStringValue,
                                 payloadData: observation.payloadData,
                                 symbology: observation.symbology)
    }

    private func update(_ all: [RecognizedItem]) {
        guard wantsActive else { return }
        var codes: [ScannedCode] = []
        for item in all {
            guard let code = Self.code(item) else { continue }
            codes.append(code)
        }
        let released = debouncer.observe(codes, now: now)
        setMultiple(debouncer.hasMultiple)
        finish(released)
    }

    private func finish(_ released: ScannedCode?) {
        wakeTask?.cancel()
        wakeTask = nil
        if let released {
            stop()
            onCode(released)
            return
        }
        guard let wake = debouncer.nextWake else { return }
        let delay = max(0, wake - now)
        wakeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.finish(self.debouncer.tick(now: self.now))
        }
    }

    // MARK: DataScannerViewControllerDelegate

    public func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        update(allItems)
    }

    public func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        update(allItems)
    }

    public func dataScanner(_ dataScanner: DataScannerViewController, didRemove removedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        update(allItems)
    }

    public func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
        guard wantsActive, let code = Self.code(item) else { return }
        finish(debouncer.tap(code, now: now))
    }
}

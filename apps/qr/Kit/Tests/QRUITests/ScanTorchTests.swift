import Testing
@testable import QRUI

@MainActor
final class ScanFakeTorch: TorchDevice {
    var hasTorch = true
    var isTorchOn = false
    var refuses = false
    func setTorch(_ on: Bool) -> Bool {
        if refuses || !hasTorch { return false }
        isTorchOn = on
        return true
    }
}

@MainActor
@Suite struct ScanTorchTests {
    @Test func scanTorchToggles() {
        let device = ScanFakeTorch()
        let torch = TorchController(device: device)
        #expect(torch.isAvailable && !torch.isOn)
        torch.toggle()
        #expect(torch.isOn && device.isTorchOn)
        torch.toggle()
        #expect(!torch.isOn && !device.isTorchOn)
    }

    @Test func scanTorchWithoutHardwareStaysOff() {
        let device = ScanFakeTorch()
        device.hasTorch = false
        let torch = TorchController(device: device)
        #expect(!torch.isAvailable)
        torch.toggle()
        #expect(!torch.isOn)
    }

    @Test func scanTorchRefusalLeavesItOff() {
        let device = ScanFakeTorch()
        device.refuses = true
        let torch = TorchController(device: device)
        torch.toggle()
        #expect(!torch.isOn)
    }

    @Test func scanTorchTurnOffAndRefresh() {
        let device = ScanFakeTorch()
        let torch = TorchController(device: device)
        torch.set(true)
        torch.turnOff()
        #expect(!torch.isOn && !device.isTorchOn)
        device.isTorchOn = true
        torch.refresh()
        #expect(torch.isOn)
    }

    @Test func scanTorchDefaultInitDoesNotCrashWithoutACamera() {
        let torch = TorchController()
        if !torch.isAvailable { #expect(!torch.isOn) }
    }
}

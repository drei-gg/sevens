import QRCore
import Testing
@testable import QRUI

@Suite struct ScanDebouncerTests {
    let a = ScannedCode(text: "A")
    let b = ScannedCode(text: "B")

    @Test func scanDeliversAfterDwell() {
        var d = ScanDebouncer()
        #expect(d.observe([a], now: 10) == nil)
        #expect(d.nextWake == 10.4)
        #expect(d.tick(now: 10.39) == nil)
        #expect(d.tick(now: 10.4) == a)
        #expect(d.isPaused)
    }

    @Test func scanRepeatedFramesDoNotRestartTheDwell() {
        var d = ScanDebouncer()
        #expect(d.observe([a], now: 0) == nil)
        #expect(d.observe([a], now: 0.2) == nil)
        #expect(d.observe([a], now: 0.41) == a)
    }

    @Test func scanLosingTheCodeResetsTheDwell() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        #expect(d.observe([], now: 0.3) == nil)
        #expect(d.nextWake == nil)
        #expect(d.observe([a], now: 0.5) == nil)
        #expect(d.tick(now: 0.89) == nil)
        #expect(d.tick(now: 0.9) == a)
    }

    @Test func scanSwitchingCodeRestartsTheDwell() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        #expect(d.observe([b], now: 0.3) == nil)
        #expect(d.tick(now: 0.5) == nil)
        #expect(d.tick(now: 0.7) == b)
    }

    @Test func scanSeveralCodesAreNeverAutoDelivered() {
        var d = ScanDebouncer()
        #expect(d.observe([a, b], now: 0) == nil)
        #expect(d.hasMultiple)
        #expect(d.nextWake == nil)
        #expect(d.tick(now: 60) == nil)
    }

    @Test func scanDuplicateFramesOfOneCodeCountAsOne() {
        var d = ScanDebouncer()
        _ = d.observe([a, a], now: 0)
        #expect(!d.hasMultiple)
        #expect(d.tick(now: 0.4) == a)
    }

    @Test func scanDropBackToOneCodeStartsTheDwellThen() {
        var d = ScanDebouncer()
        _ = d.observe([a, b], now: 0)
        #expect(d.observe([a], now: 5) == nil)
        #expect(d.tick(now: 5.4) == a)
    }

    @Test func scanTapDeliversOneOfSeveral() {
        var d = ScanDebouncer()
        _ = d.observe([a, b], now: 0)
        #expect(d.tap(b, now: 0.1) == b)
        #expect(d.isPaused)
    }

    @Test func scanNothingIsDeliveredWhilePaused() {
        var d = ScanDebouncer()
        d.pause()
        #expect(d.observe([a], now: 0) == nil)
        #expect(d.tick(now: 5) == nil)
        #expect(d.tap(a, now: 5) == nil)
        #expect(d.nextWake == nil)
    }

    @Test func scanNoSecondDeliveryUntilResumed() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        #expect(d.tick(now: 0.4) == a)
        #expect(d.observe([a], now: 1) == nil)
        #expect(d.tick(now: 9) == nil)
    }

    @Test func scanSamePayloadIsIgnoredAfterResumeWhileStillInFrame() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.resume(now: 10)
        #expect(d.observe([a], now: 10.1) == nil)
        #expect(d.observe([a], now: 12.9) == nil)
        #expect(d.nextWake == 13)
    }

    @Test func scanSamePayloadIsAcceptedAfterThreeSeconds() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.resume(now: 10)
        _ = d.observe([a], now: 10.1)
        #expect(d.tick(now: 13) == nil)         // suppression ends, dwell starts
        #expect(d.nextWake == 13.4)
        #expect(d.tick(now: 13.4) == a)
    }

    @Test func scanSamePayloadIsAcceptedOnceItHasLeftTheFrame() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.resume(now: 10)
        _ = d.observe([a], now: 10.1)
        #expect(d.observe([], now: 10.5) == nil)
        #expect(d.observe([a], now: 11) == nil)
        #expect(d.tick(now: 11.4) == a)
    }

    @Test func scanADifferentPayloadIsNotSuppressed() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.resume(now: 10)
        #expect(d.observe([b], now: 10.1) == nil)
        #expect(d.tick(now: 10.5) == b)
    }

    @Test func scanTapIsNeverSuppressed() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.resume(now: 10)
        _ = d.observe([a, b], now: 10.1)
        #expect(d.tap(a, now: 10.2) == a)
    }

    @Test func scanResumeWithoutADeliveryIsHarmless() {
        var d = ScanDebouncer()
        d.resume(now: 0)
        _ = d.observe([a], now: 1)
        #expect(d.tick(now: 1.4) == a)
    }

    @Test func scanPauseResumeWithoutNewDeliveryKeepsTheSuppression() {
        var d = ScanDebouncer()
        _ = d.observe([a], now: 0)
        _ = d.tick(now: 0.4)
        d.pause()
        d.resume(now: 5)
        d.pause()
        d.resume(now: 6)
        #expect(d.observe([a], now: 6.1) == nil)
        #expect(d.tick(now: 8.9) == nil)
    }

    @Test func scanCustomTimings() {
        var d = ScanDebouncer(dwell: 1, repeatSuppression: 10)
        _ = d.observe([a], now: 0)
        #expect(d.tick(now: 0.9) == nil)
        #expect(d.tick(now: 1) == a)
        d.resume(now: 2)
        _ = d.observe([a], now: 2)
        #expect(d.nextWake == 12)
    }
}

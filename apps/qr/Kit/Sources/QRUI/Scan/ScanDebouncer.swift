import Foundation
import QRCore

/// D7 timing for the live scanner, as a pure value type so it can be tested without a camera.
///
/// - One code continuously in view for `dwell` (0.4 s) -> delivered.
/// - Several codes in view -> never auto-delivered; the person taps one (`tap`).
/// - After a delivery the debouncer is paused (the result sheet is up). `resume` restarts it and
///   ignores the delivered payload until it has left the frame or `repeatSuppression` (3 s) has
///   passed since the restart. A deliberate `tap` is never suppressed.
///
/// Time is injected: every call takes `now` in seconds from any monotonic clock.
struct ScanDebouncer: Sendable {
    let dwell: TimeInterval
    let repeatSuppression: TimeInterval

    private(set) var isPaused = false
    private var inView: [ScannedCode] = []
    private var candidate: ScannedCode?
    private var candidateSince: TimeInterval = 0
    private var lastDelivered: ScannedCode?
    private var suppressed: ScannedCode?
    private var suppressedUntil: TimeInterval = 0

    init(dwell: TimeInterval = 0.4, repeatSuppression: TimeInterval = 3) {
        self.dwell = dwell
        self.repeatSuppression = repeatSuppression
    }

    /// True when more than one distinct code is in view (the screen shows "tap a code").
    var hasMultiple: Bool { inView.count > 1 }

    /// The codes in view, de-duplicated, in the order they were reported.
    var codesInView: [ScannedCode] { inView }

    /// Report the full set of codes currently in view. Returns a code to deliver, if any.
    mutating func observe(_ codes: [ScannedCode], now: TimeInterval) -> ScannedCode? {
        var seen = Set<ScannedCode>()
        inView = codes.filter { seen.insert($0).inserted }
        if let s = suppressed, !seen.contains(s) { suppressed = nil }
        return evaluate(now: now)
    }

    /// Re-evaluate with the codes already reported (call at `nextWake`, since the camera reports
    /// changes, not the passing of time).
    mutating func tick(now: TimeInterval) -> ScannedCode? { evaluate(now: now) }

    /// The person tapped a highlighted code. Delivers it unless paused.
    mutating func tap(_ code: ScannedCode, now: TimeInterval) -> ScannedCode? {
        guard !isPaused else { return nil }
        return deliver(code)
    }

    /// Scanning stopped (sheet up). Nothing is delivered until `resume`.
    mutating func pause() {
        isPaused = true
        candidate = nil
        inView = []
    }

    /// Scanning restarted. The last delivered payload is ignored until it leaves the frame or
    /// `repeatSuppression` seconds from `now`.
    mutating func resume(now: TimeInterval) {
        guard isPaused else { return }
        isPaused = false
        candidate = nil
        inView = []
        if let lastDelivered {
            suppressed = lastDelivered
            suppressedUntil = now + repeatSuppression
        }
    }

    /// When `tick` would next change the outcome (absolute, same clock as `now`), or nil.
    var nextWake: TimeInterval? {
        guard !isPaused else { return nil }
        if candidate != nil { return candidateSince + dwell }
        if inView.count == 1, let s = suppressed, inView[0] == s { return suppressedUntil }
        return nil
    }

    private mutating func evaluate(now: TimeInterval) -> ScannedCode? {
        guard !isPaused else { return nil }
        if suppressed != nil, now >= suppressedUntil - 1e-9 { suppressed = nil }
        guard inView.count == 1, let code = inView.first else {
            candidate = nil
            return nil
        }
        if code == suppressed {
            candidate = nil
            return nil
        }
        if candidate != code {
            candidate = code
            candidateSince = now
        }
        guard now - candidateSince >= dwell - 1e-9 else { return nil }
        return deliver(code)
    }

    private mutating func deliver(_ code: ScannedCode) -> ScannedCode {
        lastDelivered = code
        suppressed = nil
        candidate = nil
        isPaused = true
        inView = []
        return code
    }
}

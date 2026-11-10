import SwiftUI
import QRCore

private enum PeekSamples {
    static let original = ResultSamples.link("https://bit.ly/3abc", host: "bit.ly", domain: "bit.ly", level: .careful,
                                            signals: [Signal(.shortener)])
    static let capture = PeekCapture(
        hops: [PeekHop(url: "https://bit.ly/3abc", status: 301), PeekHop(url: "https://login.example.net/verify", status: 200)],
        stoppedBecause: .finished, tlsVersion: "TLS 1.3")
    static let report = PeekReport(
        finalLink: nil,
        hopAnalyses: [original, ResultSamples.link("https://login.example.net/verify", host: "login.example.net", domain: "example.net")],
        title: "Sign in", signals: [Signal(.asksForSecret, ["kind": "password"]), Signal(.domainNew, ["days": "12"])], level: .careful)
}

#Preview("Peek finished") {
    NavigationStack {
        PeekViewContent(original: PeekSamples.original, state: .finished(PeekSamples.report),
                        capture: PeekSamples.capture, ageDays: 12)
    }
}
#Preview("Peek running, dark") {
    PeekViewContent(original: PeekSamples.original, state: .running(stage: .checkingAge),
                    capture: PeekSamples.capture, ageDays: nil).preferredColorScheme(.dark)
}
#Preview("Peek finished, largest text") {
    PeekViewContent(original: PeekSamples.original, state: .finished(PeekSamples.report),
                    capture: PeekSamples.capture, ageDays: nil).dynamicTypeSize(.accessibility5)
}

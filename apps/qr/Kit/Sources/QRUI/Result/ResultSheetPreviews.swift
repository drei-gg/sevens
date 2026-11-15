import SwiftUI
import QRCore

#Preview("Link, light") {
    ResultSheet(analysis: ResultSamples.normalLink).qrStore(.preview(state: .trial))
}
#Preview("Likely scam, dark") {
    ResultSheet(analysis: ResultSamples.scamLink).qrStore(.preview(state: .trial)).preferredColorScheme(.dark)
}
#Preview("Payment, largest text") {
    ResultSheet(analysis: ResultSamples.payment).qrStore(.preview(state: .trial))
        .dynamicTypeSize(.accessibility5)
}
#Preview("Crypto, light") {
    ResultSheet(analysis: ResultSamples.crypto).qrStore(.preview(state: .trial))
}
#Preview("Wi-Fi, dark") {
    ResultSheet(analysis: ResultSamples.wifi).qrStore(.preview(state: .trial)).preferredColorScheme(.dark)
}
#Preview("Contact with link, largest text") {
    ResultSheet(analysis: ResultSamples.contact).qrStore(.preview(state: .trial))
        .dynamicTypeSize(.accessibility5)
}
#Preview("Event and text") {
    VStack { ResultSheet(analysis: ResultSamples.event); ResultSheet(analysis: ResultSamples.text) }
        .qrStore(.preview(state: .trial))
}

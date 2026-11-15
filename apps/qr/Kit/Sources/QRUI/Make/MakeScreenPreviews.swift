import QRCore
import SwiftUI

#if DEBUG
private struct MakePreviewHost: View {
    var prefill: MakeRequest?
    var body: some View {
        NavigationStack { MakeScreen(prefill: prefill) }
            .qrStore(.preview(state: .unlocked))
    }
}

#Preview("Make, link, light") {
    MakePreviewHost(prefill: .link("https://example.org/menu")).preferredColorScheme(.light)
}

#Preview("Make, Wi-Fi, dark") {
    MakePreviewHost(prefill: .wifi(WiFiInfo(ssid: "Home", security: "WPA", password: "correct horse")))
        .preferredColorScheme(.dark)
}

#Preview("Make, accessibility5") {
    MakePreviewHost(prefill: .text("Hello from 7&D")).dynamicTypeSize(.accessibility5)
}

#Preview("Make, empty") {
    MakePreviewHost()
}
#endif

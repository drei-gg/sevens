import QRCore
import SevenKit
import SwiftUI

#if DEBUG
@MainActor
private func sampleImages() -> [ExtensionCheckImage] {
    let analyzer = Analyzer(data: BundledData.shared)
    func code(_ text: String) -> ExtensionCheckCode {
        ExtensionCheckCode(analysis: analyzer.analyze(ScannedCode(text: text)))
    }
    return [
        ExtensionCheckImage(position: 1, codes: [code("https://example.com/menu"),
                                                 code("http://paypa1-secure.example.zip/login")], unreadable: false),
        ExtensionCheckImage(position: 2, codes: [], unreadable: false),
        ExtensionCheckImage(position: 3, codes: [code("WIFI:T:WPA;S:Cafe Guest;P:hunter22;;")], unreadable: false),
    ]
}

#Preview("Check extension, light") {
    CheckExtensionRoot(previewResults: sampleImages()).preferredColorScheme(.light)
}

#Preview("Check extension, dark") {
    CheckExtensionRoot(previewResults: sampleImages()).preferredColorScheme(.dark)
}

#Preview("Check extension, accessibility5") {
    CheckExtensionRoot(previewResults: sampleImages()).dynamicTypeSize(.accessibility5)
}

#Preview("Check extension, empty") {
    CheckExtensionRoot(previewResults: [ExtensionCheckImage(position: 1, codes: [], unreadable: false)])
}
#endif

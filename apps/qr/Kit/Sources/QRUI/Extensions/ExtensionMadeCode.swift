import QRCore
import SwiftUI
import UIKit

/// A generated code ready to show and share. The PNG is also written to a temp file for ShareLink.
struct ExtensionMadeCode {
    let png: Data
    let image: UIImage
    let fileURL: URL?
    /// VoiceOver's label for the picture: "QR code for <short preview>".
    let accessibilityLabel: String

    /// Pixel size of the shared PNG.
    static let pixelSize = 1024

    /// `preview` is what the person would recognise (a link, a name); defaults to `text`.
    init?(text: String, preview: String? = nil) throws {
        let matrix = try CodeGenerator.generate(text: text, symbology: .qr, correction: .auto)
        let png = PNGRenderer.png(matrix: matrix, pixelSize: Self.pixelSize, foreground: .black, background: .white)
        guard let image = UIImage(data: png) else { return nil }
        self.png = png
        self.image = image
        self.accessibilityLabel = MakeSymbology.qr.accessibilityLabel(for: preview ?? text)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("QR code.png")
        self.fileURL = (try? png.write(to: url, options: .atomic)) == nil ? nil : url
    }
}

extension ExtensionMadeCode {
    /// The words a person would recognise for what went into the code: the link or text as is,
    /// a contact's name, a place's coordinates. Never the raw vCard or `geo:` encoding.
    static func preview(for request: MakeRequest) -> String {
        switch request {
        case .link(let text), .text(let text):
            return text
        case .contact(let info):
            let name = [info.name, [info.givenName, info.familyName].compactMap { $0 }.joined(separator: " "), info.organization]
                .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first { !$0.isEmpty }
            return name ?? info.phones.first ?? info.emails.first ?? ""
        case .wifi(let info):
            return info.ssid
        case .event(let info):
            return info.summary ?? info.location ?? ""
        case .email(let info):
            return info.address
        case .sms(let info):
            return info.number
        case .phone(let number):
            return number
        case .geo(let info):
            return info.query ?? "\(info.latitude), \(info.longitude)"
        }
    }
}

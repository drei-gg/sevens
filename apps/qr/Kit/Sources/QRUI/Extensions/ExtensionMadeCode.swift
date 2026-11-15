import QRCore
import SwiftUI
import UIKit

/// A generated code ready to show and share. The PNG is also written to a temp file for ShareLink.
struct ExtensionMadeCode {
    let png: Data
    let image: UIImage
    let fileURL: URL?

    /// Pixel size of the shared PNG.
    static let pixelSize = 1024

    init?(text: String) throws {
        let matrix = try CodeGenerator.generate(text: text, symbology: .qr, correction: .auto)
        let png = PNGRenderer.png(matrix: matrix, pixelSize: Self.pixelSize, foreground: .black, background: .white)
        guard let image = UIImage(data: png) else { return nil }
        self.png = png
        self.image = image
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("QR code.png")
        self.fileURL = (try? png.write(to: url, options: .atomic)) == nil ? nil : url
    }
}

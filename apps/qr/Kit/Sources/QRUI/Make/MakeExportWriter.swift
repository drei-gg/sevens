import Foundation
import QRCore

/// Turns the chosen export options into file bytes and a file on disk.
enum MakeExportWriter {
    /// Bytes for `options`. PNG size and PDF layout come from the options.
    static func data(options: MakeExportOptions, matrix: BitMatrix, foreground: RGBColor, background: RGBColor) -> Data {
        switch options.format {
        case .png:
            return PNGRenderer.png(matrix: matrix, pixelSize: options.pixelSize, foreground: foreground, background: background)
        case .svg:
            return SVGRenderer.data(matrix: matrix, moduleSize: 10, foreground: foreground, background: background)
        case .pdf:
            switch options.pdfLayout {
            case .single:
                return PDFRenderer.single(matrix: matrix, caption: options.cleanCaption, foreground: foreground,
                                          background: background, paper: options.paper)
            case .sheet:
                return PDFRenderer.sheet(matrix: matrix, caption: options.cleanCaption, grid: options.grid,
                                         paper: options.paper, foreground: foreground, background: background)
            }
        }
    }

    /// Writes into the app's temporary `qr-export` folder (no Photos permission, no Documents).
    static func write(_ data: Data, named name: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("qr-export", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return url
    }
}

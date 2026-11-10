import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import QRCore

/// Text to `BitMatrix` through CoreImage (SPEC §8).
public enum CodeGenerator {
    /// Longest input we hand to CoreImage, bytes. Bigger inputs are `tooLong` (no QR holds more than 2953).
    static let maxInputBytes = 8192
    /// PDF417 rows are expanded to this many modules tall (the usual minimum row height).
    static let pdf417RowHeight = 3

    /// Dark modules are `true`. The result includes our quiet zone (`QuietZone.modules(for:)`).
    /// Code 128 returns a single row; renderers stretch it (see `MakeGeometry.rows(for:)`).
    public static func generate(text: String, symbology: MakeSymbology, correction: Correction) throws -> BitMatrix {
        guard !text.isEmpty else { throw MakeError.empty }
        let data: Data
        if symbology == .code128 {
            guard text.unicodeScalars.allSatisfy({ $0.value < 128 }) else { throw MakeError.notASCII }
            data = Data(text.utf8)
        } else {
            data = Data(text.utf8)
        }
        guard data.count <= maxInputBytes else { throw MakeError.tooLong }

        guard let image = makeImage(data: data, symbology: symbology, correction: correction) else {
            throw MakeError.tooLong
        }
        guard let raw = bitmap(of: image), var matrix = trimmed(raw) else { throw MakeError.generatorFailed }
        switch symbology {
        case .code128: matrix = firstRow(of: matrix)
        case .pdf417: matrix = collapsedRows(matrix, rowHeight: pdf417RowHeight)
        case .qr, .aztec: break
        }
        let quiet = QuietZone.modules(for: symbology.symbology)
        return QuietZone.add(to: matrix, horizontal: quiet.horizontal, vertical: quiet.vertical)
    }

    private static func makeImage(data: Data, symbology: MakeSymbology, correction: Correction) -> CIImage? {
        switch symbology {
        case .qr:
            let f = CIFilter.qrCodeGenerator()
            f.message = data
            f.correctionLevel = correction.qrLevel
            return f.outputImage
        case .aztec:
            let f = CIFilter.aztecCodeGenerator()
            f.message = data
            f.correctionLevel = correction.aztecPercent
            return f.outputImage
        case .pdf417:
            let f = CIFilter.pdf417BarcodeGenerator()
            f.message = data
            return f.outputImage
        case .code128:
            let f = CIFilter.code128BarcodeGenerator()
            f.message = data
            f.quietSpace = 0
            return f.outputImage
        }
    }

    /// Dark/light grid at one pixel per source pixel, thresholded at 50 %.
    private static func bitmap(of image: CIImage) -> (w: Int, h: Int, dark: [Bool])? {
        let extent = image.extent.integral
        guard extent.width.isFinite, extent.height.isFinite,
              extent.width >= 1, extent.height >= 1, extent.width <= 8192, extent.height <= 8192 else { return nil }
        let w = Int(extent.width), h = Int(extent.height)
        var pixels = [UInt8](repeating: 255, count: w * h)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        pixels.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            context.render(image, toBitmap: base, rowBytes: w, bounds: extent,
                           format: .L8, colorSpace: CGColorSpaceCreateDeviceGray())
        }
        // CoreImage bitmaps are top-down in memory for this call.
        return (w, h, pixels.map { $0 < 128 })
    }

    /// Crops to the bounding box of the dark modules; nil when nothing is dark.
    private static func trimmed(_ raw: (w: Int, h: Int, dark: [Bool])) -> BitMatrix? {
        var minX = raw.w, maxX = -1, minY = raw.h, maxY = -1
        for y in 0..<raw.h {
            for x in 0..<raw.w where raw.dark[y * raw.w + x] {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        let w = maxX - minX + 1, h = maxY - minY + 1
        var bits: [Bool] = []
        bits.reserveCapacity(w * h)
        for y in minY...maxY {
            for x in minX...maxX { bits.append(raw.dark[y * raw.w + x]) }
        }
        return BitMatrix(width: w, height: h, bits: bits)
    }

    private static func firstRow(of m: BitMatrix) -> BitMatrix {
        BitMatrix(width: m.width, height: 1, bits: (0..<m.width).map { m[$0, 0] })
    }

    /// The filter draws each PDF417 row several pixels tall. Merge identical neighbours (adjacent
    /// PDF417 rows always differ) and give every row `rowHeight` modules.
    private static func collapsedRows(_ m: BitMatrix, rowHeight: Int) -> BitMatrix {
        var rows: [[Bool]] = []
        for y in 0..<m.height {
            let row = (0..<m.width).map { m[$0, y] }
            if rows.last != row { rows.append(row) }
        }
        var bits: [Bool] = []
        bits.reserveCapacity(m.width * rows.count * rowHeight)
        for row in rows {
            for _ in 0..<rowHeight { bits.append(contentsOf: row) }
        }
        return BitMatrix(width: m.width, height: rows.count * rowHeight, bits: bits)
    }
}

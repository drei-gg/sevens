import Foundation

public enum QuietZone {
    /// Quiet zone in modules, (left and right, top and bottom). SPEC §8: QR 4 all round (Micro QR 2),
    /// Aztec 1, PDF417 and MicroPDF417 2, Data Matrix 1, Code 128 and the other 1D codes 10 left and
    /// right (0 top and bottom). Anything else 4.
    public static func modules(for symbology: Symbology) -> (horizontal: Int, vertical: Int) {
        switch symbology {
        case .qr: return (4, 4)
        case .microQR: return (2, 2)
        case .aztec: return (1, 1)
        case .pdf417, .microPDF417: return (2, 2)
        case .dataMatrix: return (1, 1)
        case .code128, .code39, .code93, .ean13, .ean8, .upce, .itf14, .codabar, .gs1DataBar: return (10, 0)
        case .other: return (4, 4)
        }
    }

    /// Pads with light modules: `horizontal` columns each side, `vertical` rows top and bottom.
    /// Negative values count as 0. An empty matrix stays empty.
    public static func add(to matrix: BitMatrix, horizontal: Int, vertical: Int) -> BitMatrix {
        guard matrix.width > 0, matrix.height > 0 else { return matrix }
        let h = max(0, horizontal), v = max(0, vertical)
        let w = matrix.width + 2 * h, ht = matrix.height + 2 * v
        var bits = Array(repeating: false, count: w * ht)
        for y in 0..<matrix.height {
            for x in 0..<matrix.width where matrix[x, y] {
                bits[(y + v) * w + x + h] = true
            }
        }
        return BitMatrix(width: w, height: ht, bits: bits)
    }
}

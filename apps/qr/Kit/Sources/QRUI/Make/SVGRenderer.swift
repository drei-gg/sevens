import Foundation
import QRCore

public enum SVGRenderer {
    /// UTF-8 SVG via `SVGWriter`. One-row codes are stretched to the 1D bar height first.
    public static func data(matrix: BitMatrix, moduleSize: Int, foreground: RGBColor, background: RGBColor?) -> Data {
        var m = matrix
        if matrix.height == 1 {
            let rows = MakeGeometry.rows(for: matrix)
            m = BitMatrix(width: matrix.width, height: rows,
                          bits: (0..<rows).flatMap { _ in (0..<matrix.width).map { matrix[$0, 0] } })
        }
        return Data(SVGWriter.svg(m, moduleSize: moduleSize, foreground: foreground, background: background).utf8)
    }
}

import CoreGraphics
import Foundation
import ImageIO
import QRCore
import UniformTypeIdentifiers

public enum PNGRenderer {
    /// Offered sizes; custom sizes are clamped to `1...maxPixelSize`.
    public static let presetSizes = [512, 1024, 2048]
    public static let maxPixelSize = 4096

    /// Integer pixels per module for a requested size: floor(pixelSize / matrix.columns), at least 1.
    public static func scale(matrixWidth: Int, pixelSize: Int) -> Int {
        guard matrixWidth > 0 else { return 1 }
        return max(1, min(max(1, pixelSize), maxPixelSize) / matrixWidth)
    }

    /// Opaque sRGB PNG. The image is `scale x modules` wide, so it can be a little under `pixelSize`
    /// (never over, unless the matrix itself is wider than `pixelSize`). One-row codes get the bar
    /// height from `MakeGeometry`. No interpolation. Empty data for an empty matrix.
    public static func png(matrix: BitMatrix, pixelSize: Int, foreground: RGBColor, background: RGBColor) -> Data {
        guard matrix.columns > 0, matrix.rows > 0 else { return Data() }
        let s = scale(matrixWidth: matrix.columns, pixelSize: pixelSize)
        let rows = MakeGeometry.rows(for: matrix)
        let w = matrix.columns * s, h = rows * s
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return Data() }
        ctx.interpolationQuality = .none
        ctx.setShouldAntialias(false)
        ctx.setFillColor(CGColor(srgbRed: background.red, green: background.green, blue: background.blue, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.setFillColor(CGColor(srgbRed: foreground.red, green: foreground.green, blue: foreground.blue, alpha: 1))
        for run in MakeGeometry.runs(matrix) {
            // CoreGraphics origin is bottom-left.
            ctx.fill(CGRect(x: run.x * s, y: h - (run.row + 1) * s, width: run.length * s, height: s))
        }
        guard let image = ctx.makeImage() else { return Data() }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil) else { return Data() }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { return Data() }
        return out as Data
    }
}

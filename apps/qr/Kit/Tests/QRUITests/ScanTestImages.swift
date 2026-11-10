import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import Vision

/// Test barcodes generated with CoreImage (the test target has no resources).
enum ScanTestImages {
    static let context = CIContext(options: [.useSoftwareRenderer: true])

    static func qr(_ text: String, level: String = "M") -> CIImage {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = level
        return f.outputImage ?? CIImage.empty()
    }

    static func aztec(_ text: String) -> CIImage {
        let f = CIFilter.aztecCodeGenerator()
        f.message = Data(text.utf8)
        return f.outputImage ?? CIImage.empty()
    }

    /// Nearest-neighbour scale so modules stay crisp.
    static func scaled(_ image: CIImage, by k: CGFloat) -> CIImage {
        image.samplingNearest().transformed(by: CGAffineTransform(scaleX: k, y: k))
    }

    static func white(_ rect: CGRect) -> CIImage {
        CIImage(color: .white).cropped(to: rect)
    }

    /// Codes placed on one white canvas, each at the given origin (CoreImage coordinates, y up).
    static func canvas(_ placed: [(CIImage, CGPoint)], size: CGSize) -> CGImage? {
        let rect = CGRect(origin: .zero, size: size)
        var out = white(rect)
        for (image, origin) in placed {
            let moved = image.transformed(by: CGAffineTransform(translationX: origin.x - image.extent.minX,
                                                                y: origin.y - image.extent.minY))
            out = moved.composited(over: out)
        }
        return context.createCGImage(out, from: rect)
    }

    static func single(_ image: CIImage, scale: CGFloat = 8, margin: CGFloat = 40) -> CGImage? {
        let big = scaled(image, by: scale)
        let size = CGSize(width: big.extent.width + 2 * margin, height: big.extent.height + 2 * margin)
        return canvas([(big, CGPoint(x: margin, y: margin))], size: size)
    }

    /// The code rotated about its centre on a white square, drawn with CoreGraphics.
    static func rotated(_ image: CIImage, degrees: CGFloat, scale: CGFloat = 8) -> CGImage? {
        guard let flat = single(image, scale: scale, margin: 0) else { return nil }
        let w = CGFloat(flat.width), h = CGFloat(flat.height)
        let side = Int((max(w, h) * 1.6).rounded(.up))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
        ctx.translateBy(x: CGFloat(side) / 2, y: CGFloat(side) / 2)
        ctx.rotate(by: degrees * .pi / 180)
        ctx.interpolationQuality = .none
        ctx.draw(flat, in: CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
        return ctx.makeImage()
    }

    static func blank() -> CGImage? {
        context.createCGImage(white(CGRect(x: 0, y: 0, width: 400, height: 400)), from: CGRect(x: 0, y: 0, width: 400, height: 400))
    }

    static func pngData(_ image: CGImage) -> Data? { UIImage(cgImage: image).pngData() }
}

extension ScanTestImages {
    /// True only if Vision in this environment decodes a generated QR. The VM's Simulator has
    /// shown "Could not create inference context" and empty results; Vision-dependent tests are
    /// skipped there and CIDetector-based tests (QR only) cover the generated images instead.
    static let visionAvailable: Bool = {
        guard let image = single(qr("canary")) else { return false }
        let request = VNDetectBarcodesRequest()
        #if targetEnvironment(simulator)
        request.revision = 1
        #endif
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        guard (try? handler.perform([request])) != nil else { return false }
        return request.results?.first?.payloadStringValue == "canary"
    }()

    static func detectQR(_ image: CGImage) -> [String] {
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: nil)
        return (detector?.features(in: CIImage(cgImage: image)) ?? []).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}

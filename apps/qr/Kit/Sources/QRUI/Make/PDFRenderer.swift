import CoreGraphics
import Foundation
import QRCore
import UIKit

public enum PDFRenderer {
    /// Caption sizes are the one fixed font size allowed (points).
    static let singleCaptionSize: CGFloat = 12
    static let sheetCaptionSize: CGFloat = 8

    /// One code centered on the page inside the 36 pt margin, caption (if any) under it.
    public static func single(matrix: BitMatrix, caption: String?, foreground: RGBColor = .black,
                              background: RGBColor = .white, paper: PaperSize) -> Data {
        let page = CGRect(x: 0, y: 0, width: paper.size.width, height: paper.size.height)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        return renderer.pdfData { ctx in
            ctx.beginPage()
            let area = page.insetBy(dx: SheetLayout.margin, dy: SheetLayout.margin)
            // Fit by the larger side of the page's short edge so the code stays a sensible size.
            let side = min(area.width, area.height * 0.8)
            let box = CGRect(x: area.midX - side / 2, y: area.midY - side / 2 - 10, width: side, height: side)
            draw(matrix: matrix, in: box, caption: caption, captionSize: singleCaptionSize,
                 foreground: foreground, background: background, context: ctx.cgContext)
        }
    }

    /// A one-page label sheet (D14): the same code in every cell of `SheetLayout.cells`, scaled to fit.
    public static func sheet(matrix: BitMatrix, caption: String?, grid: SheetGrid, paper: PaperSize,
                             foreground: RGBColor = .black, background: RGBColor = .white) -> Data {
        let page = CGRect(x: 0, y: 0, width: paper.size.width, height: paper.size.height)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        return renderer.pdfData { ctx in
            ctx.beginPage()
            for cell in SheetLayout.cells(grid: grid, paper: paper) {
                draw(matrix: matrix, in: CGRect(x: cell.x, y: cell.y, width: cell.width, height: cell.height),
                     caption: caption, captionSize: sheetCaptionSize,
                     foreground: foreground, background: background, context: ctx.cgContext)
            }
        }
    }

    /// Draws the code scaled to fit `box`, centered horizontally and kept at the top, caption below it.
    private static func draw(matrix: BitMatrix, in box: CGRect, caption: String?, captionSize: CGFloat,
                             foreground: RGBColor, background: RGBColor, context: CGContext) {
        guard matrix.width > 0, matrix.height > 0 else { return }
        let rows = MakeGeometry.rows(for: matrix)
        let text = caption?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasCaption = !(text ?? "").isEmpty
        let font = UIFont.systemFont(ofSize: captionSize)
        let captionHeight: CGFloat = hasCaption ? ceil(font.lineHeight) + 4 : 0
        let availW = box.width, availH = max(1, box.height - captionHeight)
        var module = min(availW / CGFloat(matrix.width), availH / CGFloat(rows))
        if module >= 1 { module = module.rounded(.down) }   // whole points: crisp edges
        let w = module * CGFloat(matrix.width), h = module * CGFloat(rows)
        var ox = box.minX + (availW - w) / 2
        var oy = box.minY + (hasCaption ? 0 : (availH - h) / 2)
        if module >= 1 { ox = ox.rounded(); oy = oy.rounded() }

        context.setFillColor(CGColor(srgbRed: background.red, green: background.green, blue: background.blue, alpha: 1))
        context.fill(CGRect(x: ox, y: oy, width: w, height: h))
        let path = CGMutablePath()
        for run in MakeGeometry.runs(matrix) {
            path.addRect(CGRect(x: ox + CGFloat(run.x) * module, y: oy + CGFloat(run.row) * module,
                                width: CGFloat(run.length) * module, height: module))
        }
        context.setFillColor(CGColor(srgbRed: foreground.red, green: foreground.green, blue: foreground.blue, alpha: 1))
        context.addPath(path)
        context.fillPath()

        if hasCaption, let text {
            let style = NSMutableParagraphStyle()
            style.alignment = .center
            style.lineBreakMode = .byTruncatingTail
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.black, .paragraphStyle: style]
            let rect = CGRect(x: box.minX, y: oy + h + 3, width: box.width, height: ceil(font.lineHeight))
            (text as NSString).draw(in: rect, withAttributes: attrs)
        }
    }
}

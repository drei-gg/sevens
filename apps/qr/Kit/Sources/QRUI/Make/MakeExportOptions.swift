import Foundation
import QRCore

enum MakeExportFormat: String, CaseIterable, Identifiable, Hashable, Sendable {
    case png, svg, pdf
    var id: String { rawValue }
    var fileExtension: String { rawValue }
    var title: String {
        switch self {
        case .png: return String(localized: "make.export.png", defaultValue: "PNG image", bundle: .module)
        case .svg: return String(localized: "make.export.svg", defaultValue: "SVG (vector)", bundle: .module)
        case .pdf: return String(localized: "make.export.pdf", defaultValue: "PDF", bundle: .module)
        }
    }
}

enum MakePDFLayout: String, CaseIterable, Identifiable, Hashable, Sendable {
    case single, sheet
    var id: String { rawValue }
}

extension SheetGrid {
    var title: String {
        switch self {
        case .grid2x3: return String(localized: "make.grid.2x3", defaultValue: "2 by 3 (6 labels)", bundle: .module)
        case .grid3x4: return String(localized: "make.grid.3x4", defaultValue: "3 by 4 (12 labels)", bundle: .module)
        case .grid4x6: return String(localized: "make.grid.4x6", defaultValue: "4 by 6 (24 labels)", bundle: .module)
        }
    }
}

extension PaperSize {
    var title: String {
        switch self {
        case .a4: return String(localized: "make.paper.a4", defaultValue: "A4", bundle: .module)
        case .letter: return String(localized: "make.paper.letter", defaultValue: "US Letter", bundle: .module)
        }
    }
}

/// The export choices and the pure rules around them.
struct MakeExportOptions: Hashable, Sendable {
    enum SizeChoice: Hashable, Sendable, CaseIterable {
        case s512, s1024, s2048, custom
        var preset: Int? {
            switch self {
            case .s512: return 512
            case .s1024: return 1024
            case .s2048: return 2048
            case .custom: return nil
            }
        }
    }

    var format = MakeExportFormat.png
    var sizeChoice = SizeChoice.s1024
    var customSize = "1024"
    var pdfLayout = MakePDFLayout.single
    var grid = SheetGrid.grid3x4
    var paper = PaperSize.forRegion(Locale.current.region?.identifier)
    var caption = ""

    static let minPixelSize = 32

    /// Pixel size to render: the preset, or the typed number clamped to 32…4096 (blank or not a
    /// number reads as 1024).
    var pixelSize: Int {
        sizeChoice.preset ?? Self.clampedSize(customSize)
    }

    static func clampedSize(_ raw: String) -> Int {
        let digits = raw.trimmingCharacters(in: .whitespaces)
        guard let n = Int(digits) else {
            // Overlong digit strings overflow Int; they are "too big", not "blank".
            return !digits.isEmpty && digits.allSatisfy({ $0.isASCII && $0.isNumber }) ? PNGRenderer.maxPixelSize : 1024
        }
        return min(PNGRenderer.maxPixelSize, max(minPixelSize, n))
    }

    /// The typed custom size was changed to fit.
    var customSizeWasClamped: Bool {
        guard sizeChoice == .custom, let n = Int(customSize.trimmingCharacters(in: .whitespaces)) else { return false }
        return n != Self.clampedSize(customSize)
    }

    var cleanCaption: String? {
        let t = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    /// `qr-link-20260311-1405.png`; sheets end `-sheet.pdf`. The prefix is the symbology (`qr`,
    /// `aztec`, `pdf417`, `code128`). Fixed calendar and digits, whatever the person's locale.
    static func fileName(symbology: MakeSymbology, kind: MakeFormKind, date: Date, format: MakeExportFormat,
                         sheet: Bool = false, timeZone: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        func two(_ v: Int?) -> String { String(format: "%02d", v ?? 0) }
        let stamp = String(format: "%04d", c.year ?? 0) + two(c.month) + two(c.day) + "-" + two(c.hour) + two(c.minute)
        let suffix = sheet && format == .pdf ? "-sheet" : ""
        return "\(symbology.rawValue)-\(kind.fileToken)-\(stamp)\(suffix).\(format.fileExtension)"
    }
}

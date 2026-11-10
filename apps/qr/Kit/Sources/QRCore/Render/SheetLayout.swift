import Foundation

/// D14: generic label grids on A4 or US Letter.
public enum PaperSize: String, Sendable, CaseIterable {
    case a4, letter
    /// Points (1/72 in).
    public var size: (width: Double, height: Double) {
        switch self {
        case .a4: return (595.28, 841.89)
        case .letter: return (612, 792)
        }
    }

    /// Regions CLDR lists as US Letter (supplementalData.xml `paperSize`).
    static let letterRegions: Set<String> = ["BZ", "CA", "CL", "CO", "CR", "GT", "MX", "NI", "PA", "PH", "PR", "SV", "US", "VE"]

    /// US Letter for regions that use it (CLDR list), A4 otherwise and for nil or unknown codes.
    public static func forRegion(_ regionCode: String?) -> PaperSize {
        guard let code = regionCode?.uppercased() else { return .a4 }
        return letterRegions.contains(code) ? .letter : .a4
    }
}

public enum SheetGrid: String, Sendable, CaseIterable {
    case grid2x3, grid3x4, grid4x6
    public var columns: Int {
        switch self {
        case .grid2x3: return 2
        case .grid3x4: return 3
        case .grid4x6: return 4
        }
    }
    public var rows: Int {
        switch self {
        case .grid2x3: return 3
        case .grid3x4: return 4
        case .grid4x6: return 6
        }
    }
}

public struct SheetCell: Sendable, Hashable {
    /// Points from the top-left of the page.
    public var x: Double, y: Double, width: Double, height: Double
    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public enum SheetLayout {
    /// Page margin on every side, points.
    public static let margin: Double = 36
    /// Gap between neighbouring cells, points.
    public static let gutter: Double = 12

    /// Cells left to right, top to bottom, inside fixed page margins (36 pt) with a 12 pt gutter.
    public static func cells(grid: SheetGrid, paper: PaperSize) -> [SheetCell] {
        let page = paper.size
        let cols = grid.columns, rows = grid.rows
        let cw = (page.width - 2 * margin - Double(cols - 1) * gutter) / Double(cols)
        let ch = (page.height - 2 * margin - Double(rows - 1) * gutter) / Double(rows)
        var out: [SheetCell] = []
        out.reserveCapacity(cols * rows)
        for r in 0..<rows {
            for c in 0..<cols {
                out.append(SheetCell(x: margin + Double(c) * (cw + gutter),
                                     y: margin + Double(r) * (ch + gutter),
                                     width: cw, height: ch))
            }
        }
        return out
    }
}

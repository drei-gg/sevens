import QRCore

/// Shared drawing geometry for the renderers.
enum MakeGeometry {
    /// Bar height of a one-row (1D) code as a share of its width.
    static let barHeightRatio = 0.3

    /// Rows to draw: the matrix height, except a one-row code is stretched to 0.3 x width (min 1).
    static func rows(for matrix: BitMatrix) -> Int {
        guard matrix.rows == 1 else { return matrix.rows }
        return max(1, Int((Double(matrix.columns) * barHeightRatio).rounded()))
    }

    /// Source row for a drawn row.
    static func sourceRow(_ drawn: Int, matrix: BitMatrix) -> Int {
        matrix.rows == 1 ? 0 : drawn
    }

    /// Horizontal runs of dark modules per drawn row, as (row, start, length).
    static func runs(_ matrix: BitMatrix) -> [(row: Int, x: Int, length: Int)] {
        var out: [(row: Int, x: Int, length: Int)] = []
        for y in 0..<rows(for: matrix) {
            let sy = sourceRow(y, matrix: matrix)
            var x = 0
            while x < matrix.columns {
                guard matrix[x, sy] else { x += 1; continue }
                var end = x + 1
                while end < matrix.columns, matrix[end, sy] { end += 1 }
                out.append((y, x, end - x))
                x = end
            }
        }
        return out
    }
}

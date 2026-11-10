// Contract (PLAN §5.4). WP 1.7 owns this folder and may add to this type; don't change what's here.

/// A grid of modules, row-major, `true` = dark.
public struct BitMatrix: Sendable, Hashable {
    public let width: Int, height: Int
    let bits: [Bool]

    /// `bits.count` must be `width * height`; otherwise the matrix is empty.
    public init(width: Int, height: Int, bits: [Bool]) {
        if width > 0, height > 0, bits.count == width * height {
            self.width = width
            self.height = height
            self.bits = bits
        } else {
            self.width = 0
            self.height = 0
            self.bits = []
        }
    }

    /// All light.
    public init(width: Int, height: Int) {
        self.init(width: width, height: height, bits: Array(repeating: false, count: max(0, width) * max(0, height)))
    }

    /// Out of range reads as light.
    public subscript(x: Int, y: Int) -> Bool {
        guard x >= 0, y >= 0, x < width, y < height else { return false }
        return bits[y * width + x]
    }
}

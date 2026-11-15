// swift-tools-version: 6.2
import PackageDescription
#if canImport(Darwin)
let strict: [SwiftSetting] = []
#else
let strict: [SwiftSetting] = [.treatAllWarnings(as: .error)]
#endif
// ScannerFiles (the store's file primitives) is plain Swift and builds anywhere. ScannerCore is the
// Rust core: its Swift bindings in Sources/ScannerCore/Generated and the static library in
// Generated/scanner_coreFFI.xcframework, both made by `scripts/rust-core scanner apple`, plus the
// native DocumentStore that asks it for every rule.
// ScannerFiles is not a product: only ScannerCore uses it, so the package keeps its one library.
var products: [Product] = []
var targets: [Target] = [
    .target(name: "ScannerFiles", swiftSettings: strict),
    .testTarget(name: "ScannerFilesTests", dependencies: ["ScannerFiles"], swiftSettings: strict),
]
#if canImport(Darwin)
products.append(.library(name: "ScannerCore", targets: ["ScannerCore"]))
targets += [
    .binaryTarget(name: "scanner_coreFFI", path: "Generated/scanner_coreFFI.xcframework"),
    .target(name: "ScannerCore", dependencies: ["ScannerFiles", "scanner_coreFFI"], swiftSettings: strict),
    .testTarget(name: "ScannerCoreTests", dependencies: ["ScannerCore"], swiftSettings: strict),
]
#endif
let package = Package(name: "ScannerKit", platforms: [.iOS(.v17)], products: products, targets: targets)

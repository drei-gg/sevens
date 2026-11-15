// swift-tools-version: 6.2
import PackageDescription
#if canImport(Darwin)
let strict: [SwiftSetting] = []
#else
let strict: [SwiftSetting] = [.treatAllWarnings(as: .error)]
#endif
// FastWire (the Watch and widget data) is plain Swift and builds anywhere. FastCore is the Rust
// core: its Swift bindings in Sources/FastCore/Generated and the static library in
// Generated/fast_coreFFI.xcframework, both made by `scripts/rust-core fast apple`.
var dependencies: [Package.Dependency] = []
var products: [Product] = [.library(name: "FastWire", targets: ["FastWire"])]
var targets: [Target] = [
    .target(name: "FastWire", swiftSettings: strict),
    .testTarget(name: "FastWireTests", dependencies: ["FastWire"], swiftSettings: strict),
]
#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products += [.library(name: "FastCore", targets: ["FastCore"]),
             .library(name: "FastUI", targets: ["FastUI"]),
             .library(name: "FastSurfaces", targets: ["FastSurfaces"])]
targets += [
    .binaryTarget(name: "fast_coreFFI", path: "Generated/fast_coreFFI.xcframework"),
    .target(name: "FastCore", dependencies: ["FastWire", "fast_coreFFI"], swiftSettings: strict),
    .testTarget(name: "FastCoreTests", dependencies: ["FastCore"], swiftSettings: strict),
    .target(name: "FastSurfaces", swiftSettings: strict),
    .target(name: "FastUI", dependencies: ["FastCore", "FastSurfaces", .product(name: "SevenKit", package: "SevenKit"), .product(name: "SevenCompanion", package: "SevenKit")],
            resources: [.process("Resources")], swiftSettings: strict),
    .testTarget(name: "FastUITests", dependencies: ["FastUI"], swiftSettings: strict),
]
#endif
let package = Package(name: "FastKit", defaultLocalization: "en", platforms: [.iOS(.v17), .macOS(.v14), .watchOS(.v10)],
                      products: products, dependencies: dependencies, targets: targets)

// swift-tools-version: 6.2
import PackageDescription
#if canImport(Darwin)
let strict: [SwiftSetting] = []
#else
let strict: [SwiftSetting] = [.treatAllWarnings(as: .error)]
#endif
var dependencies: [Package.Dependency] = []
var products: [Product] = [.library(name: "FastCore", targets: ["FastCore"])]
var targets: [Target] = [
    .target(name: "FastCore", swiftSettings: strict),
    .testTarget(name: "FastCoreTests", dependencies: ["FastCore"], swiftSettings: strict),
]
#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products.append(.library(name: "FastUI", targets: ["FastUI"]))
products.append(.library(name: "FastSurfaces", targets: ["FastSurfaces"]))
targets += [
    .target(name: "FastSurfaces", swiftSettings: strict),
    .target(name: "FastUI", dependencies: ["FastCore", "FastSurfaces", .product(name: "SevenKit", package: "SevenKit"), .product(name: "SevenCompanion", package: "SevenKit")],
            resources: [.process("Resources")], swiftSettings: strict),
    .testTarget(name: "FastUITests", dependencies: ["FastUI"], swiftSettings: strict),
]
#endif
let package = Package(name: "FastKit", defaultLocalization: "en", platforms: [.iOS(.v17), .macOS(.v14), .watchOS(.v10)],
                      products: products, dependencies: dependencies, targets: targets)

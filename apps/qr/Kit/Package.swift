// swift-tools-version: 6.2
// QRKit: the QR app's logic (QRCore, pure Swift, Linux + Apple) and screens (QRUI, Apple only).
// Zero dependencies apart from the local SevenKit package on Apple hosts.
import PackageDescription

// Warnings are errors. On Apple hosts Xcode adds -suppress-warnings to package targets, which
// conflicts with this setting, so there scripts/vm passes SWIFT_SUPPRESS_WARNINGS=NO and
// SWIFT_TREAT_WARNINGS_AS_ERRORS=YES on the xcodebuild command line instead.
#if canImport(Darwin)
let strict: [SwiftSetting] = []
#else
let strict: [SwiftSetting] = [.treatAllWarnings(as: .error)]
#endif

var dependencies: [Package.Dependency] = []
var products: [Product] = [
    .library(name: "QRCore", targets: ["QRCore"]),
]
var targets: [Target] = [
    .target(name: "QRCore", resources: [.copy("Data")], swiftSettings: strict),
    .testTarget(name: "QRCoreTests", dependencies: ["QRCore"],
                resources: [.copy("Fixtures")], swiftSettings: strict),
]

#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products.append(.library(name: "QRUI", targets: ["QRUI"]))
targets += [
    .target(name: "QRUI",
            dependencies: ["QRCore", .product(name: "SevenKit", package: "SevenKit")],
            resources: [.process("Resources")], swiftSettings: strict),
    .testTarget(name: "QRUITests", dependencies: ["QRUI"], swiftSettings: strict),
]
#endif

let package = Package(
    name: "QRKit",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: products,
    dependencies: dependencies,
    targets: targets
)

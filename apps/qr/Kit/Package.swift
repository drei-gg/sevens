// swift-tools-version: 6.2
// QRKit: the QR app's logic and screens. QRFiles (the saved lists file) is plain Swift and builds
// anywhere. QRCore is the Rust core (apps/qr/core): its Swift bindings in Sources/QRCore/Generated
// and the static library in Generated/qr_coreFFI.xcframework, both made by `scripts/rust-core qr
// apple`, plus a thin façade. QRUI (screens, stores, networking) is Apple only.
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
var products: [Product] = [.library(name: "QRFiles", targets: ["QRFiles"])]
var targets: [Target] = [
    .target(name: "QRFiles", swiftSettings: strict),
    .testTarget(name: "QRFilesTests", dependencies: ["QRFiles"], swiftSettings: strict),
]

#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products += [.library(name: "QRCore", targets: ["QRCore"]),
             .library(name: "QRUI", targets: ["QRUI"])]
targets += [
    .binaryTarget(name: "qr_coreFFI", path: "Generated/qr_coreFFI.xcframework"),
    // Data/ is read by the crate at build time (include_bytes), so it is not a Swift resource.
    .target(name: "QRCore", dependencies: ["QRFiles", "qr_coreFFI"], exclude: ["Data"], swiftSettings: strict),
    .testTarget(name: "QRCoreTests", dependencies: ["QRCore"],
                resources: [.copy("Fixtures")], swiftSettings: strict),
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

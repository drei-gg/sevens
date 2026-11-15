// swift-tools-version: 6.2
// SevenKit: shared by every Sevens app. Zero dependencies.
// SevenKitCore is pure Swift (Linux + Apple); SevenKit (StoreKit, SwiftUI) is declared only on Apple hosts.
import PackageDescription

// Warnings are errors. On Apple hosts Xcode adds -suppress-warnings to package targets, which
// conflicts with this setting, so there scripts/vm passes SWIFT_SUPPRESS_WARNINGS=NO and
// SWIFT_TREAT_WARNINGS_AS_ERRORS=YES on the xcodebuild command line instead.
#if canImport(Darwin)
let strict: [SwiftSetting] = []
#else
let strict: [SwiftSetting] = [.treatAllWarnings(as: .error)]
#endif

var products: [Product] = [
    .library(name: "SevenKitCore", targets: ["SevenKitCore"]),
]
var targets: [Target] = [
    .target(name: "SevenKitCore", swiftSettings: strict),
    .testTarget(name: "SevenKitCoreTests", dependencies: ["SevenKitCore"], swiftSettings: strict),
]

#if canImport(Darwin)
products.append(.library(name: "SevenBackup", targets: ["SevenBackup"]))
products.append(.library(name: "SevenKit", targets: ["SevenKit"]))
products.append(.library(name: "SevenCompanion", targets: ["SevenCompanion"]))
targets += [
    .target(name: "SevenBackup", swiftSettings: strict),
    .testTarget(name: "SevenBackupTests", dependencies: ["SevenBackup"], swiftSettings: strict),
    .target(name: "SevenCompanion", dependencies: ["SevenKitCore"], swiftSettings: strict),
    .target(name: "SevenKit", dependencies: ["SevenKitCore"],
            resources: [.process("Resources")], swiftSettings: strict),
    .testTarget(name: "SevenKitTests", dependencies: ["SevenKit"], swiftSettings: strict),
]
#endif

let package = Package(
    name: "SevenKit",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14), .watchOS(.v10)],
    products: products,
    targets: targets
)

// swift-tools-version: 6.2
import PackageDescription
// CleanerPlatform's phone keys (PhoneNumberKit) build anywhere. CleanerCore is the Rust core: its Swift
// bindings in Sources/CleanerCore/Generated and the static library in
// Generated/cleaner_coreFFI.xcframework, both made by `scripts/rust-core cleaner apple`. It, and every
// CleanerPlatform service that calls it, is Apple-only.
var products: [Product] = [.library(name: "CleanerPlatform", targets: ["CleanerPlatform"])]
var platformDependencies: [Target.Dependency] = [.product(name: "PhoneNumberKit", package: "PhoneNumberKit")]
var targets: [Target] = [.testTarget(name: "CleanerPlatformTests", dependencies: ["CleanerPlatform"])]
#if canImport(Darwin)
products.insert(.library(name: "CleanerCore", targets: ["CleanerCore"]), at: 0)
platformDependencies.insert("CleanerCore", at: 0)
targets += [
    .binaryTarget(name: "cleaner_coreFFI", path: "Generated/cleaner_coreFFI.xcframework"),
    .target(name: "CleanerCore", dependencies: ["cleaner_coreFFI"]),
    .testTarget(name: "CleanerCoreTests", dependencies: ["CleanerCore"]),
]
#endif
targets.append(.target(name: "CleanerPlatform", dependencies: platformDependencies))
let package = Package(
    name: "CleanerKit", platforms: [.iOS(.v18), .macOS(.v15)],
    products: products,
    dependencies: [.package(url: "https://github.com/PhoneNumberKit/PhoneNumberKit", exact: "5.0.14")],
    targets: targets)

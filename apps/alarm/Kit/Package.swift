// swift-tools-version: 6.2
import PackageDescription
// AlarmStore (the archive file: atomic, publish-after-write) is plain Swift and builds anywhere.
// AlarmCore is the Rust core: its Swift bindings in Sources/AlarmCore/Generated and the static
// library in Generated/alarm_coreFFI.xcframework, both made by `scripts/rust-core alarm apple`.
var products: [Product] = [.library(name: "AlarmStore", targets: ["AlarmStore"])]
var dependencies: [Package.Dependency] = []
var targets: [Target] = [.target(name: "AlarmStore"), .testTarget(name: "AlarmStoreTests", dependencies: ["AlarmStore"])]
#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products += [.library(name: "AlarmCore", targets: ["AlarmCore"]), .library(name: "AlarmUI", targets: ["AlarmUI"])]
targets += [
    .binaryTarget(name: "alarm_coreFFI", path: "Generated/alarm_coreFFI.xcframework"),
    .target(name: "AlarmCore", dependencies: ["AlarmStore", "alarm_coreFFI"]),
    .testTarget(name: "AlarmCoreTests", dependencies: ["AlarmCore"]),
    .target(name: "AlarmUI", dependencies: ["AlarmCore", .product(name: "SevenKit", package: "SevenKit"), .product(name: "SevenSchedule", package: "SevenKit")], resources: [.process("Resources")]),
    .testTarget(name: "AlarmUITests", dependencies: ["AlarmUI"]),
]
#endif
let package = Package(name: "AlarmLoopsKit", defaultLocalization: "en", platforms: [.iOS("26.1")], products: products, dependencies: dependencies, targets: targets)

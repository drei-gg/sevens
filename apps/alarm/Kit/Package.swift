// swift-tools-version: 6.2
import PackageDescription
var products: [Product] = [.library(name: "AlarmCore", targets: ["AlarmCore"])]
var dependencies: [Package.Dependency] = []
var targets: [Target] = [.target(name: "AlarmCore"), .testTarget(name: "AlarmCoreTests", dependencies: ["AlarmCore"])]
#if canImport(Darwin)
dependencies.append(.package(path: "../../../SevenKit"))
products.append(.library(name: "AlarmUI", targets: ["AlarmUI"]))
targets += [.target(name: "AlarmUI", dependencies: ["AlarmCore", .product(name: "SevenKit", package: "SevenKit")], resources: [.process("Resources")]), .testTarget(name: "AlarmUITests", dependencies: ["AlarmUI"])]
#endif
let package = Package(name: "AlarmLoopsKit", defaultLocalization: "en", platforms: [.iOS("26.1")], products: products, dependencies: dependencies, targets: targets)

// swift-tools-version: 6.2
import PackageDescription

var products: [Product] = [.library(name: "BloomWire", targets: ["BloomWire"])]
var dependencies: [Package.Dependency] = []
var targets: [Target] = [
  .target(name: "BloomWire"), .testTarget(name: "BloomWireTests", dependencies: ["BloomWire"]),
]
#if canImport(Darwin)
  dependencies.append(.package(path: "../../../SevenKit"))
  products += [
    .library(name: "BloomCore", targets: ["BloomCore"]),
    .library(name: "BloomUI", targets: ["BloomUI"]),
    .library(name: "BloomSurfaces", targets: ["BloomSurfaces"]),
  ]
  targets += [
    .binaryTarget(name: "bloom_coreFFI", path: "Generated/bloom_coreFFI.xcframework"),
    .target(name: "BloomCore", dependencies: ["BloomWire", "bloom_coreFFI"]),
    .testTarget(name: "BloomCoreTests", dependencies: ["BloomCore"]),
    .testTarget(name: "BloomUITests", dependencies: ["BloomUI"]),
    .target(name: "BloomSurfaces", dependencies: ["BloomWire"]),
    .target(
      name: "BloomUI",
      dependencies: [
        "BloomCore", "BloomSurfaces", .product(name: "SevenKit", package: "SevenKit"),
        .product(name: "SevenCompanion", package: "SevenKit"),
        .product(name: "SevenSchedule", package: "SevenKit"),
      ]),
  ]
#endif
let package = Package(
  name: "BloomKit", platforms: [.iOS(.v17), .macOS(.v14), .watchOS(.v10)], products: products,
  dependencies: dependencies, targets: targets)

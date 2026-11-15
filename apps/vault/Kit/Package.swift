// swift-tools-version: 6.2
import PackageDescription
let package = Package(
    name: "VaultKit", platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "VaultCore", targets: ["VaultCore"]), .library(name: "VaultStorage", targets: ["VaultStorage"])],
    targets: [.target(name: "VaultCore"), .target(name: "VaultStorage", dependencies: ["VaultCore"]),
              .testTarget(name: "VaultCoreTests", dependencies: ["VaultCore"]),
              .testTarget(name: "VaultStorageTests", dependencies: ["VaultStorage", "VaultCore"])]
)

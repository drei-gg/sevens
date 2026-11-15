// swift-tools-version: 6.2
import PackageDescription
// VaultCore is the Rust core (apps/vault/core): its Swift bindings in Sources/VaultCore/Generated and
// the static library in Generated/vault_coreFFI.xcframework, both made by `scripts/rust-core vault
// apple`. VaultStorage (CryptoKit, AppleArchive, file protection) builds empty without CryptoKit, so
// on Linux only that shell remains; the core's own tests run there with `scripts/rust-core vault test`.
var products: [Product] = [.library(name: "VaultStorage", targets: ["VaultStorage"])]
var targets: [Target] = []
#if canImport(Darwin)
products.append(.library(name: "VaultCore", targets: ["VaultCore"]))
targets += [
    .binaryTarget(name: "vault_coreFFI", path: "Generated/vault_coreFFI.xcframework"),
    .target(name: "VaultCore", dependencies: ["vault_coreFFI"]),
    .target(name: "VaultStorage", dependencies: ["VaultCore"]),
    .testTarget(name: "VaultCoreTests", dependencies: ["VaultCore"]),
    .testTarget(name: "VaultStorageTests", dependencies: ["VaultStorage", "VaultCore"]),
]
#else
targets += [.target(name: "VaultStorage"), .testTarget(name: "VaultStorageTests", dependencies: ["VaultStorage"])]
#endif
let package = Package(name: "VaultKit", platforms: [.iOS(.v17), .macOS(.v13)], products: products, targets: targets)

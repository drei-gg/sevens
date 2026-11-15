// swift-tools-version: 6.2
import PackageDescription
let package = Package(
    name: "CleanerKit", platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "CleanerCore", targets: ["CleanerCore"]), .library(name: "CleanerPlatform", targets: ["CleanerPlatform"])],
    dependencies: [.package(url: "https://github.com/PhoneNumberKit/PhoneNumberKit", exact: "5.0.14")],
    targets: [
        .target(name: "CleanerCore"),
        .target(name: "CleanerPlatform", dependencies: ["CleanerCore", .product(name: "PhoneNumberKit", package: "PhoneNumberKit")]),
        .testTarget(name: "CleanerCoreTests", dependencies: ["CleanerCore"]),
        .testTarget(name: "CleanerPlatformTests", dependencies: ["CleanerPlatform"])
    ])

// swift-tools-version: 6.2
import PackageDescription
let package = Package(name: "ScannerKit", platforms: [.iOS(.v17)], products: [.library(name: "ScannerCore", targets: ["ScannerCore"])], targets: [.target(name: "ScannerCore"), .testTarget(name: "ScannerCoreTests", dependencies: ["ScannerCore"])])

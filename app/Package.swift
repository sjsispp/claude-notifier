// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ClaudeNotifier",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "ClaudeNotifier", targets: ["ClaudeNotifier"])
    ],
    dependencies: [
        .package(url: "https://github.com/httpswift/swifter.git", from: "1.5.0")
    ],
    targets: [
        .executableTarget(
            name: "ClaudeNotifier",
            dependencies: [.product(name: "Swifter", package: "swifter")]
        ),
        .testTarget(
            name: "ClaudeNotifierTests",
            dependencies: ["ClaudeNotifier"]
        )
    ]
)

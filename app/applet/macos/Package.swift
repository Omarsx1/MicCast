// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MicCastMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "MicCastMac", targets: ["MicCastMac"])
    ],
    dependencies: [],
    targets: [
        .executableTarget(
            name: "MicCastMac",
            path: "MicCast",
            exclude: ["Info.plist"]
        )
    ]
)

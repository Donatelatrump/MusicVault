// swift-tools-version: 5.9
// MusicVault for iOS — Swift Playgrounds App package.
// Open with Xcode 15+ (double-click Package.swift or the folder) or with
// Swift Playgrounds on iPad. No third-party dependencies.
import PackageDescription

let package = Package(
    name: "MusicVault",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .iOSApplication(
            name: "MusicVault",
            targets: ["AppModule"],
            displayName: "MusicVault",
            bundleIdentifier: "com.donnet.musicvault.ios",
            supportedDeviceFamilies: [
                .pad,
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeLeft,
                .landscapeRight
            ]
        ),
        // WidgetKit extension (home screen widget)
        .library(
            name: "MusicVaultWidget",
            targets: ["MusicVaultWidget"]
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "Sources/AppModule",
            resources: [
                .process("Resources")
            ]
        ),
        .target(
            name: "MusicVaultWidget",
            path: "Sources/MusicVaultWidget",
            dependencies: []
        ),
        .testTarget(
            name: "MusicVaultTests",
            dependencies: ["AppModule"]
        )
    ]
)
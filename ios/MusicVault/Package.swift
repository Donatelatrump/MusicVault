// swift-tools-version: 5.9
// MusicVault for iOS — Swift Playgrounds / Xcode App package.
// Open with Xcode 15+ (open Package.swift or folder) or with
// Swift Playgrounds on iPad/Mac. No third-party dependencies.
import PackageDescription

#if canImport(AppleProductTypes)
import AppleProductTypes
#endif

let package = Package(
    name: "MusicVault",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .iOSApplication(
            name: "MusicVault",
            targets: ["AppModule"],
            bundleIdentifier: "com.donnet.musicvault.ios",
            displayVersion: "0.5.0",
            bundleVersion: "1",
            appIcon: .placeholder(placeholder: .music),
            accentColor: .presetColor(.orange),
            supportedDeviceFamilies: [
                .pad,
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeLeft,
                .landscapeRight
            ],
            capabilities: [
                .backgroundModes(modes: [.audio])
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
            path: "MusicVault",
            exclude: [
                "Info.plist",
                "MusicVault.entitlements"
            ],
            resources: [
                .process("Assets.xcassets"),
                .process("Preview Content")
            ]
        ),
        .target(
            name: "MusicVaultWidget",
            path: "MusicVaultWidget",
            exclude: [
                "Info.plist"
            ],
            resources: [
                .process("Assets.xcassets")
            ]
        )
    ]
)

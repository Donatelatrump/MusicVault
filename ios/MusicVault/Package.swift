// swift-tools-version: 5.9
// MusicVault for iOS — Universal Swift Package & Playgrounds App manifest.
// Compatible with:
//  - Swift Playgrounds (iPad / Mac)
//  - Xcode 15+ (open Package.swift directly)
//  - Swift Package Manager CLI (swift build / xcodebuild)
import PackageDescription

#if canImport(AppleProductTypes)
import AppleProductTypes

let package = Package(
    name: "MusicVault",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .iOSApplication(
            name: "MusicVault",
            targets: ["MusicVault"],
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
        .library(
            name: "MusicVaultWidget",
            targets: ["MusicVaultWidget"]
        )
    ],
    targets: [
        .executableTarget(
            name: "MusicVault",
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
#else
let package = Package(
    name: "MusicVault",
    platforms: [
        .iOS("16.0"),
        .macOS("13.0")
    ],
    products: [
        .executable(
            name: "MusicVault",
            targets: ["MusicVault"]
        ),
        .library(
            name: "MusicVaultWidget",
            targets: ["MusicVaultWidget"]
        )
    ],
    targets: [
        .executableTarget(
            name: "MusicVault",
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
#endif

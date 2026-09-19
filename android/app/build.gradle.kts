plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.musicvault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicvault.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.5.8"

    }

    // Keep one practical universal phone APK without bundling desktop/emulator
    // x86 payloads from ffmpeg/youtube-dl. Gradle also emits the two smaller
    // per-ABI APKs, while app-universal-* works on ARM32 and ARM64 phones.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    
    // ✅ Configuraciones de build optimizadas
    buildTypes {
        release {
            isMinifyEnabled = true              // ✅ Minificación de código
            isShrinkResources = true           // ✅ Eliminación de recursos no usados  
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("performance") {
            initWith(getByName("release"))
            // Update the existing test app and retain its database/downloads.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-performance"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }


    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // Asegurar que las libs nativas no sean eliminadas por shrinking
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

composeCompiler {
    // Marks List/Set/Map as stable so lambdas/params holding them stay skippable.
    stabilityConfigurationFile.set(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    // Keep the runtime Kotlin libraries on the same line as the compiler/KSP.
    // This prevents a transitive dependency from silently pulling Kotlin 2.2.x
    // into a project still compiled with Kotlin 2.0.21.
    implementation(enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Baseline profile installer: ships the hand-authored startup profile in
    // app/src/main/baseline-prof.txt so ART can AOT-compile the cold-start path.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Media3 1.10.1 is intentionally pinned here. Media3 1.11.x is built
    // with Kotlin 2.2 metadata, while MusicVault currently uses Kotlin 2.0.21
    // + KSP 2.0.21. Keeping 1.10.1 preserves the streaming/cache/Cronet
    // optimizations without forcing a risky whole-project Kotlin/KSP migration.
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-datasource-cronet:$media3Version")
    implementation("androidx.media3:media3-database:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    // Preferred streaming network stack. Google Play Services Cronet gives
    // HTTP/2 + HTTP/3/QUIC without bundling the ~8 MB embedded Cronet binary.
    implementation("com.google.android.gms:play-services-cronet:18.1.1")

    // Native/JVM-first YouTube resolver. NewPipeExtractor handles the primary
    // stream URL extraction; yt-dlp remains a compatibility fallback.
    implementation("com.github.teamnewpipe:newpipeextractor:0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.3")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // youtube-dl android
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
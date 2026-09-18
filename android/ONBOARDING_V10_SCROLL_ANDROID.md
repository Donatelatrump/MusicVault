# MusicVault v10 — artist scrolling + Android compatibility

## Artist picker performance
- Replaced the LazyColumn-of-Rows pattern with a native `LazyVerticalGrid`.
- Each artist has its own stable key and reusable `contentType`.
- Artist cells now use a fixed 64dp height, which avoids repeated measuring during fast flings.
- Removed per-card color animations while scrolling/selection changes.
- The title/search controls stay outside the scrolling grid, so only the artist cells participate in a fling.
- Reduced card visual complexity while preserving the MusicVault visual language.

## Android compatibility
- `compileSdk`: 35 -> 36
- `targetSdk`: 35 -> 36
- Android Gradle Plugin: 8.7.3 -> 8.10.1
- Gradle wrapper: 9.3.0 -> 8.11.1 (officially compatible with AGP 8.10)
- Disabled Gradle configuration cache because this project previously hit a corrupted AGP service cache.

API 36 is the current required target for new/updated phone apps submitted to Google Play as of Aug 31, 2026, and avoids treating MusicVault as an old-target app on current Android versions without opting into Android 17/API 37 behavior changes yet.

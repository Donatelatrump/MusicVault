# Android release checks

Run from `android/`. Local signing uses the existing development key only when explicitly requested via the property below.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:verifyReleaseDownloadRules --no-daemon
```

Build the optimized, locally signed APK and its device tests:

```sh
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:verifyReleaseDownloadRules -Pmusicvault.testBuildType=release -Pmusicvault.localReleaseSigning=true --no-daemon
```

On a personal device, install the matching ABI release and test APK with `adb install --no-incremental -r` (or `adb push` followed by `adb shell pm install -r` on slow wireless links). Never uninstall the app to resolve an install error: investigate signing/version compatibility first. Run `adb shell am instrument -w com.musicvault.app.test/androidx.test.runner.AndroidJUnitRunner`. This avoids Gradle connected-test install/uninstall lifecycle handling on a user's device.

The device tests verify ZIP reflection, execute embedded Python/yt-dlp, and exercise an isolated database upgrade from v18 to v19 plus cancellation and persistent queue records. They do not erase the user's database. For a live download and media-read check, add `-e musicvault.smokeUrl YOUR_PERMITTED_YOUTUBE_URL` before the instrumentation component. Without this argument the live download test is explicitly skipped.

Before distribution, also check the actual UI and OS behavior:

1. Update an existing install and verify playlists, favorites and downloaded files remain playable.
2. Enqueue a playlist, leave the app, then restart the device. Pending entries should return and run when constraints are met. Android force-stop requires reopening the app.
3. Use Wi-Fi-only and switch to cellular during a download; the request must wait/retry rather than finish using cellular. On Android 8, Wi-Fi must also be unmetered.
4. Download a track in best quality after streaming it; this must use a fresh download rather than promote the lower-quality streaming cache.
5. Export, import twice, and confirm that playlist entries do not duplicate and existing downloaded tracks remain downloaded. Corrupt/future backup versions must be rejected before database writes.
6. Change the cache limit and clear streaming cache with playback paused. Downloaded files must remain present.

Queue execution uses WorkManager's foreground-worker support; Android job quotas and force-stop rules still apply. See [Android's foreground-worker guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

# Android roadmap and release implementation plan

> Execution: independent Android domains in parallel, integration by the main agent. User explicitly requests the entire roadmap, release build and ADB installation without executing tests or benchmarks.

**Goal:** Implement the prioritized Android roadmap, produce a locally signed release APK and install over the existing installation at 192.168.1.13:45345.

**Architecture:** Preserve Room/library data and the existing Media3 session while introducing focused coordinators/screens and a simple AppContainer. Feature services own their background work and portable settings. Existing local edits are preserved.

**Spec:** android/docs/MEJORAS_ANDROID_PRIORIZADAS.md

## Constraints

- No test, lint, instrumentation, benchmark or post-install smoke commands. Build compilation remains necessary.
- No uninstall, clear data, destructive migration, unsolicited telemetry or external publication.
- Keep minSdk26, ARM universal APK and existing local signing compatibility.
- Owner decides license; source changes may proceed while that answer is pending.
- External integrations expose opt-in setup and report missing credentials; never invent credentials or results.

## Tasks

- [ ] Data/library: export schemas 19/20, unicode61 migration, MigrationTestHelper cases (not executed), backup exclusions, M3U, storage management, scheduled optional encrypted SAF backup with progress, automatic playlist/favorite download on Wi-Fi and charging.
- [ ] Playback: durable complete queue, speed/silence settings, gapless path, normalization, MediaLibraryService/Auto, Cast, Wear media-session control, manual lyric offset/editor.
- [ ] Provider reliability: existing updater visible/manual, classified errors, safe cancellation, download tags/artwork and promotion consistency, timeout handling, private exportable logs, scrobbling and song radio/related artists.
- [ ] Integration: AppContainer and focused search/library/download/playback coordinators, extracted screens, restored navigation, accessibility, offline mode, localized strings and reactive portable preferences.
- [ ] Quality infrastructure: CI definitions, Macrobenchmark module, JankStats and debug LeakCanary; no execution of their tests.
- [ ] Documentation: accurate implementation/limitations and dependency upgrade matrix.
- [ ] Release: compile assembleRelease with local signing, address compiler/build errors only, connect ADB and install -r with no further tests.

## Review focus

Preserve metadata/audioUri across migrations; prevent stale queue completion; do not restore session ciphertext without Keystore; account for URI grants revocation; propagate cancellation; no duplicate playback sessions; avoid leaking tokens/URLs in diagnostics.

# MusicVault library reliability implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve downloaded metadata, recover downloads, expose quality/storage/Wi-Fi controls, export/import library metadata, and validate optimized releases.

**Architecture:** Room stores download requests and complete track snapshots. WorkManager runs durable downloads and owns recovery/retries. Separate download, backup, and settings components keep new behavior out of the large screen and ViewModel files.

**Tech Stack:** Kotlin, Room, WorkManager, Compose, Media3, yt-dlp.

**Spec:** The user-approved review immediately preceding this plan: six prioritized improvements plus incremental modularization.

## Global constraints

- Preserve existing tracks, downloads, playlists, favorites, settings, and uncommitted R8 fix.
- Keep release optimization enabled and the existing Android API 26 minimum.
- Spanish UI. No credentials, absolute local audio paths, or audio bytes in portable backups.
- Import merges records; it never erases the existing library or marks foreign files downloaded.
- Best quality downloads bypass the streaming cache; data-saving downloads may reuse it.
- Wi-Fi-only jobs never intentionally transfer download bytes over cellular.

## Review focus

- Process death/reboot between queue persistence and scheduling: reconcile pending rows when app opens.
- Cancellation racing completion: only the current request generation can update its queue row.
- Existing track metadata: merge in a transaction and keep richer existing fields.
- Malformed backup: validate the whole bounded document before any database writes.
- Existing audio/cache readers: clear cache through Media3 APIs, never delete library files.

## Tasks

- [x] 1. Persistent queue and metadata: add Room v19 migration, DownloadQueue/DownloadWorker, generation-aware state transitions and recovery; adapt ViewModel requests and whole-playlist enqueueing. Verify metadata merge with unit tests and compile.
- [x] 2. Quality/network settings: snapshot quality per request, best bypasses cache, enforce Wi-Fi/network constraints and bounded retries. Verify quality/cache decisions with tests.
- [x] 3. Storage/queue UI: focused Compose screens, observable queue, retry/cancel, adjustable cache cap, usage totals and cache-only clear. Verify compile and cache eviction behavior.
- [x] 4. Portable backup: versioned JSON validation, transactional merge of playlists/favorites/tracks and allowlisted preferences, SAF picker UI. Verify malformed/round-trip/foreign download tests.
- [x] 5. Release checks: regression coverage for reflection constructors and optimized APK instrumentation smoke tests; document real-device download and upgrade checks.
- [x] 6. Build, review, and handoff: unit tests, lint, optimized build; sign local APK; install when current ADB endpoint is reachable; report device checks honestly.

## Execution ledger

- Ruling: implement in the existing shared workspace, preserving prior edits; user explicitly authorized execution, so no additional plan approval or commits are required.
- Ruling: use WorkManager for persistent execution rather than trying to restart a foreground service from boot.
- Ruling: split new concerns into focused components and extract settings; avoid a broad rewrite of unrelated playback screens.
- Independent review found and resolved three integration regressions: native transfer cancellation must happen inside the cancelled await before joining its child; bulk enqueue must skip existing downloaded audio; offline lyrics must be saved by the worker rather than activity callbacks.
- Verification: 24 unit tests passed; lint reported 0 errors, 121 warnings and 2 hints; optimized release and instrumented-test APKs built; ZIP constructor retention check passed. `git diff --check` passed.
- Device: updated WDY-LX3 at 192.168.1.12:45231 from 0.5.8/code 19 to 0.5.9/code 20 using `pm install -r`. Cold launch succeeded. Existing playlists appeared; settings displayed quality/Wi-Fi/cache controls, 71.4 MB downloaded audio and 20.9 MB cache. No library deletion, cache clearing or preference changes performed.
- Limitations: instrumented execution did not return results on this device and is not counted as passed. Real download, Wi-Fi-to-cellular switching, reboot recovery and SAF backup round-trip remain manual device checks; unit tests do not establish these OS-level behaviors.

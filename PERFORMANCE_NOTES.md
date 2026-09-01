# MusicVault 0.5.1 - scroll performance pass

This build applies a deeper Jetpack Compose performance pass focused on search-result scrolling.

## Changes

- Compose BOM remains **2024.12.01** (Compose 1.7.x). A planned bump to the 2025.11.01 line was *not* applied in this build.
- Enabled Compose stability configuration for Kotlin `List`, `Set`, and `Map`.
- Marked immutable UI/catalog models with `@Immutable` so Compose can skip unchanged rows more aggressively.
- Search screen no longer receives the entire `MusicUiState`; it receives only the search data it needs.
- Search is a native **RecyclerView** (`RemoteResultsAdapter`), not a Compose `LazyColumn`; row virtualization and caching come from RecyclerView (`setItemViewCacheSize`, recycled-view pools, prefetch). A `LazyLayoutCacheWindow` therefore does **not** apply here.
- Kept stable keys and explicit `contentType` values for albums/tracks.
- Reduced thumbnail decode target to 56px for 50–52dp result thumbnails.
- Reduced cover memory cache from 20% to 15% to reduce GC pressure on lower-memory devices.
- Album result rows no longer draw a gradient underneath a fully opaque cover.
- Album titles are single-line/ellipsized to keep row measurement stable.
- Search debounce remains exactly 1.5 seconds.
- Download progress remains visible; it is observed only by the search screen rather than the application root.

## Validation

The archive was checked for ZIP integrity and Kotlin source files were checked for balanced braces/parentheses and package/import ordering.

A full Gradle build could not be executed in this environment because the Gradle 9.3.0 distribution and Android dependencies are not cached locally and external Gradle downloads are unavailable here.

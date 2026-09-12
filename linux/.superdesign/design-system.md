# MusicVault — Android design system and redesign brief

## Product

MusicVault is an offline-first Android music library and downloader. It searches songs/albums, accepts Spotify track or playlist URLs in the same search field, detects valid Spotify links from the clipboard, imports playlist covers and tracks, downloads complete playlists, organizes local albums/playlists, and provides a full player with lyrics and queue.

## Identity: The Vault

Preserve the established warm archival identity. The app should feel like a meticulously catalogued private record vault: tactile, calm, technical, premium, never flashy. Avoid generic streaming-service neon, glassmorphism, black gradients, excessive pills, and oversized empty hero areas.

## Color tokens

- `background`: #121110
- `surface-base`: #171514
- `surface-1`: #1E1B18
- `surface-2`: #292420
- `surface-3`: #363028
- `text-primary`: #F1EAD9
- `text-secondary`: #B3A992
- `text-tertiary`: #7E7666
- `divider`: #322C25
- `accent-brass`: #E0A458
- `accent-pressed`: #B97F35
- `accent-soft`: #3A2E1D
- `on-accent`: #191207
- `error`: #D96A55
- `favorite`: #C96A5E
- `repeat`: #7DC4E8

Use flat surfaces and 1 px warm hairlines. Brass is reserved for the primary action, active navigation, playback progress and high-confidence status. Do not invent other colors.

## Typography

- Display: Space Grotesk, 600–700; screen titles 24–30 px, section titles 19–20 px.
- Body: Inter, 400–600; primary rows 14–15 px, metadata 12–13 px.
- Technical labels: IBM Plex Mono, 500; 9–12 px uppercase with 0.6–0.9 px tracking.
- Use sentence case for actions and titles; mono uppercase only for navigation and compact metadata.

## Layout and components

- Android viewport: 412 × 915 logical px with safe status/navigation areas.
- Horizontal screen gutter: 20 px. Base spacing unit: 4 px.
- Touch targets: minimum 44 × 44 px; primary buttons 48–52 px high.
- Cards: 10–14 px radius, subtle 1 px divider, little or no shadow.
- Artwork: square, 8–12 px radius. Prefer clear cover prominence and stable aspect ratios.
- Search field: one field only. It accepts titles/artists/albums or Spotify URLs; never add a separate URL section.
- Clipboard notice: compact but visually distinct, clear source/kind, `Importar` as the single primary action, dismiss secondary.
- Playlist detail: cover, title and track count first; play/shuffle as a balanced action group; full-width download-all action with visible downloaded/downloading state.
- Bottom navigation: four stable destinations — Inicio, Buscar, Biblioteca, Álbumes. Active state must be obvious without relying only on color.
- Mini-player sits above navigation and remains compact, with continuous progress and unambiguous play/pause.

## Motion

Preserve motion and performance: 0.96 press scale with restrained spring, 140–260 ms fade/slide transitions, 500 ms linear progress interpolation. Animate opacity/transform rather than layout where possible. No ambient looping motion except playback visualization.

## Redesign goals

1. Strengthen hierarchy between screen title, sections, card metadata and secondary controls.
2. Standardize vertical rhythm, radii, icon containers and button heights.
3. Make Spotify-link detection and playlist import feel native to the same search workflow.
4. Make download states legible without clutter.
5. Improve one-handed reach, accessibility contrast and touch targets.
6. Keep dense music browsing fast; avoid decorative elements that reduce visible content.

Use ONLY the fonts, colors, spacing, and component styles defined here. Do not introduce any fonts, colors, gradients, glass effects, or visual styles not in this design system.

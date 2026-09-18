# Search v7 fix

- Song search no longer races Deezer against yt-dlp for ordinary music queries.
  A good Deezer result is returned immediately; YouTube is only used as a fallback.
- This fixes the case where albums appeared quickly but songs timed out and ended in
  "No encontramos canciones" even though Deezer had already answered.
- Merged track search also stops after a healthy primary-provider page instead of
  blocking on secondary enrichment.
- Search song rows now have a fixed 84dp height and a fixed 48dp action column, so
  Play/Pause buttons form a straight aligned column regardless of metadata length.
- The optional description is limited to one line in search rows to preserve that
  deterministic row height.

# Audio quality

Streaming and downloads independently offer Saver, Best and Beta. Best selects the highest available bitrate from the normal extractor; Saver prefers an available stream up to 128 kbps (or the lightest available when none qualifies). Download files are fetched independently rather than promoted from streaming cache. Existing downloaded files are not replaced or upsampled.

Beta searches Audius using the public API, without credentials, with a five-second resolution budget before falling back to the normal Best path. Only exact normalized title and artist matches with downloadable, available originals and no access gates are eligible. Originals must be FLAC/WAV/AIFF; the actual response header is checked (WAV must identify PCM or IEEE float) before selecting the URL. This verifies the file format, not the recording's provenance or subjective fidelity. Public APIs can rate-limit or become unavailable.

Streaming caches are separated by quality. Beta playback uses a source URL hash so it never mixes bytes from different formats/providers. Beta does not use the normal synthetic offline-cache URI or speculative prefix download; offline playback of explicitly downloaded files is unaffected.

The existing tagger copies FLAC/WAV/AIFF/Opus/WebM audio rather than converting it to AAC. Artwork remains in the app library for those formats.

Validation: matching, gates, permissions, original availability and audio magic bytes are unit tested. Anonymous Audius search returned HTTP 200 and an eligible original returned HTTP 206 with PCM 16-bit stereo 48 kHz WAV audio in the live check. Full Android playback of that original is not yet device-tested.

API reference: https://api.audius.co/v1/swagger.yaml

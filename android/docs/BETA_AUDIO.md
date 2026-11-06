# Beta audio (0.6.17)

Select Beta independently under Streaming quality or Downloads. Enter a Jamendo client ID or an HTTPS OpenSubsonic server URL and API key in the Beta section. The server must implement apiKeyAuthentication. Credentials are excluded from portable settings and library backup exports.

Resolution tries the configured OpenSubsonic library first (FLAC/WAV/AIFF, original download or format=raw streaming), then Jamendo (FLAC). Both title and artist must match after case/diacritic/punctuation normalization. Jamendo downloads require audiodownload_allowed=true. No match, unavailable provider or missing credentials falls back to the normal source at maximum available quality. Beta is not a guarantee of lossless availability for every song. Server configuration and upstream master quality still determine the actual audio.

Beta playback does not reuse the previous track-keyed streaming bytes. Its cache key includes a hash of the resolved URL, preventing mixed bytes across source/format changes. Download quality is independent of streaming and does not promote streaming cache. FLAC/WAV/AIFF/Opus/WebM tagging copies audio without re-encoding; cover artwork for these formats remains in the library rather than embedded in the file.

Validation: unit fixtures cover source matching, original-stream/download request parameters, download permission rejection, missing credentials and provider failure. Real authenticated-provider playback requires user-supplied credentials and remains to be tested. A host FFmpeg smoke check confirmed identical decoded PCM hashes before/after FLAC metadata copying; this does not replace Android device testing.

Qobuz/TIDAL are not included: developer access and authenticated playback were not established. Bandcamp purchases can be imported as local lossless files through the existing library flow.

References:
- https://developer.jamendo.com/v3.0/tracks
- https://developer.jamendo.com/v3.0/tracks/file
- https://opensubsonic.netlify.app/docs/extensions/apikeyauth/
- https://opensubsonic.netlify.app/docs/endpoints/stream/

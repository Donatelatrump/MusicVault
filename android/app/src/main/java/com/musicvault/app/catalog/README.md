# Catalog module

`MusicBrainzCatalogProvider` es la fuente de metadata remota.

No descarga audio.

## API pública

```kotlin
interface CatalogProvider {
    suspend fun searchTracks(query: String, limit: Int = 20): List<CatalogTrack>
    suspend fun searchArtists(query: String, limit: Int = 10): List<CatalogArtist>
    suspend fun searchAlbums(query: String, limit: Int = 10): List<CatalogAlbum>
}
```

## Fuente actual

MusicBrainz Web Service:

```text
https://musicbrainz.org/ws/2/
```

No se usa API key.

Se envía:

```text
User-Agent: MusicVault/0.3.0 (personal-local-app)
```

y el cliente espera entre peticiones para respetar el límite de MusicBrainz.

## Sustituir proveedor

Puedes crear otro:

```text
SpotifyCatalogProvider.kt
DeezerCatalogProvider.kt
LocalJsonCatalogProvider.kt
```

sin cambiar la UI, porque todos implementan `CatalogProvider`.

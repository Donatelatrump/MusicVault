package com.musicvault.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.musicvault.app.R
import com.musicvault.app.catalog.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistBrowserDialog(initialQuery: String, onDismiss: () -> Unit,
    onPlay: (CatalogTrack) -> Unit, onDownload: (CatalogTrack) -> Unit) {
    val provider = remember { DeezerCatalogProvider() }
    var query by remember { mutableStateOf(initialQuery) }
    var trackQuery by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(initialQuery) }
    var artists by remember { mutableStateOf(emptyList<CatalogArtist>()) }
    var artist by remember { mutableStateOf<CatalogArtist?>(null) }
    var albums by remember { mutableStateOf(emptyList<CatalogAlbum>()) }
    var album by remember { mutableStateOf<CatalogAlbum?>(null) }
    var tracks by remember { mutableStateOf(emptyList<CatalogTrack>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(submitted, artist?.id, album?.id, trackQuery) {
        loading = true; error = false; tracks = emptyList()
        if (album == null) albums = emptyList()
        try {
            val selected = artist
            when {
                selected == null -> { artists = if (submitted.isBlank()) emptyList() else provider.searchArtists(submitted, 20); albums = emptyList() }
                album != null -> tracks = provider.albumTracks(album!!.id, 100)
                trackQuery.isNotBlank() -> { kotlinx.coroutines.delay(300); tracks = provider.artistTracks(selected, trackQuery.trim()); more = false }
                else -> { tracks = provider.artistTopTracks(selected.id); albums = provider.artistAlbums(selected.id).map { it.copy(artistName = selected.name) }; offset = albums.size; more = albums.size == 50 }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 20.dp)) {
            Row {
                if (artist != null) TextButton(onClick = { if (album != null) album = null else artist = null }) { Text(stringResource(R.string.jams_back)) }
                Text(album?.title ?: artist?.name ?: stringResource(R.string.artist_profiles), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            artist?.portraitUri?.let { portrait -> coil.compose.AsyncImage(portrait, artist?.name, modifier = Modifier.fillMaxWidth().height(160.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            if (artist != null && album == null) OutlinedTextField(trackQuery, { trackQuery = it }, label = { Text(stringResource(R.string.artist_song_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (artist == null) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.artist_profiles)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { submitted = query.trim() }) { Text(stringResource(R.string.artist_search)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) TextButton(onClick = { submitted += " " }) { Text(stringResource(R.string.artist_retry)) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (artist == null) items(artists, key = { it.id }) { item ->
                    ListItem(headlineContent = { Text(item.name) }, modifier = Modifier.clickable { trackQuery = ""; artist = item })
                } else {
                    items(tracks, key = { it.id }) { track ->
                        ListItem(headlineContent = { Text(track.title) }, supportingContent = { Text(track.artistName) },
                            modifier = Modifier.clickable { onPlay(track) }, trailingContent = {
                                TextButton(onClick = { onDownload(track) }) { Text(stringResource(R.string.artist_download)) }
                            })
                    }
                    if (album == null) {
                        items(albums, key = { it.id }) { item -> ListItem(headlineContent = { Text(item.title) },
                            supportingContent = { Text(item.year?.toString().orEmpty()) }, modifier = Modifier.clickable { album = item }) }
                        if (more) item {
                            TextButton(enabled = !loading, onClick = { scope.launch {
                                val selected = artist ?: return@launch
                                loading = true
                                try { val page = provider.artistAlbums(selected.id, offset).map { it.copy(artistName = selected.name) }
                                    if (artist?.id == selected.id) { albums = (albums + page).distinctBy { it.id }; offset += page.size; more = page.size == 50 }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = true }
                                finally { loading = false }
                            } }) { Text(stringResource(R.string.artist_more)) }
                        }
                    }
                }
                if (!loading && !error && artists.isEmpty() && artist == null && submitted.isNotBlank()) item { Text(stringResource(R.string.artist_empty)) }
            }
        }
    }
}

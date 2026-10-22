package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.musicvault.app.catalog.CatalogArtist
import com.musicvault.app.data.Track
import kotlinx.coroutines.CancellationException

@Composable
internal fun RelatedArtistsDialog(track: Track, vm: MusicVaultViewModel, onClose: () -> Unit) {
    var artists by remember(track.id) { mutableStateOf<List<CatalogArtist>>(emptyList()) }
    var loading by remember(track.id) { mutableStateOf(true) }
    LaunchedEffect(track.id) {
        try { artists = vm.relatedArtists(track) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { artists = emptyList() }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ui_artistas_relacionados_8ac47b)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (loading) CircularProgressIndicator()
            else if (artists.isEmpty()) Text(stringResource(R.string.ui_no_hay_artistas_relacionados_disponibles_compr_5bd400))
            artists.forEach { artist ->
                TextButton(onClick = {
                    vm.closeFullPlayer()
                    vm.selectSection(AppSection.SEARCH)
                    vm.submitSearch(artist.name)
                    onClose()
                }) { Text(artist.name) }
            }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ui_cerrar_4b0816)) } }
    )
}

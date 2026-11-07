package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musicvault.app.catalog.CatalogArtist
import com.musicvault.app.data.Track
import com.musicvault.app.ui.theme.VaultColors
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
        title = {
            Text(
                stringResource(R.string.ui_artistas_relacionados_8ac47b),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = VaultColors.TextPrimary
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VaultColors.Accent, modifier = Modifier.size(32.dp))
                    }
                } else if (artists.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_no_hay_artistas_relacionados_disponibles_compr_5bd400),
                        color = VaultColors.TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    artists.forEach { artist ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.closeFullPlayer()
                                    vm.selectSection(AppSection.SEARCH)
                                    vm.submitSearch(artist.name)
                                    onClose()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(VaultColors.Surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!artist.portraitUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = artist.portraitUri,
                                        contentDescription = artist.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        artist.name.take(1).uppercase(),
                                        color = VaultColors.Accent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    artist.name,
                                    color = VaultColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!artist.disambiguation.isNullOrBlank()) {
                                    Text(
                                        artist.disambiguation,
                                        color = VaultColors.TextTertiary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.ui_cerrar_4b0816), color = VaultColors.Accent)
            }
        },
        containerColor = VaultColors.Surface1,
        shape = RoundedCornerShape(20.dp)
    )
}

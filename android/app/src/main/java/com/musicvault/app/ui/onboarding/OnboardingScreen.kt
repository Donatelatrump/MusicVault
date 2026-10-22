package com.musicvault.app.ui.onboarding

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicvault.app.ui.theme.VaultColors
import com.musicvault.app.ui.theme.VaultFonts
import com.musicvault.app.ui.theme.VaultIcons
import kotlinx.coroutines.delay

private enum class OnboardingStep { GENRES, ARTISTS }

private val genreOptions = listOf(
    "Pop", "Rock", "Alternativo", "Indie",
    "Hip-hop", "Rap en español", "Trap", "R&B",
    "Reggaetón", "Corridos tumbados", "Regional mexicano", "Pop latino",
    "Cumbia", "Salsa / Bachata", "EDM", "House",
    "Techno", "Metal", "Punk", "Funk / Disco",
    "K-pop", "J-pop", "Jazz / Soul", "Clásica",
    "Lo-fi / Ambient", "Soundtracks"
)

/**
 * Curated, intentionally local list. It gives the onboarding an immediate,
 * reliable set of recognizable artists without delaying first launch on a
 * network request. The chosen names are later fed into the real catalog search
 * so Home recommendations are still generated from live/cached MusicVault data.
 */
private val artistsByGenre = mapOf(
    "Pop" to listOf("Taylor Swift", "The Weeknd", "Billie Eilish", "Dua Lipa", "Ariana Grande", "Bruno Mars", "Lady Gaga", "Sabrina Carpenter"),
    "Rock" to listOf("Linkin Park", "Foo Fighters", "Green Day", "Red Hot Chili Peppers", "Queen", "Nirvana", "The Killers", "Måneskin"),
    "Alternativo" to listOf("Arctic Monkeys", "Radiohead", "Tame Impala", "The Neighbourhood", "Twenty One Pilots", "Paramore", "Gorillaz", "Muse"),
    "Indie" to listOf("Arctic Monkeys", "Tame Impala", "Lana Del Rey", "Mac DeMarco", "Cigarettes After Sex", "The Strokes", "Clairo", "Vampire Weekend"),
    "Hip-hop" to listOf("Kendrick Lamar", "Travis Scott", "Drake", "Tyler, The Creator", "Eminem", "J. Cole", "Kanye West", "21 Savage"),
    "Rap en español" to listOf("Duki", "Trueno", "Wos", "Canserbero", "Santa Fe Klan", "Alemán", "C. Tangana", "Residente"),
    "Trap" to listOf("Travis Scott", "Future", "Metro Boomin", "Playboi Carti", "Duki", "Anuel AA", "Eladio Carrión", "Bad Bunny"),
    "R&B" to listOf("The Weeknd", "SZA", "Frank Ocean", "Brent Faiyaz", "Giveon", "Daniel Caesar", "Summer Walker", "Miguel"),
    "Reggaetón" to listOf("Bad Bunny", "Feid", "Rauw Alejandro", "Karol G", "J Balvin", "Myke Towers", "Young Miko", "Daddy Yankee"),
    "Corridos tumbados" to listOf("Peso Pluma", "Natanael Cano", "Junior H", "Fuerza Regida", "Tito Double P", "Luis R Conriquez", "Gabito Ballesteros", "Óscar Maydon"),
    "Regional mexicano" to listOf("Carín León", "Christian Nodal", "Grupo Firme", "Banda MS", "Julión Álvarez", "Edén Muñoz", "Calibre 50", "La Arrolladora"),
    "Pop latino" to listOf("Shakira", "Morat", "Sebastián Yatra", "Camilo", "Luis Miguel", "ROSALÍA", "Juanes", "Carlos Vives"),
    "Cumbia" to listOf("Los Ángeles Azules", "Selena", "Grupo Cañaveral", "Los Askis", "Sonora Dinamita", "Raymix", "Celso Piña", "Kumbia Kings"),
    "Salsa / Bachata" to listOf("Romeo Santos", "Aventura", "Marc Anthony", "Prince Royce", "Víctor Manuelle", "Héctor Lavoe", "Juan Luis Guerra", "Frankie Ruiz"),
    "EDM" to listOf("David Guetta", "Calvin Harris", "Avicii", "Martin Garrix", "Tiësto", "Marshmello", "Zedd", "Swedish House Mafia"),
    "House" to listOf("Fred again..", "Disclosure", "Peggy Gou", "FISHER", "Dom Dolla", "Purple Disco Machine", "Kaytranada", "Jamie xx"),
    "Techno" to listOf("Charlotte de Witte", "Amelie Lens", "Carl Cox", "Adam Beyer", "Nina Kraviz", "Boris Brejcha", "Anyma", "Paul Kalkbrenner"),
    "Metal" to listOf("Metallica", "System Of A Down", "Slipknot", "Deftones", "Bring Me The Horizon", "Rammstein", "Avenged Sevenfold", "Iron Maiden"),
    "Punk" to listOf("blink-182", "Green Day", "The Offspring", "Sum 41", "Paramore", "Simple Plan", "Sex Pistols", "Bad Religion"),
    "Funk / Disco" to listOf("Daft Punk", "Michael Jackson", "Earth, Wind & Fire", "Jamiroquai", "Chic", "Parcels", "Bee Gees", "Stevie Wonder"),
    "K-pop" to listOf("BTS", "BLACKPINK", "Stray Kids", "TWICE", "aespa", "NewJeans", "SEVENTEEN", "LE SSERAFIM"),
    "J-pop" to listOf("YOASOBI", "Ado", "Kenshi Yonezu", "Fujii Kaze", "LiSA", "Vaundy", "King Gnu", "Mrs. GREEN APPLE"),
    "Jazz / Soul" to listOf("Laufey", "Amy Winehouse", "Norah Jones", "Stevie Wonder", "Etta James", "Marvin Gaye", "Miles Davis", "Nina Simone"),
    "Clásica" to listOf("Ludovico Einaudi", "Frédéric Chopin", "Ludwig van Beethoven", "Antonio Vivaldi", "Max Richter", "Yo-Yo Ma", "Claude Debussy", "Wolfgang A. Mozart"),
    "Lo-fi / Ambient" to listOf("Nujabes", "Tycho", "Bonobo", "idealism", "Ólafur Arnalds", "Boards of Canada", "Emancipator", "Jinsang"),
    "Soundtracks" to listOf("Hans Zimmer", "Joe Hisaishi", "John Williams", "Ramin Djawadi", "Ludwig Göransson", "Hiroyuki Sawano", "Howard Shore", "Michael Giacchino")
)

private fun suggestedArtists(genres: Set<String>): List<String> {
    if (genres.isEmpty()) return emptyList()
    val score = linkedMapOf<String, Int>()
    genres.forEach { genre ->
        artistsByGenre[genre].orEmpty().forEachIndexed { index, artist ->
            // Artists that occur in more than one selected genre rise to the top;
            // earlier names inside a genre also get a small popularity priority.
            score[artist] = (score[artist] ?: 0) + 100 - index * 3
        }
    }
    return score.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
        .map { it.key }
        .take(36)
}

@Composable
fun OnboardingScreen(
    onCompleto: (generos: Set<String>, artistas: Set<String>) -> Unit,
    onSkip: () -> Unit
) {
    var step by remember { mutableStateOf(OnboardingStep.GENRES) }
    var generos by remember { mutableStateOf(emptySet<String>()) }
    var artistas by remember { mutableStateOf(emptySet<String>()) }

    // Build the next screen's artist list while the user is still on Genres.
    // The catalog is 100% local, so advancing to Artists never waits on network.
    val preparedArtistSuggestions = remember(generos) { suggestedArtists(generos) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState == OnboardingStep.ARTISTS) {
                    (slideInHorizontally(tween(210, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(130)))
                }
            },
            label = "onboardingStep"
        ) { target ->
            when (target) {
                OnboardingStep.GENRES -> GenreSelectionPage(
                    selected = generos,
                    onToggle = { genre ->
                        generos = if (genre in generos) generos - genre else generos + genre
                    },
                    onContinue = { step = OnboardingStep.ARTISTS },
                    onSkip = onSkip
                )

                OnboardingStep.ARTISTS -> ArtistSelectionPage(
                    genres = generos,
                    suggestions = preparedArtistSuggestions,
                    selected = artistas,
                    onToggle = { artist ->
                        artistas = if (artist in artistas) artistas - artist else artistas + artist
                    },
                    onBack = { step = OnboardingStep.GENRES },
                    onContinue = { onCompleto(generos, artistas) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreSelectionPage(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 116.dp)
        ) {
            OnboardingTopBar(step = 1)
            Spacer(Modifier.height(34.dp))
            Text(
                text = "¿Qué suena en tu bóveda?",
                color = VaultColors.TextPrimary,
                fontFamily = VaultFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 31.sp,
                lineHeight = 35.sp,
                letterSpacing = (-0.8).sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Elige uno o varios géneros. Mezcla sin miedo: esto solo afina tu inicio.",
                color = VaultColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(26.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                genreOptions.forEach { genre ->
                    GenreChip(
                        genre = genre,
                        selected = genre in selected,
                        onClick = { onToggle(genre) }
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    stringResource(R.string.ui_prefiero_configurarlo_despues_fc6294),
                    color = VaultColors.TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        OnboardingBottomAction(
            selectedCount = selected.size,
            enabled = selected.isNotEmpty(),
            buttonText = "Elegir artistas",
            helper = if (selected.isEmpty()) "Selecciona al menos un género" else "${selected.size} ${if (selected.size == 1) "género" else "géneros"} seleccionados",
            onClick = onContinue
        )
    }
}

@Composable
private fun ArtistSelectionPage(
    genres: Set<String>,
    suggestions: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    var artistInput by remember { mutableStateOf("") }

    fun addCustomArtist() {
        val clean = artistInput.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotBlank()) {
            if (clean !in selected) onToggle(clean)
            artistInput = ""
        }
    }

    // Keep the expensive set/list work outside individual grid cells. The grid
    // itself receives stable artist keys and only composes what is visible.
    val customArtists = remember(selected, suggestions) {
        selected.filterNot { it in suggestions }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ui_volver_a_generos_5bf2d6),
                        tint = VaultColors.TextPrimary
                    )
                }
                Spacer(Modifier.width(5.dp))
                OnboardingTopBar(step = 2, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "¿A quién sí escuchas?",
                color = VaultColors.TextPrimary,
                fontFamily = VaultFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 29.sp,
                lineHeight = 33.sp,
                letterSpacing = (-0.7).sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Elige tus favoritos de ${genres.take(3).joinToString(", ")}${if (genres.size > 3) " y más" else ""}.",
                color = VaultColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = artistInput,
                onValueChange = { artistInput = it.take(48) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.ui_buscar_o_anadir_otro_artista_2d29df)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addCustomArtist() }),
                trailingIcon = {
                    if (artistInput.isNotBlank()) {
                        TextButton(onClick = { addCustomArtist() }) {
                            Text(stringResource(R.string.ui_anadir_41261d), color = VaultColors.Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = VaultColors.TextPrimary,
                    unfocusedTextColor = VaultColors.TextPrimary,
                    focusedBorderColor = VaultColors.Accent,
                    unfocusedBorderColor = VaultColors.Divider,
                    cursorColor = VaultColors.Accent,
                    focusedContainerColor = VaultColors.SurfaceBase,
                    unfocusedContainerColor = VaultColors.SurfaceBase
                )
            )

            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "POPULARES PARA TI",
                    modifier = Modifier.weight(1f),
                    color = VaultColors.TextTertiary,
                    fontFamily = VaultFonts.Mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${suggestions.size} artistas",
                    color = VaultColors.TextTertiary,
                    fontFamily = VaultFonts.Mono,
                    fontSize = 9.sp
                )
            }
            Spacer(Modifier.height(8.dp))

            // A real lazy grid is substantially cheaper during fast flings than
            // a scrolling list whose items each create an additional Row with two
            // composables. Fixed-height cells also let Compose reuse slots with
            // almost no remeasurement while scrolling.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 122.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = suggestions,
                    key = { "suggestion:$it" },
                    contentType = { "artist" }
                ) { artist ->
                    ArtistCard(
                        artist = artist,
                        selected = artist in selected,
                        onClick = { onToggle(artist) }
                    )
                }

                if (customArtists.isNotEmpty()) {
                    item(
                        key = "custom-artists-title",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "section"
                    ) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "AÑADIDOS POR TI",
                                color = VaultColors.TextTertiary,
                                fontFamily = VaultFonts.Mono,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    items(
                        items = customArtists,
                        key = { "custom:$it" },
                        contentType = { "artist" }
                    ) { artist ->
                        ArtistCard(
                            artist = artist,
                            selected = true,
                            onClick = { onToggle(artist) }
                        )
                    }
                }
            }
        }

        OnboardingBottomAction(
            selectedCount = selected.size,
            enabled = true,
            buttonText = if (selected.isEmpty()) "Continuar sin artistas" else "Preparar mi MusicVault",
            helper = if (selected.isEmpty()) "Puedes continuar y usar solo tus géneros" else "${selected.size} ${if (selected.size == 1) "artista" else "artistas"} seleccionados",
            onClick = onContinue
        )
    }
}

@Composable
private fun OnboardingTopBar(step: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VaultColors.AccentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                VaultIcons.LibraryMusic,
                contentDescription = null,
                tint = VaultColors.Accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ui_musicvault_87c6d6),
                color = VaultColors.TextPrimary,
                fontFamily = VaultFonts.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
            )
            Text(
                stringResource(R.string.ui_personalizacion_inicial_886231),
                color = VaultColors.TextTertiary,
                fontFamily = VaultFonts.Mono,
                fontSize = 8.sp,
                letterSpacing = 0.8.sp
            )
        }
        Text(
            text = "0$step / 02",
            color = VaultColors.Accent,
            fontFamily = VaultFonts.Mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GenreChip(
    genre: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) VaultColors.AccentSoft else VaultColors.Surface1,
        label = "genreBg"
    )
    val border by animateColorAsState(
        if (selected) VaultColors.Accent.copy(alpha = 0.72f) else VaultColors.Divider,
        label = "genreBorder"
    )

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Text(stringResource(R.string.ui_label_698a87), color = VaultColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                genre,
                color = if (selected) VaultColors.TextPrimary else VaultColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ArtistCard(
    artist: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = remember(artist) {
        VaultColors.SpineAccents[(artist.hashCode() and Int.MAX_VALUE) % VaultColors.SpineAccents.size]
    }
    val initials = remember(artist) {
        artist.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1).uppercase() }
            .take(2)
    }
    val cardShape = RoundedCornerShape(15.dp)
    val cardColor = if (selected) VaultColors.AccentSoft else VaultColors.Surface1
    val borderColor = if (selected) VaultColors.Accent.copy(alpha = 0.74f) else VaultColors.Divider

    // Deliberately no animateColorAsState here. There can be dozens of artist
    // cells and selection animations on each cell add work to a fast fling.
    // A direct state swap keeps interaction instant and scrolling stable.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick),
        color = cardColor,
        shape = cardShape,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (selected) 0.26f else 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initials,
                    color = accent,
                    fontFamily = VaultFonts.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = artist,
                modifier = Modifier.weight(1f),
                color = VaultColors.TextPrimary,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(VaultColors.Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.ui_label_698a87), color = VaultColors.OnAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.OnboardingBottomAction(
    selectedCount: Int,
    enabled: Boolean,
    buttonText: String,
    helper: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        color = VaultColors.Background.copy(alpha = 0.98f),
        border = BorderStroke(0.5.dp, VaultColors.Divider.copy(alpha = 0.75f))
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    helper,
                    modifier = Modifier.weight(1f),
                    color = if (selectedCount > 0) VaultColors.Accent else VaultColors.TextTertiary,
                    fontFamily = VaultFonts.Mono,
                    fontSize = 9.sp,
                    letterSpacing = 0.25.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultColors.Accent,
                    contentColor = VaultColors.OnAccent,
                    disabledContainerColor = VaultColors.Surface2,
                    disabledContentColor = VaultColors.TextTertiary
                )
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Full-screen handoff shown after the last onboarding choice. MusicVaultApp keeps
 * the real Home screen mounted underneath this surface while preferences trigger
 * catalog/recommendation work, then fades this overlay away once Home is ready.
 */
@Composable
fun RecommendationLoadingScreen(
    genres: Set<String>,
    artists: Set<String>
) {
    val messages = remember(genres, artists) {
        buildList {
            add("Leyendo tus gustos")
            if (artists.isNotEmpty()) add("Conectando tus artistas favoritos")
            add("Buscando canciones que encajen contigo")
            add("Ordenando tu bóveda")
            add("Afinando tus recomendaciones")
        }
    }
    var messageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        while (true) {
            delay(900)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }

    val infinite = rememberInfiniteTransition(label = "onboardingLoading")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "loadingRotation"
    )
    val counterRotation by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "loadingCounterRotation"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                Canvas(Modifier.size(176.dp).rotate(rotation)) {
                    drawArc(
                        color = VaultColors.Accent.copy(alpha = 0.95f),
                        startAngle = 12f,
                        sweepAngle = 118f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = VaultColors.Accent.copy(alpha = 0.18f),
                        startAngle = 170f,
                        sweepAngle = 92f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Canvas(Modifier.size(132.dp).rotate(counterRotation)) {
                    drawArc(
                        color = VaultColors.TextSecondary.copy(alpha = 0.42f),
                        startAngle = 40f,
                        sweepAngle = 150f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Box(
                    modifier = Modifier
                        .size((76 * pulse).dp)
                        .clip(CircleShape)
                        .background(VaultColors.AccentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        VaultIcons.LibraryMusic,
                        contentDescription = null,
                        tint = VaultColors.Accent,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.ui_preparando_tu_boveda_6ed08a),
                color = VaultColors.Accent,
                fontFamily = VaultFonts.Mono,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = messages[messageIndex],
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(220)) },
                label = "recommendationMessage"
            ) { message ->
                Text(
                    text = message,
                    color = VaultColors.TextPrimary,
                    fontFamily = VaultFonts.Display,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    artists.isNotEmpty() -> "Usando ${genres.size} ${if (genres.size == 1) "género" else "géneros"} y ${artists.size} ${if (artists.size == 1) "artista" else "artistas"} para construir tu inicio."
                    genres.isNotEmpty() -> "Usando tus ${genres.size} ${if (genres.size == 1) "género" else "géneros"} para construir tu inicio."
                    else -> "Preparando MusicVault para ti."
                },
                color = VaultColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            EqualizerDots()
        }
    }
}

@Composable
private fun EqualizerDots() {
    val infinite = rememberInfiniteTransition(label = "loadingBars")
    val phases = listOf(0, 120, 240, 360, 480)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        phases.forEachIndexed { index, delayMs ->
            val h by infinite.animateFloat(
                initialValue = 5f,
                targetValue = 20f,
                animationSpec = infiniteRepeatable(
                    animation = tween(620, delayMillis = delayMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (index == 2) VaultColors.Accent else VaultColors.TextTertiary)
            )
        }
    }
}

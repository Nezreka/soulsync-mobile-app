package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.CatalogMovie
import net.ssync.player.CatalogShow
import net.ssync.player.MovieDetail
import net.ssync.player.MusicApiResult
import net.ssync.player.PersonDetail
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerNote
import net.ssync.player.ShowDetail
import net.ssync.player.TitleExtras

@Composable
fun MovieDetailScreen(
    movieId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MovieDetail?>(null) }
    var extras by remember { mutableStateOf<TitleExtras?>(null) }
    var notes by remember { mutableStateOf<List<PlayerNote>>(emptyList()) }
    var noteDraft by remember { mutableStateOf("") }
    var inWatchlist by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(movieId, api) {
        isLoading = true
        when (val res = api.fetchMovieDetail(movieId)) {
            is MusicApiResult.Success -> detail = res.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchWatchlist() as? MusicApiResult.Success)?.let { list ->
            inWatchlist = list.data.any { it.itemType == "movie" && it.itemId == movieId.toString() }
        }
        (api.fetchTitleExtras("movie", movieId) as? MusicApiResult.Success)?.let { extras = it.data }
        (api.fetchNotes("movie", movieId.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
        isLoading = false
    }

    val movie = detail
    if (isLoading) {
        LoadingBox()
        return
    }
    if (movie == null) {
        EmptyBox("Couldn't load movie details.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            HeroSpotlight(
                title = movie.title,
                subtitle = listOfNotNull(
                    movie.year?.toString(),
                    movie.contentRating,
                    movie.runtimeMinutes?.let { "${it}m" },
                    movie.rating?.let { "★ %.1f".format(it) },
                ).joinToString("  •  "),
                backdropUrl = api.artwork.movieBackdrop(movie.id),
                inWatchlist = inWatchlist,
                onPlay = {
                    coordinator.playVideo(
                        ActivePlayable(
                            itemType = "movie",
                            itemId = movie.id.toString(),
                            title = movie.title,
                            subtitle = movie.year?.toString().orEmpty(),
                            artworkUrl = api.artwork.moviePoster(movie.id),
                            streamUrl = api.streamUrl("movie", movie.id.toString()),
                            isVideo = true,
                        ),
                    )
                },
                onToggleWatchlist = {
                    scope.launch {
                        if (inWatchlist) {
                            api.removeFromWatchlist("movie", movie.id.toString())
                        } else {
                            api.addToWatchlist("movie", movie.id.toString())
                        }
                        inWatchlist = !inWatchlist
                    }
                },
            )
        }
        if (!movie.overview.isNullOrBlank()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (!movie.tagline.isNullOrBlank()) {
                        Text(
                            text = movie.tagline.orEmpty(),
                            color = SoulSyncColors.AccentLight,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(text = movie.overview.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (movie.cast.isNotEmpty()) {
            item {
                MediaRail(title = "Cast") {
                    items(movie.cast.take(15)) { member ->
                        SquareMediaCard(
                            title = member.name,
                            subtitle = member.character,
                            artworkUrl = api.artwork.resolve(member.photo),
                            fallbackIcon = Icons.Default.Person,
                            width = 104.dp,
                            onClick = {
                                member.tmdbId?.let { id ->
                                    onNavigate(PlayerDestination.PersonDetail(id, member.name))
                                }
                            },
                        )
                    }
                }
            }
        }
        val similar = (extras?.recommendations.orEmpty() + extras?.similar.orEmpty()).distinctBy { it.tmdbId }.take(12)
        if (similar.isNotEmpty()) {
            item {
                MediaRail(title = "More Like This") {
                    items(similar, key = { "${it.kind}-${it.tmdbId}" }) { rec ->
                        PosterCard(
                            title = rec.title,
                            artworkUrl = api.artwork.resolve(rec.poster),
                            onClick = {
                                rec.libraryId?.let { libId ->
                                    if (rec.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                                    else onNavigate(PlayerDestination.ShowDetail(libId))
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            NotesSection(
                notes = notes,
                draft = noteDraft,
                onDraftChange = { noteDraft = it },
                onAdd = {
                    val body = noteDraft.trim()
                    if (body.isNotEmpty()) {
                        noteDraft = ""
                        scope.launch {
                            api.addNote("movie", movie.id.toString(), body)
                            (api.fetchNotes("movie", movie.id.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
                        }
                    }
                },
                onDelete = { noteId ->
                    scope.launch {
                        api.deleteNote(noteId)
                        notes = notes.filterNot { it.id == noteId }
                    }
                },
            )
        }
    }
}

@Composable
private fun NotesSection(
    notes: List<PlayerNote>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Personal Notes", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Add a note...") },
                singleLine = true,
                modifier = Modifier.weight(1f).dpadFocusable(),
            )
            IconButton(onClick = onAdd, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.Add, contentDescription = "Save note", tint = SoulSyncColors.AccentLight)
            }
        }
        notes.forEach { n ->
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDelete(n.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PersonDetailScreen(
    tmdbId: Int,
    fallbackName: String,
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var person by remember { mutableStateOf<PersonDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(tmdbId, api) {
        isLoading = true
        when (val r = api.fetchPerson(tmdbId)) {
            is MusicApiResult.Success -> person = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    val p = person
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoulSyncArtwork(
                    url = api.artwork.resolve(p?.photo),
                    contentDescription = p?.name ?: fallbackName,
                    fallbackIcon = Icons.Default.Person,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p?.name ?: fallbackName, style = MaterialTheme.typography.headlineMedium)
                    p?.placeOfBirth?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (!p?.biography.isNullOrBlank()) {
            item { Text(p?.biography.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
        }
        val credits = p?.credits.orEmpty()
        if (credits.isNotEmpty()) {
            item { SectionHeader("Filmography") }
            items(credits.take(30)) { c ->
                SimpleTrackRow(
                    title = c.title,
                    subtitle = listOfNotNull(c.role, if (c.libraryId != null) "In Library" else null).joinToString(" • "),
                    artworkUrl = api.artwork.resolve(c.poster),
                    onClick = {
                        c.libraryId?.let { libId ->
                            if (c.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                            else onNavigate(PlayerDestination.ShowDetail(libId))
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun ShowDetailScreen(
    showId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var detail by remember { mutableStateOf<ShowDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(showId, api) {
        isLoading = true
        when (val res = api.fetchShowDetail(showId)) {
            is MusicApiResult.Success -> detail = res.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    val show = detail
    if (isLoading) {
        LoadingBox()
        return
    }
    if (show == null) {
        EmptyBox("Couldn't load show details.")
        return
    }

    val seasons = show.seasons
    val activeSeason = seasons.getOrNull(selectedSeasonIndex)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            val firstEp = activeSeason?.episodes?.firstOrNull { it.owned }
            HeroSpotlight(
                title = show.title,
                subtitle = listOfNotNull(show.year?.toString(), show.network, show.contentRating)
                    .joinToString("  •  "),
                backdropUrl = api.artwork.showBackdrop(show.id),
                onPlay = {
                    if (firstEp != null) {
                        coordinator.playVideo(
                            ActivePlayable(
                                itemType = "episode",
                                itemId = firstEp.id.toString(),
                                title = firstEp.title ?: "Episode ${firstEp.episodeNumber}",
                                subtitle = "${show.title} • S${activeSeason.seasonNumber}E${firstEp.episodeNumber}",
                                artworkUrl = api.artwork.episodeStill(firstEp.id),
                                streamUrl = api.streamUrl("episode", firstEp.id.toString()),
                                isVideo = true,
                            ),
                        )
                    }
                },
            )
        }

        if (!show.overview.isNullOrBlank()) {
            item {
                Text(
                    text = show.overview.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (seasons.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seasons.indices.toList()) { idx ->
                        val s = seasons[idx]
                        FilterChip(
                            selected = idx == selectedSeasonIndex,
                            onClick = { selectedSeasonIndex = idx },
                            label = { Text(s.title ?: "Season ${s.seasonNumber}") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SoulSyncColors.Accent,
                                selectedLabelColor = SoulSyncColors.OnAccent,
                                containerColor = SoulSyncColors.Card,
                                labelColor = SoulSyncColors.TextSoft,
                            ),
                            modifier = Modifier.dpadFocusable(),
                        )
                    }
                }
            }
        }

        if (activeSeason != null) {
            items(activeSeason.episodes, key = { it.id }) { ep ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .dpadFocusable(RoundedCornerShape(12.dp))
                        .clickable(enabled = ep.owned) {
                            coordinator.playVideo(
                                ActivePlayable(
                                    itemType = "episode",
                                    itemId = ep.id.toString(),
                                    title = ep.title ?: "Episode ${ep.episodeNumber}",
                                    subtitle = "${show.title} • S${activeSeason.seasonNumber}E${ep.episodeNumber}",
                                    artworkUrl = api.artwork.episodeStill(ep.id),
                                    streamUrl = api.streamUrl("episode", ep.id.toString()),
                                    isVideo = true,
                                ),
                            )
                        },
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SoulSyncArtwork(
                            url = api.artwork.episodeStill(ep.id),
                            contentDescription = ep.title,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "E${ep.episodeNumber} • ${ep.title ?: "Episode ${ep.episodeNumber}"}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!ep.overview.isNullOrBlank()) {
                                Text(
                                    text = ep.overview.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (ep.owned) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = SoulSyncColors.AccentLight,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

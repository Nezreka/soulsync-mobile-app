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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
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
import net.ssync.player.VideoRequestModes
import net.ssync.player.VideoRequestResult

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

    var pendingRequest by remember { mutableStateOf<UnownedTitleTarget?>(null) }

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
        val similar = (extras?.recommendations.orEmpty() + extras?.similar.orEmpty()).distinctBy { it.tmdbId }.take(16)
        if (similar.isNotEmpty()) {
            item {
                MediaRail(title = "More Like This") {
                    items(similar, key = { "${it.kind}-${it.tmdbId}" }) { rec ->
                        PosterCard(
                            title = rec.title,
                            subtitle = if (rec.libraryId != null) {
                                rec.yearText ?: "In Library"
                            } else {
                                listOfNotNull(rec.yearText, "Request").joinToString(" • ")
                            },
                            artworkUrl = api.artwork.resolve(rec.poster),
                            onClick = {
                                val libId = rec.libraryId
                                if (libId != null) {
                                    if (rec.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                                    else onNavigate(PlayerDestination.ShowDetail(libId))
                                } else if (rec.tmdbId > 0) {
                                    pendingRequest = UnownedTitleTarget(
                                        kind = if (rec.kind == "show" || rec.kind == "tv") "show" else "movie",
                                        tmdbId = rec.tmdbId,
                                        title = rec.title,
                                        year = rec.yearText,
                                        subtitle = if (rec.kind == "show" || rec.kind == "tv") "TV Show" else "Movie",
                                        posterUrl = api.artwork.resolve(rec.poster),
                                    )
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

    if (pendingRequest != null) {
        UnownedTitleRequestSheet(
            target = pendingRequest!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { pendingRequest = null },
        )
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
    var filter by remember { mutableStateOf("all") } // "all" | "owned" | "acting" | "crew"
    var pendingRequest by remember { mutableStateOf<UnownedTitleTarget?>(null) }

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
    val allCredits = remember(p) {
        p?.credits.orEmpty()
            .distinctBy { "${it.kind}:${it.tmdbId}:${it.role.orEmpty()}" }
            .sortedByDescending { it.yearText?.toIntOrNull() ?: 0 }
    }
    val ownedCredits = remember(allCredits) { allCredits.filter { it.libraryId != null } }
    val actingCredits = remember(allCredits) {
        allCredits.filter { (it.department ?: "Acting").equals("Acting", ignoreCase = true) }
    }
    val crewCredits = remember(allCredits) {
        allCredits.filter { !(it.department ?: "Acting").equals("Acting", ignoreCase = true) }
    }
    val visibleCredits = when (filter) {
        "owned" -> ownedCredits
        "acting" -> actingCredits
        "crew" -> crewCredits
        else -> allCredits
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 74.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoulSyncArtwork(
                    url = api.artwork.resolve(p?.photo),
                    contentDescription = p?.name ?: fallbackName,
                    fallbackIcon = Icons.Default.Person,
                    modifier = Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(p?.name ?: fallbackName, style = MaterialTheme.typography.headlineMedium)
                    val meta = listOfNotNull(
                        p?.placeOfBirth,
                        "${allCredits.size} credits",
                        if (ownedCredits.isNotEmpty()) "${ownedCredits.size} in library" else null,
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.bodyMedium, color = SoulSyncColors.TextSoft)
                    }
                }
            }
        }
        if (!p?.biography.isNullOrBlank()) {
            item { Text(p?.biography.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
        }
        if (allCredits.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Filmography (${allCredits.size})")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = buildList {
                            add("all" to "All (${allCredits.size})")
                            if (ownedCredits.isNotEmpty()) add("owned" to "In Library (${ownedCredits.size})")
                            if (actingCredits.isNotEmpty() && crewCredits.isNotEmpty()) {
                                add("acting" to "Acting (${actingCredits.size})")
                                add("crew" to "Crew (${crewCredits.size})")
                            }
                        }
                        items(chips, key = { it.first }) { (key, label) ->
                            FilterChip(
                                selected = filter == key,
                                onClick = { filter = key },
                                label = { Text(label) },
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
            items(
                items = visibleCredits,
                key = { "${it.kind}-${it.tmdbId}-${it.role.orEmpty()}-${it.yearText.orEmpty()}" },
            ) { c ->
                val isOwned = c.libraryId != null
                SimpleTrackRow(
                    title = c.title,
                    subtitle = listOfNotNull(
                        c.yearText,
                        c.role?.takeIf { it.isNotBlank() },
                        if (isOwned) "In Library" else "Not in library • Tap to request",
                    ).joinToString(" • "),
                    artworkUrl = api.artwork.resolve(c.poster),
                    trailingBadge = if (isOwned) "Owned" else "Request",
                    onClick = {
                        val libId = c.libraryId
                        if (libId != null) {
                            if (c.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                            else onNavigate(PlayerDestination.ShowDetail(libId))
                        } else if (c.tmdbId > 0) {
                            pendingRequest = UnownedTitleTarget(
                                kind = if (c.kind == "show" || c.kind == "tv") "show" else "movie",
                                tmdbId = c.tmdbId,
                                title = c.title,
                                year = c.yearText,
                                subtitle = c.role?.takeIf { it.isNotBlank() }
                                    ?: if (c.kind == "show" || c.kind == "tv") "TV Show" else "Movie",
                                posterUrl = api.artwork.resolve(c.poster),
                            )
                        }
                    },
                )
            }
        }
    }

    if (pendingRequest != null) {
        UnownedTitleRequestSheet(
            target = pendingRequest!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { pendingRequest = null },
        )
    }
}

private data class UnownedTitleTarget(
    val kind: String, // "movie" | "show"
    val tmdbId: Int,
    val title: String,
    val year: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnownedTitleRequestSheet(
    target: UnownedTitleTarget,
    api: PlayerApiClient,
    onDeviceRevoked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var modes by remember { mutableStateOf<VideoRequestModes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VideoRequestResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target.tmdbId, api) {
        when (val r = api.fetchVideoRequestOptions()) {
            is MusicApiResult.Success -> modes = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> modes = VideoRequestModes(movie = "ask", show = "ask")
        }
    }

    val mode = if (target.kind == "show") modes?.show ?: "ask" else modes?.movie ?: "ask"
    val canDownload = mode == "download"
    val canRequest = mode != "none"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SoulSyncColors.Surface,
        contentColor = SoulSyncColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SoulSyncArtwork(
                    url = target.posterUrl,
                    contentDescription = target.title,
                    fallbackIcon = Icons.Default.Movie,
                    modifier = Modifier
                        .width(74.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = target.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        if (target.kind == "show") "TV Show" else "Movie",
                        target.year,
                        target.subtitle?.takeIf { it != "Movie" && it != "TV Show" },
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(meta, color = SoulSyncColors.TextSoft, fontSize = 13.sp)
                    }
                    Text(
                        text = "Not in your SoulSync library yet",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            val doneMessage = when (val r = result) {
                VideoRequestResult.Got -> "Added to your wishlist! SoulSync will grab it and it will appear here."
                is VideoRequestResult.Asked -> if (r.already) {
                    "Already requested — waiting for approval."
                } else {
                    "Requested! Sent to whoever approves requests on this server."
                }
                VideoRequestResult.OnTheWay -> "Already on the way — SoulSync is grabbing it."
                VideoRequestResult.Owned -> "Already in your library!"
                is VideoRequestResult.Error -> null
                null -> null
            }

            if (doneMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = SoulSyncColors.AccentLight)
                    Text(doneMessage, color = SoulSyncColors.AccentLight, fontSize = 13.sp)
                }
            } else if (!canRequest) {
                Text(
                    text = "This profile does not have permission to request ${if (target.kind == "show") "TV shows" else "movies"}.",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 13.sp,
                )
            } else {
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        errorMsg = null
                        scope.launch {
                            when (val r = api.requestVideoTitle(
                                kind = target.kind,
                                tmdbId = target.tmdbId,
                                title = target.title,
                                year = target.year,
                                posterUrl = target.posterUrl,
                                get = canDownload,
                            )) {
                                is MusicApiResult.Success -> {
                                    result = r.data
                                    if (r.data is VideoRequestResult.Error) {
                                        errorMsg = (r.data as VideoRequestResult.Error).message
                                    }
                                }
                                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                                is MusicApiResult.Error -> errorMsg = r.message
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().dpadFocusable(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = SoulSyncColors.OnAccent,
                        )
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            busy -> "Submitting…"
                            canDownload -> "Add to Wishlist"
                            else -> "Request ${if (target.kind == "show") "Show" else "Movie"}"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (errorMsg != null) {
                Text(errorMsg!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
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

    var pendingRequest by remember { mutableStateOf<UnownedTitleTarget?>(null) }

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

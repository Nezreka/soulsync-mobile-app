package net.ssync.app.player

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
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
    var issueTarget by remember { mutableStateOf<ReportIssueTarget?>(null) }
    val directors = remember(movie.crew) { movie.crew.filter { it.job == "Director" } }
    val discoveryRails = rememberRelatedDiscoveryRails(
        kind = "movie",
        selfId = movie.id,
        cast = movie.cast,
        leadCrew = directors,
        studioOrNetwork = movie.studio,
        franchiseCollection = extras?.collection,
        api = api,
    )

    if (LocalIsTv.current) {
        TvCinemaMovieDetail(
            movie = movie,
            extras = extras,
            discoveryRails = discoveryRails,
            inWatchlist = inWatchlist,
            api = api,
            coordinator = coordinator,
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
                    if (inWatchlist) api.removeFromWatchlist("movie", movie.id.toString())
                    else api.addToWatchlist("movie", movie.id.toString())
                    inWatchlist = !inWatchlist
                }
            },
            onStartWatchParty = {
                onNavigate(
                    PlayerDestination.StartWatchParty(
                        itemType = "movie",
                        itemId = movie.id.toString(),
                        title = movie.title,
                        artwork = api.artwork.moviePoster(movie.id),
                        backdrop = api.artwork.movieBackdrop(movie.id),
                    ),
                )
            },
            onReportIssue = {
                issueTarget = ReportIssueTarget(
                    entityType = "movie",
                    entityId = movie.id,
                    title = movie.title,
                )
            },
            onNavigate = onNavigate,
            onRequestUnowned = { extra ->
                pendingRequest = UnownedTitleTarget(
                    kind = "movie",
                    tmdbId = extra.tmdbId,
                    title = extra.title,
                    year = extra.yearText,
                    posterUrl = api.artwork.resolve(extra.poster),
                )
            },
        )
    } else {
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
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    OutlinedButton(
                        onClick = {
                            onNavigate(
                                PlayerDestination.StartWatchParty(
                                    itemType = "movie",
                                    itemId = movie.id.toString(),
                                    title = movie.title,
                                    artwork = "/api/video/poster/movie/${movie.id}",
                                    backdrop = "/api/video/backdrop/movie/${movie.id}",
                                ),
                            )
                        },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SoulSyncColors.Accent.copy(alpha = 0.14f),
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp).dpadFocusable(CircleShape),
                    ) {
                        Text("🍿 Watch Party", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    OfflineDownloadButton(
                        api = api,
                        itemType = "movie",
                        itemId = movie.id.toString(),
                        title = movie.title,
                        subtitle = movie.year?.toString() ?: "",
                        artworkUrl = api.artwork.moviePoster(movie.id),
                        streamUrl = api.streamUrl("movie", movie.id.toString()),
                        isVideo = true,
                    )
                }
                item {
                    ReportIssuePillButton(
                        onClick = {
                            issueTarget = ReportIssueTarget(
                                entityType = "movie",
                                entityId = movie.id,
                                title = movie.title,
                            )
                        },
                    )
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
        relatedDiscoveryRails(
            rails = discoveryRails,
            onNavigate = onNavigate,
            onRequestUnowned = { card ->
                if (card.tmdbId > 0) {
                    pendingRequest = UnownedTitleTarget(
                        kind = card.kind,
                        tmdbId = card.tmdbId,
                        title = card.title,
                        year = card.subtitle,
                        subtitle = if (card.kind == "show") "TV Show" else "Movie",
                        posterUrl = card.artworkUrl,
                    )
                }
            },
        )
        val similar = (extras?.recommendations.orEmpty() + extras?.similar.orEmpty()).distinctBy { it.tmdbId }.take(16)
        if (similar.isNotEmpty()) {
            item {
                MediaRail(title = "More Like This") {
                    items(similar, key = { "${it.kind}-${it.tmdbId}" }) { rec ->
                        val isOwned = rec.libraryId != null
                        CreditPosterCard(
                            title = rec.title,
                            subtitle = rec.yearText ?: if (rec.kind == "show" || rec.kind == "tv") "TV Show" else "Movie",
                            artworkUrl = api.artwork.resolve(rec.poster),
                            isOwned = isOwned,
                            width = 126.dp,
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
    }

    if (pendingRequest != null) {
        UnownedTitleRequestSheet(
            target = pendingRequest!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { pendingRequest = null },
        )
    }
    if (issueTarget != null) {
        ReportIssueSheet(
            target = issueTarget!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { issueTarget = null },
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
    val isTv = LocalIsTv.current
    var tvEditingNote by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Personal Notes", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (isTv && !tvEditingNote) {
            OutlinedButton(
                onClick = { tvEditingNote = true },
                shape = CircleShape,
                modifier = Modifier.dpadFocusable(CircleShape),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add a note", color = Color.White, fontSize = 13.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Add a note...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).dpadFocusable(),
                )
                IconButton(
                    onClick = {
                        onAdd()
                        tvEditingNote = false
                    },
                    modifier = Modifier.dpadFocusable(CircleShape),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Save note", tint = SoulSyncColors.AccentLight)
                }
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
                    IconButton(onClick = { onDelete(n.id) }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditPosterCard(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    isOwned: Boolean,
    width: Dp? = null,
    onClick: () -> Unit,
) {
    val containerMod = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .dpadFocusable(RoundedCornerShape(12.dp))
    val borderColor = if (isOwned) SoulSyncColors.Accent.copy(alpha = 0.65f) else SoulSyncColors.Line

    Column(modifier = containerMod) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(SoulSyncColors.Card)
                .border(if (isOwned) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp)),
        ) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = title,
                fallbackIcon = Icons.Default.Movie,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isOwned) 1f else 0.58f),
            )
            // Subtle bottom vignette for crisp badge legibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                        ),
                    ),
            )
            // Always-visible top-right status badge: emerald green ✓ for owned, amber + for not in library
            LibraryCornerBadge(
                inLibrary = isOwned,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = if (isOwned) Color.White else SoulSyncColors.TextSoft,
            fontSize = 12.sp,
            fontWeight = if (isOwned) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = SoulSyncColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilmographyListCard(
    title: String,
    year: String?,
    role: String?,
    kindLabel: String,
    artworkUrl: String?,
    isOwned: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .dpadFocusable(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = if (isOwned) SoulSyncColors.Card else SoulSyncColors.Card.copy(alpha = 0.62f),
        border = BorderStroke(
            width = if (isOwned) 1.2.dp else 1.dp,
            color = if (isOwned) SoulSyncColors.Accent.copy(alpha = 0.55f) else SoulSyncColors.Line,
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SoulSyncColors.Surface),
            ) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.Movie,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isOwned) 1f else 0.58f),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    color = if (isOwned) Color.White else SoulSyncColors.TextSoft,
                    fontSize = 15.sp,
                    fontWeight = if (isOwned) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(year, kindLabel, role?.takeIf { it.isNotBlank() }).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isOwned) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(SoulSyncColors.Accent.copy(alpha = 0.18f))
                        .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = SoulSyncColors.AccentLight,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Owned",
                        color = SoulSyncColors.AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = SoulSyncColors.TextSoft,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Request",
                        color = SoulSyncColors.TextSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
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
    var gridView by remember { mutableStateOf(true) }
    val isTv = LocalIsTv.current
    var bioExpanded by remember { mutableStateOf(false) }
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
    val rawCredits = remember(p) {
        p?.credits.orEmpty()
            .distinctBy { "${it.kind}:${it.tmdbId}:${it.role.orEmpty()}" }
    }
    val knownForCredits = remember(rawCredits) { rawCredits.take(12) }
    val allCredits = remember(rawCredits) {
        rawCredits.sortedByDescending { it.yearText?.toIntOrNull() ?: 0 }
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
    val years = remember(allCredits) {
        allCredits.mapNotNull { it.yearText?.toIntOrNull() }.filter { it > 1800 }
    }
    val activeSpan = if (years.isNotEmpty()) "${years.min()}–${years.max()}" else null

    fun onCreditTap(c: net.ssync.player.PersonCredit) {
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
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 74.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cinema Person Hero Header
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SoulSyncArtwork(
                        url = api.artwork.resolve(p?.photo),
                        contentDescription = p?.name ?: fallbackName,
                        fallbackIcon = Icons.Default.Person,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .border(2.dp, SoulSyncColors.LineStrong, CircleShape),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = p?.name ?: fallbackName,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.4).sp,
                        )
                        val meta = listOfNotNull(activeSpan, "${allCredits.size} credits", p?.placeOfBirth)
                            .joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(meta, color = SoulSyncColors.TextSoft, fontSize = 13.sp, maxLines = 2)
                        }
                        if (ownedCredits.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(SoulSyncColors.Accent.copy(alpha = 0.16f))
                                    .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = SoulSyncColors.AccentLight,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "${ownedCredits.size} in your library",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                if (!p?.biography.isNullOrBlank()) {
                    val bio = p?.biography.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SoulSyncColors.Card)
                            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
                            .clickable { bioExpanded = !bioExpanded }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = bio,
                            color = SoulSyncColors.TextSoft,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bio.length > 160) {
                            Text(
                                text = if (bioExpanded) "Show less" else "Read more",
                                color = SoulSyncColors.AccentLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Known For rail (matches desktop person.$personId.tsx)
        if (knownForCredits.isNotEmpty()) {
            item {
                MediaRail(title = "Known For") {
                    items(
                        items = knownForCredits,
                        key = { "kf-${it.kind}-${it.tmdbId}-${it.role.orEmpty()}" },
                    ) { c ->
                        CreditPosterCard(
                            title = c.title,
                            subtitle = listOfNotNull(c.yearText, c.role?.takeIf { it.isNotBlank() }).joinToString(" • "),
                            artworkUrl = api.artwork.resolve(c.poster),
                            isOwned = c.libraryId != null,
                            width = 124.dp,
                            onClick = { onCreditTap(c) },
                        )
                    }
                }
            }
        }

        // In Your Library rail (if any)
        if (ownedCredits.isNotEmpty()) {
            item {
                MediaRail(title = "In Your Library (${ownedCredits.size})") {
                    items(
                        items = ownedCredits,
                        key = { "own-${it.kind}-${it.tmdbId}-${it.role.orEmpty()}" },
                    ) { c ->
                        CreditPosterCard(
                            title = c.title,
                            subtitle = listOfNotNull(c.yearText, c.role?.takeIf { it.isNotBlank() }).joinToString(" • "),
                            artworkUrl = api.artwork.resolve(c.poster),
                            isOwned = true,
                            width = 124.dp,
                            onClick = { onCreditTap(c) },
                        )
                    }
                }
            }
        }

        // Full Filmography section with filter chips + Grid/List toggle
        if (allCredits.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Filmography",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(SoulSyncColors.Card)
                                .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(999.dp))
                                .padding(2.dp),
                        ) {
                            IconButton(
                                onClick = { gridView = true },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (gridView) SoulSyncColors.Accent else Color.Transparent).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GridView,
                                    contentDescription = "Poster Grid",
                                    tint = if (gridView) Color.Black else SoulSyncColors.TextSoft,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { gridView = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (!gridView) SoulSyncColors.Accent else Color.Transparent).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = "List View",
                                    tint = if (!gridView) Color.Black else SoulSyncColors.TextSoft,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

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

            if (gridView) {
                val cols = if (isTv) 7 else 3
                val rows = visibleCredits.chunked(cols)
                items(
                    items = rows,
                    key = { row -> row.joinToString("|") { "${it.kind}-${it.tmdbId}-${it.role.orEmpty()}" } },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isTv) 24.dp else 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                    ) {
                        row.forEach { c ->
                            Box(modifier = Modifier.weight(1f)) {
                                CreditPosterCard(
                                    title = c.title,
                                    subtitle = listOfNotNull(c.yearText, c.role?.takeIf { it.isNotBlank() }).joinToString(" • "),
                                    artworkUrl = api.artwork.resolve(c.poster),
                                    isOwned = c.libraryId != null,
                                    onClick = { onCreditTap(c) },
                                )
                            }
                        }
                        repeat(cols - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(
                    items = visibleCredits,
                    key = { "list-${it.kind}-${it.tmdbId}-${it.role.orEmpty()}-${it.yearText.orEmpty()}" },
                ) { c ->
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        FilmographyListCard(
                            title = c.title,
                            year = c.yearText,
                            role = c.role,
                            kindLabel = if (c.kind == "show" || c.kind == "tv") "TV" else "Movie",
                            artworkUrl = api.artwork.resolve(c.poster),
                            isOwned = c.libraryId != null,
                            onClick = { onCreditTap(c) },
                        )
                    }
                }
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

    AdaptiveTvSheet(
        onDismissRequest = onDismiss,
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
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
    initialSeason: Int? = null,
    highlightEpisode: Int? = null,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<ShowDetail?>(null) }
    var extras by remember { mutableStateOf<TitleExtras?>(null) }
    var notes by remember { mutableStateOf<List<PlayerNote>>(emptyList()) }
    var noteDraft by remember { mutableStateOf("") }
    var inWatchlist by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var ownedOnly by remember { mutableStateOf(false) }
    var overviewExpanded by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<UnownedTitleTarget?>(null) }
    var issueTarget by remember { mutableStateOf<ReportIssueTarget?>(null) }
    var showTmdbId by remember { mutableStateOf<Int?>(null) }
    var showRequestMode by remember { mutableStateOf("ask") }

    LaunchedEffect(showId, api) {
        isLoading = true
        when (val res = api.fetchShowDetail(showId)) {
            is MusicApiResult.Success -> detail = res.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchVideoCatalog() as? MusicApiResult.Success)?.let { cat ->
            showTmdbId = cat.data.shows.firstOrNull { it.id == showId }?.tmdbId
        }
        (api.fetchVideoRequestOptions() as? MusicApiResult.Success)?.let { opts ->
            showRequestMode = opts.data.show
        }
        (api.fetchWatchlist() as? MusicApiResult.Success)?.let { list ->
            inWatchlist = list.data.any { it.itemType == "show" && it.itemId == showId.toString() }
        }
        (api.fetchTitleExtras("show", showId) as? MusicApiResult.Success)?.let { extras = it.data }
        (api.fetchNotes("show", showId.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
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

    val creators = remember(show.crew) { show.crew.filter { it.job == "Creator" } }
    val discoveryRails = rememberRelatedDiscoveryRails(
        kind = "show",
        selfId = show.id,
        cast = show.cast,
        leadCrew = creators,
        studioOrNetwork = show.network,
        franchiseCollection = null,
        api = api,
    )

    val seasons = remember(show) {
        show.seasons.sortedBy { if (it.seasonNumber == 0) 999 else it.seasonNumber }
    }
    var didApplyInitialSeason by remember(showId, initialSeason) { mutableStateOf(false) }
    LaunchedEffect(seasons, initialSeason) {
        if (!didApplyInitialSeason && initialSeason != null && seasons.isNotEmpty()) {
            val idx = seasons.indexOfFirst { it.seasonNumber == initialSeason }
            if (idx >= 0) selectedSeasonIndex = idx
            didApplyInitialSeason = true
        }
    }
    val safeSeasonIndex = selectedSeasonIndex.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))
    val activeSeason = seasons.getOrNull(safeSeasonIndex)

    // Smart "Up Next" episode: first unwatched owned episode across seasons, else first owned episode
    val upNext = remember(seasons) {
        val all = seasons.flatMap { se -> se.episodes.sortedBy { it.episodeNumber }.map { se to it } }
        all.firstOrNull { (_, ep) -> ep.owned && ep.watched != true }
            ?: all.firstOrNull { (_, ep) -> ep.owned }
    }

    fun playEpisode(seasonNum: Int, ep: net.ssync.player.DetailEpisode) {
        if (!ep.owned) return
        coordinator.playVideo(
            ActivePlayable(
                itemType = "episode",
                itemId = ep.id.toString(),
                title = ep.title ?: "Episode ${ep.episodeNumber}",
                subtitle = "${show.title} • S${seasonNum}E${ep.episodeNumber}",
                artworkUrl = api.artwork.episodeStill(ep.id),
                streamUrl = api.streamUrl("episode", ep.id.toString()),
                isVideo = true,
            ),
        )
    }

    val activeEpisodes = remember(activeSeason, ownedOnly) {
        val sorted = activeSeason?.episodes.orEmpty().sortedBy { it.episodeNumber }
        if (ownedOnly) sorted.filter { it.owned } else sorted
    }
    val totalSeasonEps = activeSeason?.episodes?.size ?: 0
    val ownedSeasonEps = activeSeason?.episodes?.count { it.owned } ?: 0
    val hasMissingInSeason = ownedSeasonEps < totalSeasonEps

    val isTv = LocalIsTv.current
    val showListState = rememberLazyListState()
    val highlightedEpFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val hasOverviewOrGenres = show.genres.isNotEmpty() || !show.overview.isNullOrBlank()
    val headerItemsCount = 2 + (if (hasOverviewOrGenres) 1 else 0) + (if (seasons.isNotEmpty()) 1 else 0) + 1

    LaunchedEffect(activeEpisodes, highlightEpisode, activeSeason?.seasonNumber, headerItemsCount) {
        if (highlightEpisode != null && (initialSeason == null || activeSeason?.seasonNumber == initialSeason)) {
            val epIdx = activeEpisodes.indexOfFirst { it.episodeNumber == highlightEpisode }
            if (epIdx >= 0) {
                showListState.animateScrollToItem((epIdx + headerItemsCount).coerceAtLeast(0))
                if (isTv) {
                    delay(120L)
                    runCatching { highlightedEpFocus.requestFocus() }
                }
            }
        }
    }

    if (isTv) {
        TvCinemaShowDetail(
            show = show,
            extras = extras,
            discoveryRails = discoveryRails,
            inWatchlist = inWatchlist,
            initialSeason = initialSeason,
            api = api,
            coordinator = coordinator,
            onPlayEpisode = { ep, sNum -> playEpisode(sNum, ep) },
            onOpenEpisodeDetail = { epId -> onNavigate(PlayerDestination.EpisodeDetail(show.id, epId)) },
            onToggleWatchlist = {
                scope.launch {
                    if (inWatchlist) api.removeFromWatchlist("show", show.id.toString())
                    else api.addToWatchlist("show", show.id.toString())
                    inWatchlist = !inWatchlist
                }
            },
            onStartWatchParty = {
                onNavigate(
                    PlayerDestination.StartWatchParty(
                        itemType = "show",
                        itemId = show.id.toString(),
                        title = show.title,
                        artwork = api.artwork.showPoster(show.id),
                        backdrop = api.artwork.showBackdrop(show.id),
                    ),
                )
            },
            onReportIssue = {
                issueTarget = ReportIssueTarget(
                    entityType = "show",
                    entityId = show.id,
                    title = show.title,
                )
            },
            onNavigate = onNavigate,
            onRequestUnowned = { extra ->
                pendingRequest = UnownedTitleTarget(
                    kind = "show",
                    tmdbId = extra.tmdbId,
                    title = extra.title,
                    year = extra.yearText,
                    posterUrl = api.artwork.resolve(extra.poster),
                )
            },
        )
    } else {
    LazyColumn(
        state = showListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Cinema Hero with Watchlist + Smart Up-Next CTA
        item {
            val heroMeta = listOfNotNull(
                show.year?.toString(),
                show.network,
                show.contentRating,
                show.rating?.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
            ).joinToString("  •  ")
            val upNextLabel = upNext?.let { (se, ep) ->
                "Up Next • S${se.seasonNumber} E${ep.episodeNumber}"
            } ?: "TV Series"
            HeroSpotlight(
                title = show.title,
                subtitle = heroMeta,
                backdropUrl = api.artwork.showBackdrop(show.id),
                eyebrow = upNextLabel,
                inWatchlist = inWatchlist,
                onPlay = {
                    upNext?.let { (se, ep) -> playEpisode(se.seasonNumber, ep) }
                },
                onToggleWatchlist = {
                    scope.launch {
                        if (inWatchlist) {
                            api.removeFromWatchlist("show", show.id.toString())
                        } else {
                            api.addToWatchlist("show", show.id.toString())
                        }
                        inWatchlist = !inWatchlist
                    }
                },
            )
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (upNext != null) {
                    item {
                        val (se, ep) = upNext
                        OutlinedButton(
                            onClick = {
                                onNavigate(
                                    PlayerDestination.StartWatchParty(
                                        itemType = "episode",
                                        itemId = ep.id.toString(),
                                        title = "${show.title} · S${se.seasonNumber} E${ep.episodeNumber}",
                                        artwork = "/api/video/poster/show/${show.id}",
                                        backdrop = "/api/video/backdrop/show/${show.id}",
                                    ),
                                )
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SoulSyncColors.Accent.copy(alpha = 0.14f),
                                contentColor = Color.White,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp).dpadFocusable(CircleShape),
                        ) {
                            Text("🍿 Watch Party", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    ReportIssuePillButton(
                        onClick = {
                            issueTarget = ReportIssueTarget(
                                entityType = "show",
                                entityId = show.id,
                                title = show.title,
                            )
                        },
                    )
                }
            }
        }

        // 2. Genre pills + Expandable Tagline / Overview
        if (show.genres.isNotEmpty() || !show.overview.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (show.genres.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(show.genres.take(5)) { g ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color.White.copy(alpha = 0.07f))
                                        .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(999.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(g, color = SoulSyncColors.TextSoft, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                    if (!show.overview.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SoulSyncColors.Card)
                                .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(14.dp))
                                .clickable { overviewExpanded = !overviewExpanded }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (!show.tagline.isNullOrBlank()) {
                                Text(
                                    text = show.tagline.orEmpty(),
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = show.overview.orEmpty(),
                                color = SoulSyncColors.TextSoft,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = if (overviewExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (show.overview.orEmpty().length > 140) {
                                Text(
                                    text = if (overviewExpanded) "Show less" else "Read more",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Visual Season Poster Strip (Apple TV+ / Plex-grade)
        if (seasons.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (seasons.size == 1) "Season" else "Seasons (${seasons.size})",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        val totalOwnedAll = seasons.sumOf { se -> se.episodes.count { it.owned } }
                        val totalAll = seasons.sumOf { it.episodes.size }
                        if (totalAll > 0) {
                            Text(
                                text = "$totalOwnedAll / $totalAll episodes",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(seasons.indices.toList(), key = { seasons[it].seasonNumber }) { idx ->
                            val s = seasons[idx]
                            val selected = idx == safeSeasonIndex
                            val sOwned = s.episodes.count { it.owned }
                            val sTotal = s.episodes.size.coerceAtLeast(s.episodeTotal)
                            val frac = if (sTotal > 0) (sOwned.toFloat() / sTotal.toFloat()).coerceIn(0f, 1f) else 0f
                            val seasonName = s.title?.takeIf { it.isNotBlank() }
                                ?: if (s.seasonNumber == 0) "Specials" else "Season ${s.seasonNumber}"
                            val posterUrl = if (s.hasPoster && s.id > 0) {
                                api.artwork.seasonPoster(s.id)
                            } else {
                                api.artwork.showPoster(show.id)
                            }

                            Column(
                                modifier = Modifier
                                    .width(104.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedSeasonIndex = idx }
                                    .dpadFocusable(RoundedCornerShape(12.dp)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SoulSyncColors.Card)
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) SoulSyncColors.Accent else SoulSyncColors.Line,
                                            shape = RoundedCornerShape(12.dp),
                                        ),
                                ) {
                                    SoulSyncArtwork(
                                        url = posterUrl,
                                        contentDescription = seasonName,
                                        fallbackIcon = Icons.Default.Movie,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .alpha(if (selected) 1f else 0.72f),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                            .align(Alignment.BottomCenter)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                                                ),
                                            ),
                                    )
                                    // Owned count pill
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 6.dp, bottom = 8.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                if (selected) SoulSyncColors.Accent else Color.Black.copy(alpha = 0.72f),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = "$sOwned/$sTotal",
                                            color = if (selected) Color.Black else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                    // Thin bottom completion bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .align(Alignment.BottomCenter)
                                            .background(Color.White.copy(alpha = 0.16f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(frac)
                                                .height(3.dp)
                                                .background(SoulSyncColors.Accent),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = seasonName,
                                    color = if (selected) Color.White else SoulSyncColors.TextSoft,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Active Season Header + Owned-Only Filter
        if (activeSeason != null) {
            item {
                val seasonHeading = activeSeason.title?.takeIf { it.isNotBlank() }
                    ?: if (activeSeason.seasonNumber == 0) "Specials" else "Season ${activeSeason.seasonNumber}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = seasonHeading,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "$ownedSeasonEps of $totalSeasonEps episodes in your library",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    if (hasMissingInSeason) {
                        FilterChip(
                            selected = ownedOnly,
                            onClick = { ownedOnly = !ownedOnly },
                            label = { Text("In library", fontSize = 12.sp) },
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

            // 5. Apple TV+ / Infuse-Grade Expandable Episode Cards
            items(activeEpisodes, key = { it.id }) { ep ->
                val epCode = "S${activeSeason.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
                val isHighlightedEp = highlightEpisode != null &&
                    ep.episodeNumber == highlightEpisode &&
                    (initialSeason == null || activeSeason.seasonNumber == initialSeason)
                ShowEpisodeCard(
                    episode = ep,
                    showTitle = show.title,
                    seasonNumber = activeSeason.seasonNumber,
                    showTmdbId = showTmdbId,
                    requestMode = showRequestMode,
                    stillUrl = api.artwork.episodeStill(ep.id),
                    fallbackBackdropUrl = api.artwork.showBackdrop(show.id),
                    api = api,
                    initialExpanded = isHighlightedEp,
                    headerFocusRequester = if (isHighlightedEp) highlightedEpFocus else null,
                    onPlay = { playEpisode(activeSeason.seasonNumber, ep) },
                    onStartWatchParty = {
                        scope.launch {
                            api.createWatchParty(
                                title = "${show.title} · S${activeSeason.seasonNumber} E${ep.episodeNumber}",
                                itemType = "episode",
                                itemId = ep.id.toString(),
                                meta = net.ssync.player.ProgressMeta(
                                    title = show.title,
                                    subtitle = "S${activeSeason.seasonNumber} E${ep.episodeNumber}" + (ep.title?.let { " · $it" } ?: ""),
                                    artwork = if (show.hasPoster) "/api/video/poster/show/${show.id}" else null,
                                    backdrop = if (show.hasBackdrop) "/api/video/backdrop/show/${show.id}" else null,
                                ),
                                showId = show.id,
                            )
                            onNavigate(PlayerDestination.WatchingNow)
                        }
                    },
                    onReportIssue = {
                        issueTarget = ReportIssueTarget(
                            entityType = "episode",
                            entityId = ep.id,
                            title = "${show.title} · $epCode${ep.title?.let { " — $it" } ?: ""}",
                        )
                    },
                    onPersonClick = { pid, name ->
                        onNavigate(PlayerDestination.PersonDetail(pid, name))
                    },
                )
            }
        }

        // 6. Cast Rail
        if (show.cast.isNotEmpty()) {
            item {
                MediaRail(title = "Cast") {
                    items(show.cast.take(15)) { member ->
                        SquareMediaCard(
                            title = member.name,
                            subtitle = member.character,
                            artworkUrl = api.artwork.resolve(member.photo),
                            fallbackIcon = Icons.Default.Person,
                            width = 104.dp,
                            onClick = {
                                member.tmdbId?.let { pid ->
                                    onNavigate(PlayerDestination.PersonDetail(pid, member.name))
                                }
                            },
                        )
                    }
                }
            }
        }

        // 6b. Plex-style Discovery Rails (Collections, Cast/Creator, Network)
        relatedDiscoveryRails(
            rails = discoveryRails,
            onNavigate = onNavigate,
            onRequestUnowned = { card ->
                if (card.tmdbId > 0) {
                    pendingRequest = UnownedTitleTarget(
                        kind = card.kind,
                        tmdbId = card.tmdbId,
                        title = card.title,
                        year = card.subtitle,
                        subtitle = if (card.kind == "show") "TV Show" else "Movie",
                        posterUrl = card.artworkUrl,
                    )
                }
            },
        )

        // 7. More Like This Rail (Owned vs Request poster cards)
        val similar = (extras?.recommendations.orEmpty() + extras?.similar.orEmpty())
            .distinctBy { it.tmdbId }
            .take(16)
        if (similar.isNotEmpty()) {
            item {
                MediaRail(title = "More Like This") {
                    items(similar, key = { "show-sim-${it.kind}-${it.tmdbId}" }) { rec ->
                        val isOwned = rec.libraryId != null
                        CreditPosterCard(
                            title = rec.title,
                            subtitle = rec.yearText ?: if (rec.kind == "show" || rec.kind == "tv") "TV Show" else "Movie",
                            artworkUrl = api.artwork.resolve(rec.poster),
                            isOwned = isOwned,
                            width = 126.dp,
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

        // 8. Personal Notes
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
                            api.addNote("show", show.id.toString(), body)
                            (api.fetchNotes("show", show.id.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
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

    if (pendingRequest != null) {
        UnownedTitleRequestSheet(
            target = pendingRequest!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { pendingRequest = null },
        )
    }
    if (issueTarget != null) {
        ReportIssueSheet(
            target = issueTarget!!,
            api = api,
            onDeviceRevoked = onDeviceRevoked,
            onDismiss = { issueTarget = null },
        )
    }
}

@Composable
private fun ShowEpisodeCard(
    episode: net.ssync.player.DetailEpisode,
    showTitle: String,
    seasonNumber: Int,
    showTmdbId: Int?,
    requestMode: String = "ask",
    stillUrl: String,
    fallbackBackdropUrl: String? = null,
    api: PlayerApiClient,
    initialExpanded: Boolean = false,
    headerFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onPlay: () -> Unit,
    onStartWatchParty: () -> Unit = {},
    onReportIssue: () -> Unit = {},
    onPersonClick: (Int, String) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(episode.id, initialExpanded) { mutableStateOf(initialExpanded) }
    var extra by remember(episode.id) { mutableStateOf<net.ssync.player.EpisodeExtra?>(null) }
    var extraLoading by remember(episode.id) { mutableStateOf(false) }
    var reqBusy by remember(episode.id) { mutableStateOf(false) }
    var reqResult by remember(episode.id) { mutableStateOf<VideoRequestResult?>(null) }
    var reqError by remember(episode.id) { mutableStateOf<String?>(null) }

    val canRequest = !episode.owned && showTmdbId != null && showTmdbId > 0 && requestMode != "none"
    val reqDone = reqResult is VideoRequestResult.Got ||
        reqResult is VideoRequestResult.Asked ||
        reqResult is VideoRequestResult.OnTheWay ||
        reqResult is VideoRequestResult.Owned
    val reqLabel = when (val r = reqResult) {
        VideoRequestResult.Got -> "Added to wishlist ✓"
        is VideoRequestResult.Asked -> if (r.already) "Already requested ✓" else "Requested ✓"
        VideoRequestResult.OnTheWay -> "On the way ✓"
        VideoRequestResult.Owned -> "In library ✓"
        else -> when {
            reqBusy -> "Requesting…"
            requestMode == "download" -> "Get Episode"
            else -> "Request Episode"
        }
    }

    fun triggerEpisodeRequest() {
        val tid = showTmdbId ?: return
        if (!canRequest || reqBusy || reqDone) return
        reqBusy = true
        reqError = null
        scope.launch {
            when (
                val res = api.requestVideoTitle(
                    kind = "episode",
                    tmdbId = tid,
                    title = showTitle,
                    season = seasonNumber,
                    episode = episode.episodeNumber,
                    get = requestMode == "download",
                )
            ) {
                is MusicApiResult.Success -> {
                    reqResult = res.data
                    if (res.data is VideoRequestResult.Error) {
                        reqError = (res.data as VideoRequestResult.Error).message
                    }
                }
                MusicApiResult.DeviceRevoked -> Unit
                is MusicApiResult.Error -> reqError = res.message
            }
            reqBusy = false
        }
    }

    LaunchedEffect(expanded, showTmdbId, seasonNumber, episode.episodeNumber) {
        if (expanded && extra == null && showTmdbId != null && showTmdbId > 0) {
            extraLoading = true
            (api.fetchEpisodeExtra(showTmdbId, seasonNumber, episode.episodeNumber) as? MusicApiResult.Success)?.let {
                extra = it.data
            }
            extraLoading = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = when {
            expanded -> SoulSyncColors.CardRaised
            episode.owned -> SoulSyncColors.Card
            else -> SoulSyncColors.Card.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = when {
                expanded -> SoulSyncColors.Accent.copy(alpha = 0.55f)
                episode.watched == true -> SoulSyncColors.Accent.copy(alpha = 0.35f)
                else -> SoulSyncColors.Line
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Put clickable ONLY on the header row so expanded child buttons (Play, Watch Party, Report) are 100% D-pad navigable!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(headerFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .dpadFocusable(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 16:9 Episode Still with Episode Number, Runtime, Watched Check, and Tap-to-Play Overlay
                Box(
                    modifier = Modifier
                        .width(146.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SoulSyncColors.Surface),
                ) {
                    SoulSyncArtwork(
                        url = stillUrl,
                        fallbackUrl = fallbackBackdropUrl,
                        contentDescription = episode.title,
                        fallbackIcon = Icons.Default.Movie,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (episode.owned) 1f else 0.45f),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                                ),
                            ),
                    )
                    // Top-left episode number pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "E${episode.episodeNumber.toString().padStart(2, '0')}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    // Top-right watched badge
                    if (episode.watched == true) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(SoulSyncColors.Accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Watched",
                                tint = Color.Black,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    // Center play affordance on owned episodes
                    if (episode.owned) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.58f))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play episode",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "MISSING",
                                color = SoulSyncColors.TextSoft,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                    // Bottom-right runtime pill
                    episode.runtimeMinutes?.takeIf { it > 0 }?.let { mins ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${mins}m",
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Right side: Title, Meta, Preview Overview
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.episodeNumber}",
                        color = if (episode.owned) Color.White else SoulSyncColors.TextSoft,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        episode.airDate?.takeIf { it.isNotBlank() },
                        episode.rating?.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
                        episode.resolution?.takeIf { it.isNotBlank() }?.uppercase(),
                        if (!episode.owned) "Not in library" else null,
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!expanded && !episode.overview.isNullOrBlank()) {
                        Text(
                            text = episode.overview.orEmpty(),
                            color = SoulSyncColors.TextSoft,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Expanded inline details: Badges, Full synopsis, Actions, Crew & Guest Stars
            if (expanded) {
                val fullOverview = episode.overview?.takeIf { it.isNotBlank() } ?: extra?.overview
                val epCode = "S${seasonNumber.toString().padStart(2, '0')}E${episode.episodeNumber.toString().padStart(2, '0')}"
                val epRating = episode.rating?.takeIf { it > 0 } ?: extra?.rating?.takeIf { it > 0 }
                val epRuntime = episode.runtimeMinutes?.takeIf { it > 0 } ?: extra?.runtimeMinutes?.takeIf { it > 0 }

                // Technical & episode info chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(SoulSyncColors.Accent.copy(alpha = 0.16f))
                                .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(epCode, color = SoulSyncColors.AccentLight, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    episode.resolution?.takeIf { it.isNotBlank() }?.let { res ->
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(res.uppercase(), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    epRating?.let { r ->
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0x33F5C518))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("★ ${"%.1f".format(r)}", color = Color(0xFFF5C518), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    epRuntime?.let { mins ->
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("${mins} min", color = SoulSyncColors.TextSoft, fontSize = 10.5.sp)
                            }
                        }
                    }
                }

                if (!fullOverview.isNullOrBlank()) {
                    Text(
                        text = fullOverview,
                        color = SoulSyncColors.TextSoft,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }

                // Action buttons: Play/Watch Party (owned) OR Request Episode (missing), plus Report Issue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episode.owned) {
                        Button(
                            onClick = onPlay,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoulSyncColors.Accent,
                                contentColor = SoulSyncColors.OnAccent,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onStartWatchParty,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        ) {
                            Text("🍿 Party", color = SoulSyncColors.AccentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OfflineDownloadButton(
                            api = api,
                            itemType = "episode",
                            itemId = episode.id.toString(),
                            title = "$showTitle · S${seasonNumber}E${episode.episodeNumber}",
                            subtitle = episode.title ?: "",
                            artworkUrl = api.artwork.episodeStill(episode.id),
                            streamUrl = api.streamUrl("episode", episode.id.toString()),
                            isVideo = true,
                            parentId = showTmdbId?.toString(),
                            compact = true,
                        )
                    } else if (canRequest) {
                        Button(
                            onClick = { triggerEpisodeRequest() },
                            enabled = !reqBusy && !reqDone,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (reqDone) SoulSyncColors.Card else SoulSyncColors.Accent,
                                contentColor = if (reqDone) SoulSyncColors.AccentLight else SoulSyncColors.OnAccent,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        ) {
                            Icon(
                                imageVector = if (reqDone) Icons.Default.Check else Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(reqLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    ReportIssuePillButton(
                        onClick = onReportIssue,
                        compact = true,
                    )
                }
                if (reqError != null) {
                    Text(
                        text = reqError!!,
                        color = Color(0xFFFF7A7A),
                        fontSize = 11.5.sp,
                    )
                }

                // Episode Crew (Director / Writer)
                val crewList = extra?.crew.orEmpty()
                if (crewList.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(crewList.take(6)) { c ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(999.dp))
                                    .clickable(enabled = (c.tmdbId ?: 0) > 0) {
                                        c.tmdbId?.let { pid -> onPersonClick(pid, c.name) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "${c.job}:",
                                    color = SoulSyncColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = c.name,
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // Guest Stars portrait rail
                val guests = extra?.guestStars.orEmpty()
                if (guests.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "GUEST STARS & CAST",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(guests.take(15)) { g ->
                                Column(
                                    modifier = Modifier
                                        .width(74.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = (g.tmdbId ?: 0) > 0) {
                                            g.tmdbId?.let { pid -> onPersonClick(pid, g.name) }
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = api.artwork.resolve(g.photo),
                                        contentDescription = g.name,
                                        fallbackIcon = Icons.Default.Person,
                                        modifier = Modifier
                                            .size(62.dp)
                                            .clip(CircleShape)
                                            .background(SoulSyncColors.Surface)
                                            .border(1.dp, SoulSyncColors.Line, CircleShape),
                                    )
                                    Text(
                                        text = g.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!g.character.isNullOrBlank()) {
                                        Text(
                                            text = g.character!!,
                                            color = SoulSyncColors.TextSecondary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (extraLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = SoulSyncColors.AccentLight,
                        )
                        Text(
                            text = "Loading guest cast & credits…",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeDetailScreen(
    showId: Int,
    episodeId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<ShowDetail?>(null) }
    var notes by remember { mutableStateOf<List<PlayerNote>>(emptyList()) }
    var noteDraft by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showId, episodeId, api) {
        isLoading = true
        when (val res = api.fetchShowDetail(showId)) {
            is MusicApiResult.Success -> detail = res.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchNotes("episode", episodeId.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
        isLoading = false
    }

    val show = detail
    if (isLoading) {
        LoadingBox()
        return
    }
    if (show == null) {
        EmptyBox("Couldn't load episode details.")
        return
    }

    val sortedSeasons = remember(show) {
        show.seasons.sortedBy { if (it.seasonNumber == 0) 999 else it.seasonNumber }
    }
    val flat = remember(sortedSeasons) {
        sortedSeasons.flatMap { se ->
            se.episodes.sortedBy { it.episodeNumber }.map { se to it }
        }
    }
    val currentIndex = flat.indexOfFirst { it.second.id == episodeId }
    if (currentIndex < 0) {
        EmptyBox("Episode not found.")
        return
    }
    val (season, ep) = flat[currentIndex]
    val prev = flat.getOrNull(currentIndex - 1)
    val next = flat.getOrNull(currentIndex + 1)
    val code = "S${season.seasonNumber.toString().padStart(2, '0')} · E${ep.episodeNumber.toString().padStart(2, '0')}"
    val seasonLabel = season.title ?: if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"

    fun playEpisode(targetSeasonNum: Int, target: net.ssync.player.DetailEpisode) {
        if (!target.owned) return
        coordinator.playVideo(
            ActivePlayable(
                itemType = "episode",
                itemId = target.id.toString(),
                title = target.title ?: "Episode ${target.episodeNumber}",
                subtitle = "${show.title} • S${targetSeasonNum}E${target.episodeNumber}",
                artworkUrl = api.artwork.episodeStill(target.id),
                streamUrl = api.streamUrl("episode", target.id.toString()),
                isVideo = true,
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            HeroSpotlight(
                title = ep.title ?: "Episode ${ep.episodeNumber}",
                subtitle = listOfNotNull(
                    show.title,
                    code,
                    ep.airDate,
                    ep.runtimeMinutes?.let { "${it}m" },
                    if (!ep.owned) "Not in library" else null,
                ).joinToString("  •  "),
                backdropUrl = api.artwork.episodeStill(ep.id),
                eyebrow = "$seasonLabel • $code",
                onPlay = { playEpisode(season.seasonNumber, ep) },
            )
        }

        // Quick episode navigation strip: Prev / Next / Show Page
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = false,
                        onClick = { onNavigate(PlayerDestination.ShowDetail(show.id)) },
                        label = { Text(show.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SoulSyncColors.Card,
                            labelColor = SoulSyncColors.AccentLight,
                        ),
                        modifier = Modifier.dpadFocusable(),
                    )
                }
                if (prev != null) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { onNavigate(PlayerDestination.EpisodeDetail(show.id, prev.second.id)) },
                            label = { Text("← S${prev.first.seasonNumber}E${prev.second.episodeNumber}") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SoulSyncColors.Card,
                                labelColor = Color.White,
                            ),
                            modifier = Modifier.dpadFocusable(),
                        )
                    }
                }
                if (next != null) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { onNavigate(PlayerDestination.EpisodeDetail(show.id, next.second.id)) },
                            label = { Text("S${next.first.seasonNumber}E${next.second.episodeNumber} →") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SoulSyncColors.Card,
                                labelColor = Color.White,
                            ),
                            modifier = Modifier.dpadFocusable(),
                        )
                    }
                }
            }
        }

        if (!ep.overview.isNullOrBlank()) {
            item {
                Text(
                    text = ep.overview.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
                            api.addNote("episode", ep.id.toString(), body)
                            (api.fetchNotes("episode", ep.id.toString()) as? MusicApiResult.Success)?.let { notes = it.data }
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

        val siblings = season.episodes.sortedBy { it.episodeNumber }
        if (siblings.size > 1) {
            item {
                SectionHeader("More in $seasonLabel")
            }
            items(siblings, key = { "sib-${it.id}" }) { other ->
                val isCurrent = other.id == ep.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .dpadFocusable(RoundedCornerShape(12.dp))
                        .clickable {
                            if (!isCurrent) {
                                onNavigate(PlayerDestination.EpisodeDetail(show.id, other.id))
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.14f) else SoulSyncColors.Card,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SoulSyncArtwork(
                            url = api.artwork.episodeStill(other.id),
                            contentDescription = other.title,
                            modifier = Modifier
                                .width(104.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .alpha(if (other.owned) 1f else 0.55f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "E${other.episodeNumber} • ${other.title ?: "Episode ${other.episodeNumber}"}",
                                color = if (isCurrent) SoulSyncColors.AccentLight else Color.White,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = listOfNotNull(
                                if (isCurrent) "Viewing" else null,
                                other.airDate,
                                if (!other.owned) "Not in library" else null,
                            ).joinToString(" • ")
                            if (sub.isNotBlank()) {
                                Text(sub, color = SoulSyncColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        if (other.owned) {
                            IconButton(onClick = { playEpisode(season.seasonNumber, other) }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = SoulSyncColors.AccentLight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

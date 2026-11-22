package net.ssync.app.player

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
            // Top-left status pill: vibrant emerald checkmark for owned, frosted glass Request pill for unowned
            if (isOwned) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SoulSyncColors.Accent)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "In Library",
                        tint = Color.Black,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = "IN LIBRARY",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.4.sp,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Request",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = "REQUEST",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
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
                                    .background(if (gridView) SoulSyncColors.Accent else Color.Transparent),
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
                                    .background(if (!gridView) SoulSyncColors.Accent else Color.Transparent),
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
                val rows = visibleCredits.chunked(3)
                items(
                    items = rows,
                    key = { row -> row.joinToString("|") { "${it.kind}-${it.tmdbId}-${it.role.orEmpty()}" } },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        repeat(3 - row.size) {
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
    onNavigate: (PlayerDestination) -> Unit,
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
                        .clickable {
                            onNavigate(PlayerDestination.EpisodeDetail(show.id, ep.id))
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
                                .clip(RoundedCornerShape(8.dp))
                                .alpha(if (ep.owned) 1f else 0.55f),
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
                            } else if (!ep.owned) {
                                Text(
                                    text = "Not in your library",
                                    color = SoulSyncColors.TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (ep.owned) {
                            IconButton(
                                onClick = {
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
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play episode",
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
                            IconButton(onClick = { playEpisode(season.seasonNumber, other) }) {
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

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.GuideChannel
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicChannel
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerSettings
import net.ssync.player.TogetherTitle
import net.ssync.player.VideoCatalog
import net.ssync.player.WatchingSession
import net.ssync.player.WatchlistItem
import net.ssync.storage.PairedServerRecord

private data class LiveGuideSlot(
    val id: String,
    val number: Int,
    val channelName: String,
    val nowItemType: String,
    val nowItemId: String,
    val nowTitle: String,
    val nowSubtitle: String,
    val nowArtworkUrl: String?,
    val nowOffsetMs: Long,
    val nowProgress: Float,
    val nextTitle: String,
)

private fun buildLiveGuideSlots(
    catalog: VideoCatalog,
    customChannels: List<GuideChannel>,
    api: PlayerApiClient,
): List<LiveGuideSlot> {
    val nowSec = System.currentTimeMillis() / 1000L
    val out = mutableListOf<LiveGuideSlot>()

    fun addMovieChannel(id: String, number: Int, name: String, movies: List<net.ssync.player.CatalogMovie>) {
        if (movies.isEmpty()) return
        val durations = movies.map { (it.runtimeSeconds ?: 6000).coerceAtLeast(1800).toLong() }
        val total = durations.sum().coerceAtLeast(1L)
        var pos = (nowSec + number * 7919L) % total
        var idx = 0
        while (idx < movies.lastIndex && pos >= durations[idx]) {
            pos -= durations[idx]
            idx++
        }
        val cur = movies[idx]
        val next = movies[(idx + 1) % movies.size]
        val dur = durations[idx].coerceAtLeast(1L)
        out += LiveGuideSlot(
            id = id,
            number = number,
            channelName = name,
            nowItemType = "movie",
            nowItemId = cur.id.toString(),
            nowTitle = cur.title,
            nowSubtitle = listOfNotNull(cur.year?.toString(), cur.genres.firstOrNull()).joinToString(" · "),
            nowArtworkUrl = api.artwork.movieBackdrop(cur.id),
            nowOffsetMs = pos * 1000L,
            nowProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f),
            nextTitle = next.title,
        )
    }

    var chNum = 101
    if (catalog.movies.isNotEmpty()) {
        addMovieChannel("auto-premiere", chNum++, "Premiere Movies", catalog.movies.sortedByDescending { it.addedAt.orEmpty() }.take(24))
        addMovieChannel("auto-top-rated", chNum++, "Top Rated Cinema", catalog.movies.sortedByDescending { it.rating ?: 0.0 }.take(24))
    }

    if (catalog.recentEpisodes.isNotEmpty()) {
        val eps = catalog.recentEpisodes.take(24)
        val durations = eps.map { (it.runtimeSeconds ?: 2700).coerceAtLeast(900).toLong() }
        val total = durations.sum().coerceAtLeast(1L)
        var pos = (nowSec + chNum * 7919L) % total
        var idx = 0
        while (idx < eps.lastIndex && pos >= durations[idx]) {
            pos -= durations[idx]
            idx++
        }
        val cur = eps[idx]
        val next = eps[(idx + 1) % eps.size]
        val dur = durations[idx].coerceAtLeast(1L)
        out += LiveGuideSlot(
            id = "auto-tv-binge",
            number = chNum++,
            channelName = "SoulSync TV",
            nowItemType = "episode",
            nowItemId = cur.id.toString(),
            nowTitle = cur.showTitle,
            nowSubtitle = "S${cur.seasonNumber} E${cur.episodeNumber}" + (cur.title?.let { " · $it" } ?: ""),
            nowArtworkUrl = if (cur.hasStill) api.artwork.episodeStill(cur.id) else api.artwork.showBackdrop(cur.showId),
            nowOffsetMs = pos * 1000L,
            nowProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f),
            nextTitle = "${next.showTitle} S${next.seasonNumber}E${next.episodeNumber}",
        )
    }

    val topGenres = catalog.movies.flatMap { it.genres }
        .groupingBy { it }.eachCount()
        .entries.filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .take(10)
    topGenres.forEach { (genre, _) ->
        val list = catalog.movies.filter { it.genres.contains(genre) }
        addMovieChannel("auto-genre-${genre.lowercase()}", chNum++, "$genre HD", list)
    }

    customChannels.forEach { ch ->
        if (out.none { it.id == ch.id }) {
            val type = ch.currentItemType ?: "movie"
            val itemId = ch.currentItemId ?: ch.id
            out += LiveGuideSlot(
                id = ch.id,
                number = ch.number ?: chNum++,
                channelName = ch.name,
                nowItemType = type,
                nowItemId = itemId,
                nowTitle = ch.currentTitle ?: ch.description ?: ch.name,
                nowSubtitle = "Custom Channel",
                nowArtworkUrl = api.artwork.forItem(type, itemId, preferBackdrop = true),
                nowOffsetMs = 0L,
                nowProgress = 0.35f,
                nextTitle = ch.nextTitle ?: "Scheduled",
            )
        }
    }

    return out
}

@Composable
fun GuideScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var customChannels by remember { mutableStateOf<List<GuideChannel>>(emptyList()) }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var musicChannels by remember { mutableStateOf<List<MusicChannel>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        coroutineScope {
            val vDef = async { api.fetchVideoCatalog() }
            val gDef = async { api.fetchGuideChannels() }
            val mDef = async { api.fetchMusicChannels() }
            val fDef = async { api.fetchGuideFavorites() }
            val v = vDef.await()
            val g = gDef.await()
            val m = mDef.await()
            val f = fDef.await()
            if (v is MusicApiResult.DeviceRevoked || g is MusicApiResult.DeviceRevoked || m is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (v as? MusicApiResult.Success)?.let { catalog = it.data }
            (g as? MusicApiResult.Success)?.let { customChannels = it.data }
            (m as? MusicApiResult.Success)?.let { musicChannels = it.data }
            (f as? MusicApiResult.Success)?.let { favorites = it.data }
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    val allSlots = remember(catalog, customChannels) {
        buildLiveGuideSlots(catalog, customChannels, api)
    }
    val shownChannels = if (favoritesOnly) allSlots.filter { it.id in favorites } else allSlots

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Guide", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Text("${allSlots.size} live channels on air from your library", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                }
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("Favorites") },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
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

        if (shownChannels.isNotEmpty()) {
            items(shownChannels, key = { it.id }) { slot ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dpadFocusable(RoundedCornerShape(14.dp))
                        .clickable {
                            coordinator.playVideo(
                                ActivePlayable(
                                    itemType = slot.nowItemType,
                                    itemId = slot.nowItemId,
                                    title = slot.nowTitle,
                                    subtitle = "CH ${slot.number} · ${slot.channelName}",
                                    artworkUrl = slot.nowArtworkUrl,
                                    streamUrl = api.streamUrl(slot.nowItemType, slot.nowItemId),
                                    isVideo = true,
                                    resumePositionMs = slot.nowOffsetMs,
                                ),
                            )
                        },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(108.dp)
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            ) {
                                SoulSyncArtwork(
                                    url = slot.nowArtworkUrl,
                                    contentDescription = slot.nowTitle,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.78f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "CH ${slot.number}",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "● ON AIR  ${slot.channelName}",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val isFav = slot.id in favorites
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Favorite channel",
                                        tint = if (isFav) SoulSyncColors.Warning else SoulSyncColors.TextSecondary.copy(alpha = 0.45f),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                val updated = if (isFav) favorites - slot.id else favorites + slot.id
                                                favorites = updated
                                                scope.launch { api.saveGuideFavorites(updated.toList()) }
                                            },
                                    )
                                }
                                Text(
                                    text = slot.nowTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Up next: ${slot.nextTitle}",
                                    color = SoulSyncColors.TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { slot.nowProgress },
                            color = SoulSyncColors.Accent,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                }
            }
        }

        if (musicChannels.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader("Music Channels")
            }
            items(musicChannels, key = { it.id }) { mc ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dpadFocusable(RoundedCornerShape(12.dp))
                        .clickable {
                            val firstTrack = mc.tracks.firstOrNull() ?: return@clickable
                            val queue = mc.tracks.map { tid ->
                                ActivePlayable(
                                    itemType = "track",
                                    itemId = tid.toString(),
                                    title = mc.name,
                                    subtitle = "Music Channel",
                                    streamUrl = api.streamUrl("track", tid.toString()),
                                )
                            }
                            coordinator.playAudio(queue.first(), queue.drop(1))
                        },
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = SoulSyncColors.Accent)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mc.name, style = MaterialTheme.typography.titleMedium)
                            Text(mc.description ?: "${mc.trackCount} tracks", style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Listen", tint = SoulSyncColors.AccentLight)
                    }
                }
            }
        }

        if (shownChannels.isEmpty() && musicChannels.isEmpty()) {
            item { EmptyBox("No Guide channels configured on this server yet.") }
        }
    }
}

@Composable
fun WatchlistScreen(
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WatchlistItem>>(emptyList()) }
    var catalog by remember { mutableStateOf(net.ssync.player.VideoCatalog()) }
    var isLoading by remember { mutableStateOf(true) }
    var kindFilter by remember { mutableStateOf("all") }
    var sortByTitle by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchWatchlist()) {
            is MusicApiResult.Success -> items = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchVideoCatalog() as? MusicApiResult.Success)?.let { catalog = it.data }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (items.isEmpty()) {
        EmptyBox("Your Watchlist is empty. Save movies or shows to watch later.")
        return
    }

    val moviesById = remember(catalog.movies) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog.shows) { catalog.shows.associateBy { it.id } }
    val movieCount = items.count { it.itemType == "movie" }
    val showCount = items.count { it.itemType == "show" }
    val ytCount = items.count { it.itemType == "youtube" }

    val tabs = buildList {
        add("all" to "All ${items.size}")
        if (movieCount > 0) add("movie" to "Movies $movieCount")
        if (showCount > 0) add("show" to "TV $showCount")
        if (ytCount > 0) add("youtube" to "YouTube $ytCount")
    }

    fun titleOf(w: WatchlistItem): String {
        val idInt = w.itemId.toIntOrNull()
        return (if (w.itemType == "movie" && idInt != null) moviesById[idInt]?.title else null)
            ?: (if (w.itemType == "show" && idInt != null) showsById[idInt]?.title else null)
            ?: w.meta?.title
            ?: w.itemId
    }

    fun artOf(w: WatchlistItem): String? =
        api.artwork.resolve(w.meta?.artwork) ?: api.artwork.forItem(w.itemType, w.itemId, preferBackdrop = false)

    val filtered = remember(items, kindFilter, sortByTitle, catalog) {
        val base = items.filter { kindFilter == "all" || it.itemType == kindFilter }
        if (sortByTitle) base.sortedBy { titleOf(it).lowercase().removePrefix("the ") } else base
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeaderAndTabs(
            title = "Watchlist",
            tabs = tabs,
            selected = kindFilter,
            onSelect = { kindFilter = it },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !sortByTitle,
                    onClick = { sortByTitle = false },
                    label = { Text("Recently added") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                    ),
                )
                FilterChip(
                    selected = sortByTitle,
                    onClick = { sortByTitle = true },
                    label = { Text("A to Z") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                    ),
                )
            }
            TextButton(onClick = { gridView = !gridView }) {
                Text(if (gridView) "List" else "Grid", color = SoulSyncColors.AccentLight)
            }
        }

        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 116.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(filtered, key = { "${it.itemType}:${it.itemId}" }) { w ->
                    PosterCard(
                        title = titleOf(w),
                        subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                        artworkUrl = artOf(w),
                        onClick = {
                            val idInt = w.itemId.toIntOrNull()
                            if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                            if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "${it.itemType}:${it.itemId}" }) { w ->
                    CompactMediaListRow(
                        title = titleOf(w),
                        subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                        artworkUrl = artOf(w),
                        onClick = {
                            val idInt = w.itemId.toIntOrNull()
                            if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                            if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                        },
                        trailing = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        api.removeFromWatchlist(w.itemType, w.itemId)
                                        items = items.filterNot { it.itemType == w.itemType && it.itemId == w.itemId }
                                    }
                                },
                                modifier = Modifier.dpadFocusable(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = SoulSyncColors.TextSecondary)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun WatchingNowScreen(
    api: PlayerApiClient,
    onDeviceRevoked: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<WatchingSession>>(emptyList()) }
    var together by remember { mutableStateOf<List<TogetherTitle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val p = api.fetchPresence()) {
            is MusicApiResult.Success -> sessions = p.data.sessions
            MusicApiResult.DeviceRevoked -> {
                onDeviceRevoked()
                return@LaunchedEffect
            }
            is MusicApiResult.Error -> Unit
        }
        (api.fetchWatchTogether() as? MusicApiResult.Success)?.let { together = it.data }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Watching Now", style = MaterialTheme.typography.headlineMedium) }
        if (sessions.isEmpty()) {
            item { EmptyBox("Nobody else is watching or listening right now.") }
        } else {
            items(sessions) { s ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (s.you) "${s.profileName} (You)" else s.profileName,
                            color = SoulSyncColors.AccentLight,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (s.paused) "Paused" else "Playing now",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        if (together.isNotEmpty()) {
            item { SectionHeader("Watch Together") }
            items(together) { t ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text("Shared interest: ${t.profiles.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var podcasts by remember { mutableStateOf<List<net.ssync.player.PodcastSub>>(emptyList()) }
    var books by remember { mutableStateOf<List<net.ssync.player.OwnedBook>>(emptyList()) }
    var artists by remember { mutableStateOf<List<net.ssync.player.LibArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<net.ssync.player.LibAlbum>>(emptyList()) }
    var songs by remember { mutableStateOf<List<net.ssync.player.LibSong>>(emptyList()) }
    var searchingMusic by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        coroutineScope {
            val vDef = async { api.fetchVideoCatalog() }
            val pDef = async { api.fetchPodcastSubs() }
            val bDef = async { api.fetchAudiobooks() }
            val v = vDef.await()
            if (v is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (v as? MusicApiResult.Success)?.let { catalog = it.data }
            (pDef.await() as? MusicApiResult.Success)?.let { podcasts = it.data }
            (bDef.await() as? MusicApiResult.Success)?.let { books = it.data }
        }
    }

    // Live debounced music library search (Artists, Albums, Songs)
    LaunchedEffect(query, api) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            artists = emptyList()
            albums = emptyList()
            songs = emptyList()
            searchingMusic = false
            return@LaunchedEffect
        }
        searchingMusic = true
        kotlinx.coroutines.delay(220L)
        coroutineScope {
            val arDef = async { api.fetchArtists(search = trimmed, limit = 12) }
            val alDef = async { api.fetchAlbums(search = trimmed, limit = 12) }
            val soDef = async { api.fetchSongs(search = trimmed, limit = 20) }
            (arDef.await() as? MusicApiResult.Success)?.let { artists = it.data }
            (alDef.await() as? MusicApiResult.Success)?.let { albums = it.data }
            (soDef.await() as? MusicApiResult.Success)?.let { songs = it.data }
        }
        searchingMusic = false
    }

    val q = query.trim().lowercase()
    val movieHits = if (q.isEmpty()) emptyList() else catalog.movies.filter {
        it.title.lowercase().contains(q) || it.genres.any { g -> g.lowercase().contains(q) } || (it.studio?.lowercase()?.contains(q) == true)
    }.take(24)
    val showHits = if (q.isEmpty()) emptyList() else catalog.shows.filter {
        it.title.lowercase().contains(q) || it.genres.any { g -> g.lowercase().contains(q) } || (it.network?.lowercase()?.contains(q) == true)
    }.take(24)
    val ytHits = if (q.isEmpty()) emptyList() else catalog.youtube.filter {
        it.title.lowercase().contains(q) || (it.channelTitle?.lowercase()?.contains(q) == true)
    }.take(20)
    val podcastHits = if (q.isEmpty()) emptyList() else podcasts.filter {
        it.title.lowercase().contains(q) || (it.author?.lowercase()?.contains(q) == true)
    }.take(12)
    val bookHits = if (q.isEmpty()) emptyList() else books.filter {
        it.title.lowercase().contains(q) || (it.author?.lowercase()?.contains(q) == true)
    }.take(12)

    val musicCount = artists.size + albums.size + songs.size
    val totalCount = movieHits.size + showHits.size + musicCount + ytHits.size + podcastHits.size + bookHits.size

    val tabs = buildList {
        add("all" to "All $totalCount")
        if (movieHits.isNotEmpty()) add("movies" to "Movies ${movieHits.size}")
        if (showHits.isNotEmpty()) add("tv" to "TV ${showHits.size}")
        if (musicCount > 0) add("music" to "Music $musicCount")
        if (ytHits.isNotEmpty()) add("youtube" to "YouTube ${ytHits.size}")
        if (podcastHits.isNotEmpty()) add("podcasts" to "Podcasts ${podcastHits.size}")
        if (bookHits.isNotEmpty()) add("audiobooks" to "Audiobooks ${bookHits.size}")
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                filter = "all"
            },
            placeholder = { Text("Search movies, shows, artists, albums, songs, podcasts…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().dpadFocusable(),
        )

        if (q.isNotEmpty() && tabs.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tabs, key = { it.first }) { (key, label) ->
                    val on = filter == key
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (on) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line, CircleShape)
                            .clickable { filter = key }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(label, color = if (on) Color.White else SoulSyncColors.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (q.isEmpty()) {
            EmptyBox("Search across your entire SoulSync library — movies, TV shows, music, YouTube, podcasts, and audiobooks.")
        } else if (totalCount == 0 && !searchingMusic) {
            EmptyBox("No matches for \"$query\".")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if ((filter == "all" || filter == "movies") && movieHits.isNotEmpty()) {
                    item { SectionHeader("Movies") }
                    items(movieHits, key = { "m-${it.id}" }) { m ->
                        CompactMediaListRow(
                            title = m.title,
                            subtitle = listOfNotNull("Movie", m.year?.toString(), m.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                            artworkUrl = api.artwork.moviePoster(m.id),
                            onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "tv") && showHits.isNotEmpty()) {
                    item { SectionHeader("TV Shows") }
                    items(showHits, key = { "s-${it.id}" }) { s ->
                        CompactMediaListRow(
                            title = s.title,
                            subtitle = listOfNotNull("TV Show", "${s.episodeCount} episodes", s.network).joinToString(" · "),
                            artworkUrl = api.artwork.showPoster(s.id),
                            onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artists, key = { "ar-${it.id}" }) { ar ->
                        SimpleTrackRow(
                            title = ar.name,
                            subtitle = "Artist · ${ar.albumCount} albums",
                            artworkUrl = api.artwork.resolve(ar.imageUrl ?: ar.remoteImageUrl),
                            onClick = { onNavigate(PlayerDestination.ArtistDetail(ar.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(albums, key = { "al-${it.id}" }) { al ->
                        SimpleTrackRow(
                            title = al.title,
                            subtitle = listOfNotNull("Album", al.artistName, al.year?.toString()).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                            onClick = { onNavigate(PlayerDestination.AlbumDetail(al.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    items(songs, key = { "so-${it.id}" }) { song ->
                        SimpleTrackRow(
                            title = song.title,
                            subtitle = listOfNotNull("Song", song.artistName, song.albumTitle).joinToString(" · "),
                            artworkUrl = api.artwork.albumArtwork(song.albumId),
                            onClick = {
                                coordinator.playAudio(
                                    ActivePlayable(
                                        itemType = "track",
                                        itemId = song.id.toString(),
                                        title = song.title,
                                        subtitle = song.artistName ?: "Unknown Artist",
                                        artworkUrl = api.artwork.albumArtwork(song.albumId),
                                        streamUrl = api.streamUrl("track", song.id.toString()),
                                    ),
                                )
                            },
                        )
                    }
                }
                if ((filter == "all" || filter == "youtube") && ytHits.isNotEmpty()) {
                    item { SectionHeader("YouTube") }
                    items(ytHits, key = { "y-${it.id}" }) { y ->
                        SimpleTrackRow(
                            title = y.title,
                            subtitle = "YouTube · ${y.channelTitle ?: ""}".trimEnd(' ', '·'),
                            artworkUrl = api.artwork.resolve(y.thumbnailUrl),
                            onClick = {
                                coordinator.playVideo(
                                    ActivePlayable(
                                        itemType = "youtube",
                                        itemId = y.id,
                                        title = y.title,
                                        subtitle = y.channelTitle.orEmpty(),
                                        artworkUrl = api.artwork.resolve(y.thumbnailUrl),
                                        streamUrl = api.streamUrl("youtube", y.id),
                                        isVideo = true,
                                    ),
                                )
                            },
                        )
                    }
                }
                if ((filter == "all" || filter == "podcasts") && podcastHits.isNotEmpty()) {
                    item { SectionHeader("Podcasts") }
                    items(podcastHits, key = { "p-${it.id}" }) { p ->
                        SimpleTrackRow(
                            title = p.title,
                            subtitle = listOfNotNull("Podcast", p.author).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(p.artworkUrl),
                            onClick = { onNavigate(PlayerDestination.PodcastDetail(p)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "audiobooks") && bookHits.isNotEmpty()) {
                    item { SectionHeader("Audiobooks") }
                    items(bookHits, key = { "b-${it.asin}" }) { b ->
                        SimpleTrackRow(
                            title = b.title,
                            subtitle = listOfNotNull("Audiobook", b.author).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(b.coverUrl) ?: api.artwork.audiobookCover(b.asin),
                            onClick = { onNavigate(PlayerDestination.AudiobookDetail(b)) },
                        )
                    }
                }
            }
        }
    }
}


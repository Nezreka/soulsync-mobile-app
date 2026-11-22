package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun GuideScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var channels by remember { mutableStateOf<List<GuideChannel>>(emptyList()) }
    var musicChannels by remember { mutableStateOf<List<MusicChannel>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        coroutineScope {
            val gDef = async { api.fetchGuideChannels() }
            val mDef = async { api.fetchMusicChannels() }
            val fDef = async { api.fetchGuideFavorites() }
            val g = gDef.await()
            val m = mDef.await()
            val f = fDef.await()
            if (g is MusicApiResult.DeviceRevoked || m is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (g as? MusicApiResult.Success)?.let { channels = it.data }
            (m as? MusicApiResult.Success)?.let { musicChannels = it.data }
            (f as? MusicApiResult.Success)?.let { favorites = it.data }
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    val shownChannels = if (favoritesOnly) channels.filter { it.id in favorites } else channels

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Guide", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
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
            item {
                // Premier EPG Broadcast Time Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("CH", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(68.dp))
                    Text("ON NOW", color = SoulSyncColors.AccentLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                    Text("UP NEXT", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
            items(shownChannels, key = { it.id }) { ch ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left Channel Pill
                    Card(
                        modifier = Modifier.width(64.dp).height(76.dp),
                        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.PanelSolid),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = ch.number?.toString() ?: "TV",
                                color = SoulSyncColors.AccentLight,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = ch.name.take(8),
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    // Active ON AIR Program Block
                    Card(
                        modifier = Modifier
                            .weight(1.4f)
                            .height(76.dp)
                            .dpadFocusable(RoundedCornerShape(10.dp))
                            .clickable {
                                val type = ch.currentItemType ?: "movie"
                                val id = ch.currentItemId ?: ch.id
                                coordinator.playVideo(
                                    ActivePlayable(
                                        itemType = type,
                                        itemId = id,
                                        title = ch.currentTitle ?: ch.name,
                                        subtitle = "CH ${ch.number ?: ""} • ${ch.name}".trim(),
                                        streamUrl = api.streamUrl(type, id),
                                        isVideo = true,
                                    ),
                                )
                            },
                        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.CardRaised),
                        border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "● LIVE  ${ch.name}",
                                color = SoulSyncColors.AccentLight,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                text = ch.currentTitle ?: ch.description ?: "Live stream",
                                color = SoulSyncColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    // Next Up Program Block
                    Card(
                        modifier = Modifier.weight(1f).height(76.dp),
                        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("NEXT", color = SoulSyncColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = ch.nextTitle ?: "Scheduled",
                                color = SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                maxLines = 2,
                            )
                        }
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
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchWatchlist()) {
            is MusicApiResult.Success -> items = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
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

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Watchlist", style = MaterialTheme.typography.headlineMedium) }
        items(items, key = { "${it.itemType}:${it.itemId}" }) { w ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusable(RoundedCornerShape(12.dp))
                    .clickable {
                        val idInt = w.itemId.toIntOrNull()
                        if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                        if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                    },
                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(w.meta?.title ?: w.itemId, style = MaterialTheme.typography.titleMedium)
                        Text(w.itemType.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                    }
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
    var catalog by remember { mutableStateOf(VideoCatalog()) }

    LaunchedEffect(api) {
        when (val r = api.fetchVideoCatalog()) {
            is MusicApiResult.Success -> catalog = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
    }

    val q = query.trim().lowercase()
    val movieHits = if (q.isEmpty()) emptyList() else catalog.movies.filter { it.title.lowercase().contains(q) }
    val showHits = if (q.isEmpty()) emptyList() else catalog.shows.filter { it.title.lowercase().contains(q) }
    val ytHits = if (q.isEmpty()) emptyList() else catalog.youtube.filter { it.title.lowercase().contains(q) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search movies, TV, YouTube...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().dpadFocusable(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(movieHits, key = { "m-${it.id}" }) { m ->
                SimpleTrackRow(
                    title = m.title,
                    subtitle = "Movie • ${m.year ?: ""}".trimEnd(' ', '•'),
                    artworkUrl = api.artwork.moviePoster(m.id),
                    onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                )
            }
            items(showHits, key = { "s-${it.id}" }) { s ->
                SimpleTrackRow(
                    title = s.title,
                    subtitle = "TV Show • ${s.episodeCount} episodes",
                    artworkUrl = api.artwork.showPoster(s.id),
                    onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                )
            }
            items(ytHits, key = { "y-${it.id}" }) { y ->
                SimpleTrackRow(
                    title = y.title,
                    subtitle = "YouTube • ${y.channelTitle ?: ""}",
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
    }
}

@Composable
fun SettingsScreen(
    record: PairedServerRecord,
    api: PlayerApiClient,
    onUnpair: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(PlayerSettings()) }

    LaunchedEffect(api) {
        (api.fetchSettings() as? MusicApiResult.Success)?.let { settings = it.data }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Player Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connected Server", style = MaterialTheme.typography.titleMedium)
                    Text("Server: ${record.serverName ?: "SoulSync"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Profile: ${record.profileName}", style = MaterialTheme.typography.bodyMedium)
                    Text("Fingerprint: ${record.formattedFingerprint}", color = SoulSyncColors.AccentLight, fontSize = 13.sp)
                    Text("Connection: Direct Cert-Pinned HTTPS", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Share what I'm watching", style = MaterialTheme.typography.titleMedium)
                            Text("Show in household Watching Now", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = settings.shareWatching,
                            onCheckedChange = { checked ->
                                settings = settings.copy(shareWatching = checked)
                                scope.launch { api.saveSettings(settings) }
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Autoplay next episode", style = MaterialTheme.typography.titleMedium)
                            Text("Continue automatically when an episode ends", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = settings.autoplayNext,
                            onCheckedChange = { checked ->
                                settings = settings.copy(autoplayNext = checked)
                                scope.launch { api.saveSettings(settings) }
                            },
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onUnpair,
                colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Danger),
                modifier = Modifier.fillMaxWidth().height(48.dp).dpadFocusable(),
            ) {
                Text("Unpair This Device")
            }
        }
    }
}

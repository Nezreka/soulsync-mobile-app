package net.ssync.app.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.ArtistListeningStats
import net.ssync.player.ArtistTopTrack
import net.ssync.player.LibAlbum
import net.ssync.player.LibAlbumDetail
import net.ssync.player.LibArtistDetail
import net.ssync.player.LibTrack
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicTrackRequestResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.SongRefPayload

private fun fmtMusicBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "${"%.2f".format(mb / 1024.0)} GB" else "${"%.0f".format(mb)} MB"
}

private fun fmtMusicDuration(raw: Long?): String? {
    if (raw == null || raw <= 0L) return null
    val sec = if (raw > 10_000L) raw / 1000L else raw
    val m = sec / 60L
    val s = sec % 60L
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun fmtAlbumTotalRuntime(tracks: List<LibTrack>): String? {
    val totalSec = tracks.sumOf { t ->
        val raw = t.duration ?: 0L
        if (raw > 10_000L) raw / 1000L else raw
    }
    if (totalSec <= 0L) return null
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    return if (hrs > 0) "${hrs}h ${mins}m" else "${mins} min"
}

@Composable
private fun rememberMusicAmbientColor(artUrl: String?): Color {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    var target by remember(artUrl) { mutableStateOf(Color(0xFF141126)) }
    LaunchedEffect(artUrl) {
        if (artUrl.isNullOrBlank()) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(artUrl)
                    .size(48)
                    .allowHardware(false)
                    .build()
                val bmp = (imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                bmp?.let { extractCinemaAmbientColor(it) }
            }.getOrNull()
        }
        if (extracted != null) target = extracted
    }
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "musicDetailAmbient",
    )
    return animated
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberCinemaViewportSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val padPx = with(density) { 40.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                val bottom = containerSize - padPx
                return when {
                    offset < padPx -> offset - padPx
                    offset + size > bottom -> (offset + size) - bottom
                    else -> 0f
                }
            }
        }
    }
}

@Composable
private fun CinemaMusicActionPill(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        primary -> Color.White.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = if (focused || primary) Color(0xFF0A0C14) else Color.White
    val borderColor = when {
        focused -> Color.White
        primary -> Color.White
        else -> Color.White.copy(alpha = 0.24f)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.2.dp, borderColor, CircleShape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text = label, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun CinemaMusicTagChip(
    label: String,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        accent -> Color(0x2E34D399)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val fg = when {
        focused -> Color(0xFF0A0C14)
        accent -> Color(0xFF6EE7B7)
        else -> Color.White.copy(alpha = 0.88f)
    }
    val border = when {
        focused -> Color.White
        accent -> Color(0x6634D399)
        else -> Color.White.copy(alpha = 0.18f)
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, border, CircleShape)
            .then(
                if (onClick != null) {
                    Modifier
                        .onFocusChanged { focused = it.isFocused || it.hasFocus }
                        .clickable(onClick = onClick)
                } else Modifier,
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ── Cinema Artist Detail Screen (Plexamp-Beating, TV + Mobile) ────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CinemaArtistDetailScreen(
    artistId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val warmHit = HomeWarmCache.baseUrl == api.baseUrl && HomeWarmCache.artistDetails.containsKey(artistId)
    var artist by remember(artistId, api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.artistDetails[artistId] else null) }
    var isLoading by remember(artistId, api.baseUrl) { mutableStateOf(!warmHit) }
    var topTracks by remember(artistId) { mutableStateOf<List<ArtistTopTrack>>(emptyList()) }
    var yourPlays by remember(artistId) { mutableStateOf<ArtistListeningStats?>(null) }
    var similarAlbums by remember(artistId) { mutableStateOf<List<LibAlbum>>(emptyList()) }
    var bioExpanded by remember(artistId) { mutableStateOf(false) }

    LaunchedEffect(artistId, api) {
        if (!warmHit) isLoading = true
        coroutineScope {
            launch {
                when (val r = api.fetchArtistDetail(artistId)) {
                    is MusicApiResult.Success -> {
                        artist = r.data
                        HomeWarmCache.artistDetails[artistId] = r.data
                        isLoading = false
                        // Pull same-genre albums by OTHER artists in library for "Fans Also Like"
                        val primaryGenre = (r.data.genres + r.data.about?.tags.orEmpty())
                            .map { it.trim() }
                            .firstOrNull { it.isNotBlank() }
                        if (primaryGenre != null) {
                            launch {
                                val gAlbums = (api.fetchAlbums(limit = 32, page = 1, genre = primaryGenre) as? MusicApiResult.Success)
                                    ?.data.orEmpty()
                                    .filter { it.artistId != r.data.id && !it.artistName.equals(r.data.name, ignoreCase = true) }
                                    .distinctBy { it.artistId ?: it.id }
                                    .take(14)
                                similarAlbums = gAlbums
                            }
                        }
                    }
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> Unit
                }
                isLoading = false
            }
            launch {
                (api.fetchArtistTopTracks(artistId, limit = 10) as? MusicApiResult.Success)?.let {
                    topTracks = it.data
                }
            }
            launch {
                (api.fetchArtistYourPlays(artistId) as? MusicApiResult.Success)?.let {
                    yourPlays = it.data
                }
            }
        }
    }

    val a = artist
    if (isLoading && a == null) {
        LoadingBox()
        return
    }
    if (a == null) {
        EmptyBox("Artist not found.")
        return
    }

    val heroUrl = api.artwork.resolve(a.imageUrl ?: a.remoteImageUrl)
        ?: a.albums.firstOrNull()?.let { api.artwork.resolve(it.imageUrl ?: it.remoteImageUrl) ?: api.artwork.albumArtwork(it.id) }
    val ambient = rememberMusicAmbientColor(heroUrl)
    val bio = a.about?.bio ?: a.summary
    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId
    val sideStart = if (isTv) 78.dp else 18.dp
    val sideEnd = if (isTv) 32.dp else 18.dp

    fun playArtistTracks(shuffle: Boolean) {
        if (topTracks.isNotEmpty()) {
            val base = topTracks.map { t ->
                ActivePlayable(
                    itemType = "track",
                    itemId = t.id.toString(),
                    title = t.title,
                    subtitle = t.artist.ifBlank { a.name },
                    artworkUrl = api.artwork.resolve(t.imageUrl) ?: t.albumId?.let { api.artwork.albumArtwork(it) } ?: heroUrl,
                    streamUrl = api.streamUrl("track", t.id.toString()),
                    parentId = t.albumId?.toString(),
                )
            }
            val list = if (shuffle) base.shuffled() else base
            coordinator.playAudio(list.first(), list.drop(1))
        } else {
            val firstOwned = (a.albums + a.eps + a.singles).firstOrNull { it.tracksPresent > 0 || it.trackCount > 0 }
            if (firstOwned != null) {
                scope.launch {
                    val det = (api.fetchAlbumDetail(firstOwned.id) as? MusicApiResult.Success)?.data ?: return@launch
                    val cover = api.artwork.resolve(det.imageUrl) ?: api.artwork.albumArtwork(det.id)
                    val list = det.tracks.filter { it.isOwned }.map { tr ->
                        ActivePlayable(
                            itemType = "track",
                            itemId = tr.id.toString(),
                            title = tr.title,
                            subtitle = a.name,
                            artworkUrl = cover,
                            streamUrl = api.streamUrl("track", tr.id.toString()),
                            parentId = det.id.toString(),
                        )
                    }.let { if (shuffle) it.shuffled() else it }
                    if (list.isNotEmpty()) coordinator.playAudio(list.first(), list.drop(1))
                }
            }
        }
    }

    fun startArtistRadio() {
        scope.launch {
            val pool = mutableListOf<ActivePlayable>()
            topTracks.forEach { t ->
                pool += ActivePlayable(
                    itemType = "track",
                    itemId = t.id.toString(),
                    title = t.title,
                    subtitle = t.artist.ifBlank { a.name },
                    artworkUrl = api.artwork.resolve(t.imageUrl) ?: t.albumId?.let { api.artwork.albumArtwork(it) } ?: heroUrl,
                    streamUrl = api.streamUrl("track", t.id.toString()),
                    parentId = t.albumId?.toString(),
                )
            }
            similarAlbums.take(4).forEach { sim ->
                val det = (api.fetchAlbumDetail(sim.id) as? MusicApiResult.Success)?.data ?: return@forEach
                val cover = api.artwork.resolve(det.imageUrl) ?: api.artwork.albumArtwork(det.id)
                det.tracks.filter { it.isOwned }.take(3).forEach { tr ->
                    pool += ActivePlayable(
                        itemType = "track",
                        itemId = tr.id.toString(),
                        title = tr.title,
                        subtitle = det.primaryArtist?.name ?: sim.artistName.orEmpty(),
                        artworkUrl = cover,
                        streamUrl = api.streamUrl("track", tr.id.toString()),
                        parentId = det.id.toString(),
                    )
                }
            }
            val shuffled = pool.distinctBy { it.itemId }.shuffled()
            if (shuffled.isNotEmpty()) coordinator.playAudio(shuffled.first(), shuffled.drop(1))
        }
    }

    val viewportSpec = rememberCinemaViewportSpec()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambient),
    ) {
        // Feathered full-bleed artist backdrop anchored top-right (TV) or top (Mobile)
        if (!heroUrl.isNullOrBlank()) {
            AsyncImage(
                model = heroUrl,
                imageLoader = LocalSoulSyncImageLoader.current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = if (isTv) {
                    Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(0.68f).alpha(0.45f)
                } else {
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(340.dp).alpha(0.38f)
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to ambient,
                        0.38f to ambient.copy(alpha = 0.88f),
                        0.72f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.20f),
                        0.42f to ambient.copy(alpha = 0.84f),
                        0.68f to Color(0xFF0A0C16),
                        1.0f to Color(0xFF080A12),
                    ),
                ),
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides viewportSpec) {
            LazyColumn(
                contentPadding = PaddingValues(top = if (isTv) 28.dp else 68.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. Hero Header (TV landscape split vs Mobile centered)
                item(key = "artist-hero") {
                    val releaseSummary = buildList {
                        if (a.albums.isNotEmpty()) add("${a.albums.size} ${if (a.albums.size == 1) "Album" else "Albums"}")
                        if (a.eps.isNotEmpty()) add("${a.eps.size} ${if (a.eps.size == 1) "EP" else "EPs"}")
                        if (a.singles.isNotEmpty()) add("${a.singles.size} ${if (a.singles.size == 1) "Single" else "Singles"}")
                        fmtMusicBytes(a.totalSizeBytes)?.let { add(it) }
                    }.joinToString(" · ")

                    if (isTv) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = sideStart, end = sideEnd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(144.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(2.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                            ) {
                                SoulSyncArtwork(
                                    url = heroUrl,
                                    contentDescription = a.name,
                                    fallbackIcon = Icons.Default.Person,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "ARTIST",
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.4.sp,
                                )
                                Text(
                                    text = a.name,
                                    color = Color.White,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 44.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (releaseSummary.isNotBlank()) {
                                    Text(
                                        text = releaseSummary,
                                        color = Color.White.copy(alpha = 0.78f),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (a.genres.isNotEmpty() || !a.style.isNullOrBlank()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        a.genres.take(4).forEach { g ->
                                            CinemaMusicTagChip(
                                                label = g,
                                                onClick = { onNavigate(PlayerDestination.SearchQuery(g)) },
                                            )
                                        }
                                        a.style?.takeIf { it.isNotBlank() }?.let {
                                            CinemaMusicTagChip(label = it)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CinemaMusicActionPill(
                                        label = "Play Top",
                                        icon = Icons.Default.PlayArrow,
                                        primary = true,
                                        onClick = { playArtistTracks(shuffle = false) },
                                    )
                                    CinemaMusicActionPill(
                                        label = "Shuffle",
                                        icon = Icons.Default.Shuffle,
                                        onClick = { playArtistTracks(shuffle = true) },
                                    )
                                    CinemaMusicActionPill(
                                        label = "Artist Radio",
                                        icon = Icons.Default.Radio,
                                        onClick = { startArtistRadio() },
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(138.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(2.dp, Color.White.copy(alpha = 0.26f), CircleShape),
                            ) {
                                SoulSyncArtwork(
                                    url = heroUrl,
                                    contentDescription = a.name,
                                    fallbackIcon = Icons.Default.Person,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Text(
                                text = a.name,
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (releaseSummary.isNotBlank()) {
                                Text(
                                    text = releaseSummary,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (a.genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    a.genres.take(4).forEach { g ->
                                        CinemaMusicTagChip(
                                            label = g,
                                            onClick = { onNavigate(PlayerDestination.SearchQuery(g)) },
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                CinemaMusicActionPill(
                                    label = "Play",
                                    icon = Icons.Default.PlayArrow,
                                    primary = true,
                                    onClick = { playArtistTracks(shuffle = false) },
                                )
                                CinemaMusicActionPill(
                                    label = "Shuffle",
                                    icon = Icons.Default.Shuffle,
                                    onClick = { playArtistTracks(shuffle = true) },
                                )
                                CinemaMusicActionPill(
                                    label = "Radio",
                                    icon = Icons.Default.Radio,
                                    onClick = { startArtistRadio() },
                                )
                            }
                        }
                    }
                }

                // the artist's albums in one go (send to a phone on a tv)
                item(key = "artist-download") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = sideStart, end = sideEnd),
                        horizontalArrangement = if (isTv) Arrangement.Start else Arrangement.Center,
                    ) {
                        GroupDownloadButton(
                            api = api,
                            target = DownloadTarget(
                                type = "artist",
                                id = a.id.toString(),
                                title = a.name,
                                artwork = relativeArt(heroUrl),
                                groupTitle = a.name,
                            ),
                        )
                    }
                }

                // 2. "You & [Artist]" Personal Listening Card (beats Plexamp!)
                yourPlays?.takeIf { it.playCount > 0 }?.let { stats ->
                    item(key = "artist-your-plays") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = sideStart, end = sideEnd)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "You & ${a.name}",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                stats.lastPlayed?.take(10)?.let { lp ->
                                    Text(
                                        text = "Last played $lp",
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 11.5.sp,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ArtistStatTile(
                                    label = "TOTAL PLAYS",
                                    value = stats.playCount.toString(),
                                    modifier = Modifier.weight(1f),
                                )
                                ArtistStatTile(
                                    label = "LAST 30 DAYS",
                                    value = stats.plays30d.toString(),
                                    modifier = Modifier.weight(1f),
                                )
                                stats.rank?.let { rk ->
                                    ArtistStatTile(
                                        label = "YOUR RANK",
                                        value = "#$rk${stats.artistsPlayed?.let { " / $it" }.orEmpty()}",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (stats.months.any { it.plays > 0 }) {
                                val maxM = (stats.months.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "12-MONTH LISTENING ACTIVITY",
                                        color = Color.White.copy(alpha = 0.50f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.9.sp,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.Bottom,
                                    ) {
                                        stats.months.forEach { m ->
                                            val frac = (m.plays.toFloat() / maxM.toFloat()).coerceIn(0.08f, 1f)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight(frac)
                                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                                    .background(
                                                        if (m.plays > 0) Color.White.copy(alpha = 0.78f)
                                                        else Color.White.copy(alpha = 0.12f),
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Popular Tracks (ranked playable list!)
                if (topTracks.isNotEmpty()) {
                    item(key = "artist-popular-header") {
                        Text(
                            text = "Popular Tracks",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(start = sideStart, end = sideEnd),
                        )
                    }
                    itemsIndexed(topTracks.take(8), key = { idx, t -> "top-$idx-${t.id}" }) { idx, tr ->
                        val isPlaying = activeTrackId == tr.id.toString()
                        val art = api.artwork.resolve(tr.imageUrl) ?: tr.albumId?.let { api.artwork.albumArtwork(it) } ?: heroUrl
                        var rowFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = sideStart, end = sideEnd)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        rowFocused -> Color.White
                                        isPlaying -> Color.White.copy(alpha = 0.14f)
                                        else -> Color.White.copy(alpha = 0.04f)
                                    },
                                )
                                .onFocusChanged { rowFocused = it.isFocused || it.hasFocus }
                                .clickable {
                                    val queue = topTracks.map { q ->
                                        ActivePlayable(
                                            itemType = "track",
                                            itemId = q.id.toString(),
                                            title = q.title,
                                            subtitle = q.artist.ifBlank { a.name },
                                            artworkUrl = api.artwork.resolve(q.imageUrl) ?: q.albumId?.let { api.artwork.albumArtwork(it) } ?: heroUrl,
                                            streamUrl = api.streamUrl("track", q.id.toString()),
                                            parentId = q.albumId?.toString(),
                                        )
                                    }
                                    coordinator.playAudio(queue[idx], queue.drop(idx + 1))
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                if (isPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Playing",
                                        tint = if (rowFocused) Color(0xFF0A0C14) else Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(
                                        text = "${idx + 1}",
                                        color = if (rowFocused) Color(0xFF475569) else Color.White.copy(alpha = 0.55f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            SoulSyncArtwork(
                                url = art,
                                contentDescription = tr.title,
                                fallbackIcon = Icons.Default.MusicNote,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr.title,
                                    color = if (rowFocused) Color(0xFF0A0C14) else Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = buildList {
                                    tr.album?.takeIf { it.isNotBlank() }?.let { add(it) }
                                    if (tr.playCount > 0) add("${tr.playCount} plays")
                                }.joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        text = sub,
                                        color = if (rowFocused) Color(0xFF475569) else Color.White.copy(alpha = 0.62f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            fmtMusicDuration(tr.durationMs)?.let { dur ->
                                Text(
                                    text = dur,
                                    color = if (rowFocused) Color(0xFF475569) else Color.White.copy(alpha = 0.62f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // 4. Discography Rails (Albums, EPs, Singles, Appears On)
                if (a.albums.isNotEmpty()) {
                    item(key = "artist-albums") {
                        CinemaDiscographyRail(
                            title = "Albums",
                            releases = a.albums,
                            api = api,
                            startPad = sideStart,
                            endPad = sideEnd,
                            onAlbumClick = { onNavigate(PlayerDestination.AlbumDetail(it)) },
                        )
                    }
                }
                if (a.eps.isNotEmpty()) {
                    item(key = "artist-eps") {
                        CinemaDiscographyRail(
                            title = "EPs",
                            releases = a.eps,
                            api = api,
                            startPad = sideStart,
                            endPad = sideEnd,
                            onAlbumClick = { onNavigate(PlayerDestination.AlbumDetail(it)) },
                        )
                    }
                }
                if (a.singles.isNotEmpty()) {
                    item(key = "artist-singles") {
                        CinemaDiscographyRail(
                            title = "Singles",
                            releases = a.singles,
                            api = api,
                            startPad = sideStart,
                            endPad = sideEnd,
                            onAlbumClick = { onNavigate(PlayerDestination.AlbumDetail(it)) },
                        )
                    }
                }
                if (a.appearsOn.isNotEmpty()) {
                    item(key = "artist-appears-on") {
                        CinemaDiscographyRail(
                            title = "Appears On",
                            releases = a.appearsOn,
                            api = api,
                            startPad = sideStart,
                            endPad = sideEnd,
                            onAlbumClick = { onNavigate(PlayerDestination.AlbumDetail(it)) },
                        )
                    }
                }

                // 5. Fans Also Like (In Your Library)
                if (similarAlbums.size >= 2) {
                    item(key = "artist-similar") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Fans Also Like · In Your Library",
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(start = sideStart, end = sideEnd),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(start = sideStart, end = sideEnd),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(similarAlbums, key = { "sim-al-${it.id}" }) { sim ->
                                    val simArt = api.artwork.resolve(sim.imageUrl ?: sim.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(sim.id)
                                    AlbumGridTile(
                                        title = sim.artistName?.takeIf { it.isNotBlank() } ?: sim.title,
                                        subtitle = sim.title,
                                        artworkUrl = simArt,
                                        tracksPresent = sim.tracksPresent,
                                        trackCount = sim.trackCount,
                                        modifier = Modifier.width(if (isTv) 156.dp else 138.dp),
                                        onClick = {
                                            if (sim.artistId != null) {
                                                onNavigate(PlayerDestination.ArtistDetail(sim.artistId!!))
                                            } else {
                                                onNavigate(PlayerDestination.AlbumDetail(sim.id))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. About [Artist] Bento Card
                if (!bio.isNullOrBlank() || !a.label.isNullOrBlank() || !a.mood.isNullOrBlank() || a.about?.tags.orEmpty().isNotEmpty()) {
                    item(key = "artist-about") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = sideStart, end = sideEnd)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                                .clickable { bioExpanded = !bioExpanded }
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "About ${a.name}",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            if (!bio.isNullOrBlank()) {
                                Text(
                                    text = bio,
                                    color = Color.White.copy(alpha = 0.76f),
                                    fontSize = 13.5.sp,
                                    lineHeight = 20.sp,
                                    maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bio.length > 180) {
                                    Text(
                                        text = if (bioExpanded) "Show less" else "Read full biography",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            val tags = (a.about?.tags.orEmpty() + listOfNotNull(a.mood, a.label))
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinctBy { it.lowercase() }
                                .take(8)
                            if (tags.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    tags.forEach { tag ->
                                        CinemaMusicTagChip(
                                            label = "#$tag",
                                            onClick = { onNavigate(PlayerDestination.SearchQuery(tag)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.52f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CinemaDiscographyRail(
    title: String,
    releases: List<LibAlbum>,
    api: PlayerApiClient,
    startPad: androidx.compose.ui.unit.Dp,
    endPad: androidx.compose.ui.unit.Dp,
    onAlbumClick: (Int) -> Unit,
) {
    val isTv = LocalIsTv.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = startPad, end = endPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "${releases.size}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(start = startPad, end = endPad),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(releases, key = { it.id }) { album ->
                val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                    ?: api.artwork.albumArtwork(album.id)
                AlbumGridTile(
                    title = album.title,
                    subtitle = listOfNotNull(
                        album.year?.toString(),
                        if (album.trackCount > 0) "${album.trackCount} tracks" else null,
                    ).joinToString(" · "),
                    artworkUrl = alArt,
                    tracksPresent = album.tracksPresent,
                    trackCount = album.trackCount,
                    contextItem = MediaContextItem(
                        itemType = "album",
                        itemId = album.id.toString(),
                        title = album.title,
                        subtitle = album.artistName.orEmpty(),
                        artworkUrl = alArt,
                        isVideo = false,
                        onOpenDetails = { onAlbumClick(album.id) },
                    ),
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.width(if (isTv) 162.dp else 144.dp),
                )
            }
        }
    }
}

// ── Cinema Album Detail Screen (Plexamp-Beating, TV + Mobile) ─────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CinemaAlbumDetailScreen(
    albumId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit,
    highlightTrackId: Int? = null,
) {
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val warmHit = HomeWarmCache.baseUrl == api.baseUrl && HomeWarmCache.albumDetails.containsKey(albumId)
    var album by remember(albumId, api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.albumDetails[albumId] else null) }
    var isLoading by remember(albumId, api.baseUrl) { mutableStateOf(!warmHit) }
    var loadError by remember(albumId) { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }
    var moreByArtist by remember(albumId) { mutableStateOf<List<LibAlbum>>(emptyList()) }
    val likedState = rememberLikedTracksState(api)

    LaunchedEffect(albumId, api, reloadNonce) {
        if (!warmHit) isLoading = true
        loadError = null
        when (val r = api.fetchAlbumDetail(albumId, resolve = true)) {
            is MusicApiResult.Success -> {
                album = r.data
                HomeWarmCache.albumDetails[albumId] = r.data
                isLoading = false
                val aid = r.data.primaryArtist?.id
                if (aid != null) {
                    launch {
                        val aDet = HomeWarmCache.artistDetails[aid]
                            ?: (api.fetchArtistDetail(aid) as? MusicApiResult.Success)?.data?.also {
                                HomeWarmCache.artistDetails[aid] = it
                            }
                        if (aDet != null) {
                            moreByArtist = (aDet.albums + aDet.eps)
                                .filter { it.id != albumId }
                                .distinctBy { it.id }
                                .take(16)
                        }
                    }
                }
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> loadError = r.message
        }
        isLoading = false
        // the server is fetching the release's real tracklist: refresh until missing songs have names
        if (album?.isTracklistPending == true) {
            api.pollAlbumTracklist(albumId, onDeviceRevoked = onDeviceRevoked) {
                album = it
                HomeWarmCache.albumDetails[albumId] = it
            }
        }
    }

    val al = album
    if (isLoading && al == null) {
        LoadingBox()
        return
    }
    if (al == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyBox(loadError ?: "Album not found.")
            if (loadError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { reloadNonce += 1 },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                ) {
                    Text("Retry", color = Color.White)
                }
            }
        }
        return
    }

    val reqState = remember(albumId) { mutableStateMapOf<String, String>() }
    var requestingAll by remember { mutableStateOf(false) }

    val coverUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id)
    val ambient = rememberMusicAmbientColor(coverUrl)
    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId
    val sideStart = if (isTv) 78.dp else 18.dp
    val sideEnd = if (isTv) 32.dp else 18.dp

    val playableTracks = remember(al.tracks, coverUrl) {
        al.tracks.filter { it.isOwned }.map { t ->
            ActivePlayable(
                itemType = "track",
                itemId = t.id.toString(),
                title = t.title,
                subtitle = al.primaryArtist?.name ?: al.title,
                artworkUrl = coverUrl,
                streamUrl = api.streamUrl("track", t.id.toString()),
                parentId = al.id.toString(),
            )
        }
    }
    val missingEntries = al.tracks.mapIndexedNotNull { idx, t ->
        if (t.isRequestable) Triple(idx, t, "${t.id ?: "m-$idx"}") else null
    }
    val unrequestedMissing = missingEntries.filter { (_, _, key) ->
        val st = reqState[key]
        st == null || st == "error"
    }
    val distinctDiscs = al.tracks.mapNotNull { it.discNumber }.distinct()
    val showDiscHeaders = distinctDiscs.size > 1
    val totalDuration = fmtAlbumTotalRuntime(al.tracks)

    // Audio quality rollup from owned track files (Plexamp-grade Lossless / Hi-Res / Bitrate badge!)
    val audioBadge = remember(al.tracks) {
        val files = al.tracks.mapNotNull { it.file }
        val fmt = files.mapNotNull { it.format?.uppercase()?.takeIf { f -> f.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val avgBr = files.mapNotNull { it.bitrate?.takeIf { b -> b > 0 } }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val isLossless = fmt in setOf("FLAC", "ALAC", "WAV", "AIFF", "DSD")
        if (fmt != null) {
            val brLabel = avgBr?.let { if (it > 10_000) "${it / 1000} kbps" else "$it kbps" }
            val text = listOfNotNull(fmt, brLabel).joinToString(" · ")
            text to isLossless
        } else null
    }

    suspend fun requestTrack(idx: Int, t: LibTrack, key: String) {
        // an untitled track would send an empty title: wait for the real tracklist
        if (!t.isRequestable) return
        val cur = reqState[key]
        if (cur == "sending" || cur == "wishlist" || cur == "requested" || cur == "owned") return
        reqState[key] = "sending"
        when (val r = api.requestMusicTrack(
            title = t.title,
            artist = al.primaryArtist?.name ?: "Unknown Artist",
            album = al.title,
            albumId = al.id,
            artistId = al.primaryArtist?.id,
            trackId = t.id,
            trackNumber = t.trackNumber ?: (idx + 1),
            discNumber = t.discNumber ?: 1,
            durationMs = t.duration,
            year = al.year,
            imageUrl = al.imageUrl,
        )) {
            is MusicApiResult.Success -> reqState[key] = when (r.data) {
                MusicTrackRequestResult.Wishlist -> "wishlist"
                MusicTrackRequestResult.Requested -> "requested"
                MusicTrackRequestResult.Owned -> "owned"
                is MusicTrackRequestResult.Error -> "error"
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> reqState[key] = "error"
        }
    }

    val albumListState = rememberLazyListState()
    LaunchedEffect(al.tracks, highlightTrackId) {
        if (highlightTrackId != null) {
            val idx = al.tracks.indexOfFirst { it.id == highlightTrackId }
            if (idx >= 0) albumListState.animateScrollToItem(idx + 1)
        }
    }

    val viewportSpec = rememberCinemaViewportSpec()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambient),
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                imageLoader = LocalSoulSyncImageLoader.current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = if (isTv) {
                    Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(0.68f).alpha(0.36f)
                } else {
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(380.dp).alpha(0.32f)
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to ambient,
                        0.40f to ambient.copy(alpha = 0.88f),
                        0.76f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.42f to ambient.copy(alpha = 0.86f),
                        0.72f to Color(0xFF0A0C16),
                        1.0f to Color(0xFF080A12),
                    ),
                ),
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides viewportSpec) {
            LazyColumn(
                state = albumListState,
                contentPadding = PaddingValues(top = if (isTv) 28.dp else 68.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. Hero Stage (TV split vs Mobile centered Plexamp hero)
                item(key = "album-hero") {
                    val metaParts = buildList {
                        al.albumType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }?.let { add(it) }
                        al.year?.let { add(it.toString()) }
                        if (playableTracks.size == al.tracks.size) {
                            add("${al.tracks.size} tracks")
                        } else {
                            add("${playableTracks.size}/${al.tracks.size} tracks")
                        }
                        totalDuration?.let { add(it) }
                        fmtMusicBytes(al.totalSizeBytes)?.let { add(it) }
                    }
                    val artistName = al.primaryArtist?.name
                    val artistRefId = al.primaryArtist?.id

                    if (isTv) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = sideStart, end = sideEnd, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(26.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(216.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(18.dp)),
                            ) {
                                SoulSyncArtwork(
                                    url = coverUrl,
                                    contentDescription = al.title,
                                    fallbackIcon = Icons.Default.MusicNote,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = al.title,
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 40.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!artistName.isNullOrBlank()) {
                                    Text(
                                        text = artistName,
                                        color = Color.White.copy(alpha = 0.88f),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = artistRefId != null) {
                                                artistRefId?.let { onNavigate(PlayerDestination.ArtistDetail(it)) }
                                            }
                                            .padding(vertical = 2.dp),
                                    )
                                }
                                Text(
                                    text = metaParts.joinToString(" · "),
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    audioBadge?.let { (text, isLossless) ->
                                        CinemaMusicTagChip(label = if (isLossless) "LOSSLESS · $text" else text, accent = isLossless)
                                    }
                                    al.label?.takeIf { it.isNotBlank() }?.let { CinemaMusicTagChip(label = it) }
                                    al.genres.take(3).forEach { g ->
                                        CinemaMusicTagChip(label = g, onClick = { onNavigate(PlayerDestination.SearchQuery(g)) })
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (playableTracks.isNotEmpty()) {
                                        CinemaMusicActionPill(
                                            label = "Play Album",
                                            icon = Icons.Default.PlayArrow,
                                            primary = true,
                                            onClick = { coordinator.playAudio(playableTracks.first(), playableTracks.drop(1)) },
                                        )
                                        CinemaMusicActionPill(
                                            label = "Shuffle",
                                            icon = Icons.Default.Shuffle,
                                            onClick = {
                                                val s = playableTracks.shuffled()
                                                coordinator.playAudio(s.first(), s.drop(1))
                                            },
                                        )
                                    }
                                    if (artistRefId != null) {
                                        CinemaMusicActionPill(
                                            label = "Artist",
                                            icon = Icons.Default.Person,
                                            onClick = { onNavigate(PlayerDestination.ArtistDetail(artistRefId)) },
                                        )
                                    }
                                    if (unrequestedMissing.isNotEmpty()) {
                                        CinemaMusicActionPill(
                                            label = if (requestingAll) "Requesting…" else "Get Missing (${unrequestedMissing.size})",
                                            icon = Icons.Default.Download,
                                            onClick = {
                                                if (!requestingAll) {
                                                    scope.launch {
                                                        requestingAll = true
                                                        for ((i, t, key) in unrequestedMissing) requestTrack(i, t, key)
                                                        requestingAll = false
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(224.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp)),
                            ) {
                                SoulSyncArtwork(
                                    url = coverUrl,
                                    contentDescription = al.title,
                                    fallbackIcon = Icons.Default.MusicNote,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = al.title,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!artistName.isNullOrBlank()) {
                                Text(
                                    text = artistName,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = artistRefId != null) {
                                            artistRefId?.let { onNavigate(PlayerDestination.ArtistDetail(it)) }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = metaParts.joinToString(" · "),
                                color = Color.White.copy(alpha = 0.68f),
                                fontSize = 12.5.sp,
                                textAlign = TextAlign.Center,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                audioBadge?.let { (text, isLossless) ->
                                    CinemaMusicTagChip(label = if (isLossless) "LOSSLESS · $text" else text, accent = isLossless)
                                }
                                al.genres.take(3).forEach { g ->
                                    CinemaMusicTagChip(label = g, onClick = { onNavigate(PlayerDestination.SearchQuery(g)) })
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                if (playableTracks.isNotEmpty()) {
                                    CinemaMusicActionPill(
                                        label = "Play",
                                        icon = Icons.Default.PlayArrow,
                                        primary = true,
                                        onClick = { coordinator.playAudio(playableTracks.first(), playableTracks.drop(1)) },
                                    )
                                    CinemaMusicActionPill(
                                        label = "Shuffle",
                                        icon = Icons.Default.Shuffle,
                                        onClick = {
                                            val s = playableTracks.shuffled()
                                            coordinator.playAudio(s.first(), s.drop(1))
                                        },
                                    )
                                }
                                if (unrequestedMissing.isNotEmpty()) {
                                    CinemaMusicActionPill(
                                        label = if (requestingAll) "…" else "Get (${unrequestedMissing.size})",
                                        icon = Icons.Default.Download,
                                        onClick = {
                                            if (!requestingAll) {
                                                scope.launch {
                                                    requestingAll = true
                                                    for ((i, t, key) in unrequestedMissing) requestTrack(i, t, key)
                                                    requestingAll = false
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Tracklist Header (with the whole album's download, or send to a phone on a tv)
                item(key = "album-tracks-head") {
                    if (playableTracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = sideStart, end = sideEnd, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${playableTracks.size} ${if (playableTracks.size == 1) "song" else "songs"}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            GroupDownloadButton(
                                api = api,
                                target = DownloadTarget(
                                    type = "album",
                                    id = al.id.toString(),
                                    title = al.title,
                                    subtitle = al.primaryArtist?.name.orEmpty(),
                                    artwork = relativeArt(coverUrl),
                                    groupTitle = al.title,
                                ),
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = sideStart, end = sideEnd, top = 6.dp, bottom = 6.dp),
                        thickness = 0.75.dp,
                        color = Color.White.copy(alpha = 0.12f),
                    )
                }

                // 3. Track Rows (with multi-disc headers, quality pills, Like, Playlist & Request)
                itemsIndexed(al.tracks, key = { idx, t -> "${t.id ?: "m-$idx"}-${t.discNumber}-${t.trackNumber}" }) { idx, tr ->
                    val prevDisc = if (idx > 0) al.tracks[idx - 1].discNumber else null
                    val thisDisc = tr.discNumber ?: 1
                    val showDisc = showDiscHeaders && (idx == 0 || thisDisc != prevDisc)
                    val owned = tr.isOwned
                    val isPlaying = owned && activeTrackId == tr.id.toString()
                    val isHighlighted = highlightTrackId != null && tr.id == highlightTrackId
                    val trackKey = "${tr.id ?: "m-$idx"}"
                    val rStatus = reqState[trackKey]
                    val songRef = if (owned && tr.id != null) {
                        SongRefPayload(
                            title = tr.title,
                            artist = al.primaryArtist?.name ?: "Unknown Artist",
                            album = al.title,
                            durationMs = tr.duration,
                            lib2TrackId = tr.id,
                        )
                    } else null
                    val pItem = if (owned && songRef != null) {
                        ActivePlayable(
                            itemType = "track",
                            itemId = tr.id.toString(),
                            title = tr.title,
                            subtitle = al.primaryArtist?.name ?: al.title,
                            artworkUrl = coverUrl,
                            streamUrl = api.streamUrl("track", tr.id.toString()),
                            parentId = al.id.toString(),
                        )
                    } else null
                    val openContext = LocalMediaContextMenu.current

                    Column(modifier = Modifier.padding(start = sideStart, end = sideEnd)) {
                        if (showDisc) {
                            Text(
                                text = "DISC $thisDisc",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 8.dp),
                            )
                        }
                        var rowFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        rowFocused -> Color.White
                                        isPlaying || isHighlighted -> Color.White.copy(alpha = 0.14f)
                                        else -> Color.White.copy(alpha = 0.03f)
                                    },
                                )
                                .onFocusChanged { rowFocused = it.isFocused || it.hasFocus }
                                .combinedClickable(
                                    enabled = owned,
                                    onClick = {
                                        val playIdx = playableTracks.indexOfFirst { it.itemId == tr.id.toString() }
                                        if (playIdx >= 0) {
                                            coordinator.playAudio(playableTracks[playIdx], playableTracks.drop(playIdx + 1))
                                        }
                                    },
                                    // a long press, or holding select on a tv
                                    onLongClick = pItem?.let { p -> { openContext(p.toContextItem(songRef)) } },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                                if (isPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Playing",
                                        tint = if (rowFocused) Color(0xFF0A0C14) else Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(
                                        text = "${tr.trackNumber ?: (idx + 1)}",
                                        color = if (rowFocused) Color(0xFF475569) else Color.White.copy(alpha = if (owned) 0.56f else 0.30f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = tr.displayTitle(idx + 1),
                                        color = if (rowFocused) Color(0xFF0A0C14) else Color.White.copy(alpha = if (owned) 1f else 0.45f),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (tr.explicit) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (rowFocused) Color(0x22000000) else Color.White.copy(alpha = 0.14f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = "E",
                                                color = if (rowFocused) Color(0xFF0A0C14) else Color.White.copy(alpha = 0.78f),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                            )
                                        }
                                    }
                                    tr.file?.format?.uppercase()?.takeIf { it.isNotBlank() }?.let { fmt ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (rowFocused) Color(0x18000000) else Color.White.copy(alpha = 0.08f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = fmt,
                                                color = if (rowFocused) Color(0xFF334155) else Color.White.copy(alpha = 0.58f),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                            if (pItem != null && songRef != null) {
                                TrackRowActionIcons(
                                    song = songRef,
                                    playable = pItem,
                                    likedState = likedState,
                                )
                            }
                            if (!owned && (tr.isRequestable || rStatus != null)) {
                                val badgeLabel = when (rStatus) {
                                    "sending" -> "Requesting…"
                                    "wishlist" -> "In Wishlist"
                                    "requested" -> "Requested"
                                    "owned" -> "Owned"
                                    else -> "Request"
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.12f),
                                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.24f)),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { scope.launch { requestTrack(idx, tr, trackKey) } },
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            fmtMusicDuration(tr.duration)?.let { dur ->
                                Text(
                                    text = dur,
                                    color = if (rowFocused) Color(0xFF475569) else Color.White.copy(alpha = if (owned) 0.62f else 0.35f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // 4. About This Release Bento Card
                val hasMetaCard = !al.label.isNullOrBlank() || !al.releaseDate.isNullOrBlank() || !al.style.isNullOrBlank() || !al.mood.isNullOrBlank() || audioBadge != null
                if (hasMetaCard) {
                    item(key = "album-about") {
                        Spacer(modifier = Modifier.height(14.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = sideStart, end = sideEnd)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "About This Release",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                al.label?.takeIf { it.isNotBlank() }?.let {
                                    ArtistStatTile(label = "RECORD LABEL", value = it, modifier = Modifier.width(200.dp))
                                }
                                (al.releaseDate?.takeIf { it.isNotBlank() } ?: al.year?.toString())?.let {
                                    ArtistStatTile(label = "RELEASED", value = it, modifier = Modifier.width(150.dp))
                                }
                                audioBadge?.let { (text, _) ->
                                    ArtistStatTile(label = "AUDIO FORMAT", value = text, modifier = Modifier.width(210.dp))
                                }
                                listOfNotNull(al.style?.takeIf { it.isNotBlank() }, al.mood?.takeIf { it.isNotBlank() })
                                    .joinToString(" · ")
                                    .takeIf { it.isNotBlank() }
                                    ?.let {
                                        ArtistStatTile(label = "STYLE & MOOD", value = it, modifier = Modifier.width(210.dp))
                                    }
                            }
                        }
                    }
                }

                // 5. More by [Artist] Rail
                if (moreByArtist.isNotEmpty() && !al.primaryArtist?.name.isNullOrBlank()) {
                    item(key = "album-more-by-artist") {
                        Spacer(modifier = Modifier.height(16.dp))
                        CinemaDiscographyRail(
                            title = "More by ${al.primaryArtist?.name}",
                            releases = moreByArtist,
                            api = api,
                            startPad = sideStart,
                            endPad = sideEnd,
                            onAlbumClick = { onNavigate(PlayerDestination.AlbumDetail(it)) },
                        )
                    }
                }
            }
        }

    }
}

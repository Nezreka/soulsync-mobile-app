package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import net.ssync.player.VideoCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class EpgProgram(
    val itemType: String,
    val itemId: String,
    val detailId: Int?,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val startEpochSec: Long,
    val endEpochSec: Long,
) {
    val durationMin: Int get() = ((endEpochSec - startEpochSec) / 60L).toInt().coerceAtLeast(15)
}

private data class EpgChannel(
    val id: String,
    val number: Int,
    val name: String,
    val category: String, // "movie" | "tv" | "music"
    val mosaicArt: List<String>,
    val programs: List<EpgProgram>,
    val musicTracks: List<Int> = emptyList(),
)

private enum class GuideViewMode(val label: String) { GRID("Grid"), ON_NOW("On now") }
private enum class GuideFilter(val label: String) { ALL("All"), FAVORITES("Favorites"), MOVIES("Movies"), TV("TV"), MUSIC("Music") }

private val PX_PER_MIN: Dp = 3.6.dp
private val CHANNEL_COL_WIDTH: Dp = 122.dp
private val ROW_HEIGHT: Dp = 68.dp

private fun buildEpgSchedule(
    catalog: VideoCatalog,
    customChannels: List<GuideChannel>,
    musicChannels: List<MusicChannel>,
    api: PlayerApiClient,
    windowStartSec: Long,
    windowEndSec: Long,
): List<EpgChannel> {
    val out = mutableListOf<EpgChannel>()
    var chNum = 101

    fun addMovieChannel(id: String, number: Int, name: String, movies: List<net.ssync.player.CatalogMovie>) {
        if (movies.isEmpty()) return
        val durations = movies.map { (it.runtimeSeconds ?: 6000).coerceAtLeast(1800).toLong() }
        val total = durations.sum().coerceAtLeast(1L)
        val anchor = (windowStartSec + number * 7919L) % total
        var cursorOffset = 0L
        var idx = 0
        while (idx < movies.lastIndex && cursorOffset + durations[idx] <= anchor) {
            cursorOffset += durations[idx]
            idx++
        }
        var t = windowStartSec - (anchor - cursorOffset)
        val progs = mutableListOf<EpgProgram>()
        var guard = 0
        while (t < windowEndSec && guard < 12) {
            val m = movies[idx % movies.size]
            val dur = durations[idx % movies.size]
            progs += EpgProgram(
                itemType = "movie",
                itemId = m.id.toString(),
                detailId = m.id,
                title = m.title,
                subtitle = listOfNotNull(m.year?.toString(), m.contentRating, m.genres.firstOrNull()).joinToString(" · "),
                artworkUrl = api.artwork.movieBackdrop(m.id),
                startEpochSec = t,
                endEpochSec = t + dur,
            )
            t += dur
            idx++
            guard++
        }
        out += EpgChannel(
            id = id,
            number = number,
            name = name,
            category = "movie",
            mosaicArt = movies.take(4).map { api.artwork.moviePoster(it.id) },
            programs = progs,
        )
    }

    if (catalog.movies.isNotEmpty()) {
        addMovieChannel("auto-premiere", chNum++, "Premiere", catalog.movies.sortedByDescending { it.addedAt.orEmpty() }.take(24))
        addMovieChannel("auto-top-rated", chNum++, "Top Rated", catalog.movies.sortedByDescending { it.rating ?: 0.0 }.take(24))
    }

    if (catalog.recentEpisodes.isNotEmpty()) {
        val eps = catalog.recentEpisodes.take(24)
        val durations = eps.map { (it.runtimeSeconds ?: 2700).coerceAtLeast(1200).toLong() }
        val total = durations.sum().coerceAtLeast(1L)
        val anchor = (windowStartSec + chNum * 7919L) % total
        var cursorOffset = 0L
        var idx = 0
        while (idx < eps.lastIndex && cursorOffset + durations[idx] <= anchor) {
            cursorOffset += durations[idx]
            idx++
        }
        var t = windowStartSec - (anchor - cursorOffset)
        val progs = mutableListOf<EpgProgram>()
        var guard = 0
        while (t < windowEndSec && guard < 16) {
            val ep = eps[idx % eps.size]
            val dur = durations[idx % eps.size]
            progs += EpgProgram(
                itemType = "episode",
                itemId = ep.id.toString(),
                detailId = ep.showId,
                title = ep.showTitle,
                subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" + (ep.title?.let { " · $it" } ?: ""),
                artworkUrl = if (ep.hasStill) api.artwork.episodeStill(ep.id) else api.artwork.showBackdrop(ep.showId),
                startEpochSec = t,
                endEpochSec = t + dur,
            )
            t += dur
            idx++
            guard++
        }
        out += EpgChannel(
            id = "auto-tv-binge",
            number = chNum++,
            name = "SoulSync TV",
            category = "tv",
            mosaicArt = eps.map { it.showId }.distinct().take(4).map { api.artwork.showPoster(it) },
            programs = progs,
        )
    }

    val topGenres = catalog.movies.flatMap { it.genres }
        .groupingBy { it }.eachCount()
        .entries.filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .take(10)
    topGenres.forEach { (genre, _) ->
        addMovieChannel("auto-genre-${genre.lowercase()}", chNum++, genre, catalog.movies.filter { it.genres.contains(genre) })
    }

    customChannels.forEach { ch ->
        if (out.none { it.id == ch.id }) {
            val type = ch.currentItemType ?: "movie"
            val id = ch.currentItemId ?: ch.id
            out += EpgChannel(
                id = ch.id,
                number = ch.number ?: chNum++,
                name = ch.name,
                category = if (ch.kind == "music") "music" else "movie",
                mosaicArt = listOfNotNull(api.artwork.forItem(type, id, preferBackdrop = false)),
                programs = listOf(
                    EpgProgram(
                        itemType = type,
                        itemId = id,
                        detailId = id.toIntOrNull(),
                        title = ch.currentTitle ?: ch.name,
                        subtitle = ch.description ?: "Custom Channel",
                        artworkUrl = api.artwork.forItem(type, id, preferBackdrop = true),
                        startEpochSec = windowStartSec,
                        endEpochSec = windowStartSec + 5400L,
                    ),
                    EpgProgram(
                        itemType = type,
                        itemId = id,
                        detailId = id.toIntOrNull(),
                        title = ch.nextTitle ?: "Up Next",
                        subtitle = ch.name,
                        artworkUrl = api.artwork.forItem(type, id, preferBackdrop = true),
                        startEpochSec = windowStartSec + 5400L,
                        endEpochSec = windowEndSec,
                    ),
                ),
            )
        }
    }

    musicChannels.forEach { mc ->
        val blocks = (0 until 8).map { i ->
            val st = windowStartSec + i * 1800L
            EpgProgram(
                itemType = "track",
                itemId = mc.tracks.getOrNull(i % mc.tracks.size.coerceAtLeast(1))?.toString() ?: mc.id,
                detailId = null,
                title = mc.name,
                subtitle = mc.description ?: "${mc.trackCount} tracks · 30 min block",
                artworkUrl = null,
                startEpochSec = st,
                endEpochSec = st + 1800L,
            )
        }
        out += EpgChannel(
            id = "music-${mc.id}",
            number = chNum++,
            name = mc.name,
            category = "music",
            mosaicArt = emptyList(),
            programs = blocks,
            musicTracks = mc.tracks,
        )
    }

    return out
}

@Composable
fun GuideScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var customChannels by remember { mutableStateOf<List<GuideChannel>>(emptyList()) }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var musicChannels by remember { mutableStateOf<List<MusicChannel>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewMode by remember { mutableStateOf(GuideViewMode.GRID) }
    var filter by remember { mutableStateOf(GuideFilter.ALL) }
    var picked by remember { mutableStateOf<Pair<EpgChannel, EpgProgram>?>(null) }
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

    val nowSec = remember { System.currentTimeMillis() / 1000L }
    // Snap window start to the current 30-minute mark, covering 4 hours ahead
    val windowStartSec = remember(nowSec) { (nowSec / 1800L) * 1800L }
    val windowMinutes = 240
    val windowEndSec = windowStartSec + windowMinutes * 60L
    val nowOffsetMin = ((nowSec - windowStartSec).coerceAtLeast(0L) / 60f)

    val allChannels = remember(catalog, customChannels, musicChannels, windowStartSec) {
        buildEpgSchedule(catalog, customChannels, musicChannels, api, windowStartSec, windowEndSec)
    }

    val shownChannels = remember(allChannels, filter, favorites) {
        when (filter) {
            GuideFilter.ALL -> allChannels
            GuideFilter.FAVORITES -> allChannels.filter { it.id in favorites }
            GuideFilter.MOVIES -> allChannels.filter { it.category == "movie" }
            GuideFilter.TV -> allChannels.filter { it.category == "tv" }
            GuideFilter.MUSIC -> allChannels.filter { it.category == "music" }
        }
    }

    fun tuneInChannel(ch: EpgChannel, prog: EpgProgram? = null) {
        val active = prog ?: ch.programs.firstOrNull { nowSec in it.startEpochSec until it.endEpochSec }
            ?: ch.programs.firstOrNull() ?: return
        if (ch.category == "music" && ch.musicTracks.isNotEmpty()) {
            val queue = ch.musicTracks.map { tid ->
                ActivePlayable(
                    itemType = "track",
                    itemId = tid.toString(),
                    title = ch.name,
                    subtitle = "CH ${ch.number} · Music Channel",
                    streamUrl = api.streamUrl("track", tid.toString()),
                )
            }
            coordinator.playAudio(queue.first(), queue.drop(1))
        } else {
            val offsetMs = if (nowSec in active.startEpochSec until active.endEpochSec) {
                (nowSec - active.startEpochSec) * 1000L
            } else {
                0L
            }
            coordinator.playVideo(
                ActivePlayable(
                    itemType = active.itemType,
                    itemId = active.itemId,
                    title = active.title,
                    subtitle = "CH ${ch.number} · ${ch.name}",
                    artworkUrl = active.artworkUrl,
                    streamUrl = api.streamUrl(active.itemType, active.itemId),
                    isVideo = true,
                    resumePositionMs = offsetMs,
                ),
            )
        }
    }

    val hScroll = rememberScrollState()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header + Grid / On now switcher + filter pills (1:1 with desktop /player/guide)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Guide", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, SoulSyncColors.Line, CircleShape)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    GuideViewMode.entries.forEach { mode ->
                        val on = viewMode == mode
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.Transparent)
                                .clickable { viewMode = mode }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (mode == GuideViewMode.GRID) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = mode.label,
                                tint = if (on) Color.White else SoulSyncColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = mode.label,
                                color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(GuideFilter.entries, key = { it.name }) { f ->
                    val on = filter == f
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (on) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line, CircleShape)
                            .clickable { filter = f }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(f.label, color = if (on) Color.White else SoulSyncColors.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Top Picked-Program Preview Panel (exact desktop /player/guide detail panel!)
        val selected = picked
        if (selected != null) {
            val (ch, prog) = selected
            val isLive = nowSec in prog.startEpochSec until prog.endEpochSec
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(148.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoulSyncColors.CardRaised)
                    .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
            ) {
                SoulSyncArtwork(url = prog.artworkUrl, contentDescription = prog.title, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.94f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.72f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.35f),
                                ),
                            ),
                        ),
                )
                IconButton(
                    onClick = { picked = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${if (isLive) "● ON NOW · " else ""}CH ${ch.number} ${ch.name} · ${timeFmt.format(Date(prog.startEpochSec * 1000L))}–${timeFmt.format(Date(prog.endEpochSec * 1000L))}",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                        Text(
                            text = prog.title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (prog.subtitle.isNotBlank()) {
                            Text(
                                text = prog.subtitle,
                                color = SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { tuneInChannel(ch, prog) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isLive) "Tune in" else "Play now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (prog.detailId != null && (prog.itemType == "movie" || prog.itemType == "episode")) {
                            OutlinedButton(
                                onClick = {
                                    if (prog.itemType == "movie") onNavigate(PlayerDestination.MovieDetail(prog.detailId))
                                    else onNavigate(PlayerDestination.ShowDetail(prog.detailId))
                                },
                                shape = CircleShape,
                                border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Details", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        if (shownChannels.isEmpty()) {
            EmptyBox("No channels match this filter.")
            return
        }

        if (viewMode == GuideViewMode.GRID) {
            // ── Authentic Desktop-Parity EPG Time Grid ──
            val totalGridWidth = PX_PER_MIN * windowMinutes
            val nowNeedleX = PX_PER_MIN * nowOffsetMin

            // Time Ruler Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(BorderStroke(1.dp, SoulSyncColors.Line)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(CHANNEL_COL_WIDTH)
                        .height(32.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("CHANNELS", color = SoulSyncColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll)
                        .height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val slots = windowMinutes / 30
                    for (i in 0 until slots) {
                        val slotTime = windowStartSec + i * 1800L
                        Box(
                            modifier = Modifier
                                .width(PX_PER_MIN * 30)
                                .fillMaxHeight()
                                .padding(start = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = timeFmt.format(Date(slotTime * 1000L)),
                                color = if (i == 0) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            // Channel Rows with Sticky Left Channel Column + Synchronized Horizontal Time Grid + Live NOW Needle
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(shownChannels, key = { it.id }) { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT),
                    ) {
                        // Sticky Left Channel Cell (4-tile mosaic + number + name + star)
                        Row(
                            modifier = Modifier
                                .width(CHANNEL_COL_WIDTH)
                                .fillMaxHeight()
                                .background(SoulSyncColors.PanelSolid)
                                .clickable { tuneInChannel(ch) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            ChannelMosaicLogo(urls = ch.mosaicArt, isMusic = ch.category == "music")
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = ch.number.toString(),
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val isFav = ch.id in favorites
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Favorite",
                                        tint = if (isFav) SoulSyncColors.Warning else SoulSyncColors.TextSecondary.copy(alpha = 0.35f),
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clickable {
                                                val updated = if (isFav) favorites - ch.id else favorites + ch.id
                                                favorites = updated
                                                scope.launch { api.saveGuideFavorites(updated.toList()) }
                                            },
                                    )
                                }
                                Text(
                                    text = ch.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    lineHeight = 13.sp,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Synchronized Horizontal Program Timeline Row
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(hScroll),
                        ) {
                            Box(modifier = Modifier.width(totalGridWidth).fillMaxHeight()) {
                                ch.programs.forEach { prog ->
                                    val visStart = prog.startEpochSec.coerceAtLeast(windowStartSec)
                                    val visEnd = prog.endEpochSec.coerceAtMost(windowEndSec)
                                    if (visEnd > visStart) {
                                        val startX = PX_PER_MIN * ((visStart - windowStartSec) / 60f)
                                        val blockW = (PX_PER_MIN * ((visEnd - visStart) / 60f)).coerceAtLeast(48.dp)
                                        val isNow = nowSec in prog.startEpochSec until prog.endEpochSec
                                        val isPicked = picked?.second == prog && picked?.first?.id == ch.id
                                        Box(
                                            modifier = Modifier
                                                .offset(x = startX)
                                                .width(blockW)
                                                .fillMaxHeight()
                                                .padding(horizontal = 2.dp, vertical = 4.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        isPicked -> SoulSyncColors.Accent.copy(alpha = 0.28f)
                                                        isNow -> Color.White.copy(alpha = 0.08f)
                                                        else -> Color.White.copy(alpha = 0.03f)
                                                    },
                                                )
                                                .border(
                                                    1.dp,
                                                    when {
                                                        isPicked -> SoulSyncColors.AccentLight
                                                        isNow -> SoulSyncColors.Accent.copy(alpha = 0.45f)
                                                        else -> SoulSyncColors.Line
                                                    },
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .clickable { picked = ch to prog }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    text = prog.title,
                                                    color = if (isNow || isPicked) Color.White else SoulSyncColors.TextSoft,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isNow || isPicked) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (prog.subtitle.isNotBlank()) {
                                                    Text(
                                                        text = prog.subtitle,
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

                                // Live vertical NOW needle
                                Box(
                                    modifier = Modifier
                                        .offset(x = nowNeedleX)
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(SoulSyncColors.AccentLight),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = SoulSyncColors.Line, thickness = 1.dp)
                }
            }
        } else {
            // ── "On now" Compact View (1:1 with desktop /player/guide On now tab) ──
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shownChannels, key = { it.id }) { ch ->
                    val cur = ch.programs.firstOrNull { nowSec in it.startEpochSec until it.endEpochSec }
                        ?: ch.programs.firstOrNull()
                    val next = cur?.let { c -> ch.programs.firstOrNull { it.startEpochSec >= c.endEpochSec } }
                    if (cur != null) {
                        val progFrac = ((nowSec - cur.startEpochSec).toFloat() / (cur.endEpochSec - cur.startEpochSec).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusable(RoundedCornerShape(12.dp))
                                .clickable { picked = ch to cur },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ChannelMosaicLogo(urls = ch.mosaicArt, isMusic = ch.category == "music")
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "CH ${ch.number} · ${ch.name}",
                                            color = SoulSyncColors.AccentLight,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(cur.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (next != null) {
                                            Text("Next: ${next.title}", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    IconButton(onClick = { tuneInChannel(ch, cur) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Tune in", tint = SoulSyncColors.AccentLight)
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { progFrac },
                                    color = SoulSyncColors.Accent,
                                    trackColor = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 4-tile poster mosaic channel logo (1:1 with desktop /player/guide ChannelLogo) */
@Composable
private fun ChannelMosaicLogo(urls: List<String>, isMusic: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(SoulSyncColors.CardRaised)
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (urls.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    SoulSyncArtwork(url = urls[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                    SoulSyncArtwork(url = urls[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(modifier = Modifier.weight(1f)) {
                    SoulSyncArtwork(url = urls[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                    SoulSyncArtwork(url = urls[3], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else if (urls.isNotEmpty()) {
            SoulSyncArtwork(url = urls.first(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                imageVector = if (isMusic) Icons.Default.Radio else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = SoulSyncColors.AccentLight,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

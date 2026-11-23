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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import net.ssync.player.CalendarEpisode
import net.ssync.player.CalendarMovie
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.VideoCalendarResponse
import net.ssync.player.VideoRequestResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private enum class CalStatusFilter(val label: String) {
    ALL("All"),
    OWNED("In library"),
    UPCOMING("Coming soon"),
    MISSING("Missing"),
}

private sealed interface CalUnifiedEntry {
    val key: String
    val date: String
    val acq: String?

    data class Ep(override val key: String, override val date: String, val ep: CalendarEpisode) : CalUnifiedEntry {
        override val acq: String? get() = ep.acq
    }

    data class Mv(override val key: String, override val date: String, val mv: CalendarMovie) : CalUnifiedEntry {
        override val acq: String? get() = mv.acq
    }
}

private fun isoToday(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return fmt.format(Calendar.getInstance().time)
}

private fun addIsoDays(baseIso: String, delta: Int): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    runCatching { cal.time = fmt.parse(baseIso)!! }
    cal.add(Calendar.DAY_OF_YEAR, delta)
    return fmt.format(cal.time)
}

private fun formatCalDayHeading(dayIso: String, todayIso: String): String {
    if (dayIso == todayIso) return "Today"
    if (dayIso == addIsoDays(todayIso, 1)) return "Tomorrow"
    if (dayIso == addIsoDays(todayIso, -1)) return "Yesterday"
    val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    return runCatching { outFmt.format(parseFmt.parse(dayIso)!!) }.getOrDefault(dayIso)
}

private data class AcqBadgeStyle(val label: String, val bg: Color, val border: Color, val fg: Color)

private fun acqStyle(acq: String?, progress: Double? = null): AcqBadgeStyle = when (acq) {
    "owned" -> AcqBadgeStyle("In library", Color(0x2910B981), Color(0x7310B981), Color(0xFF6EE7B7))
    "downloading" -> AcqBadgeStyle(
        if (progress != null && progress > 0) "Downloading ${progress.toInt()}%" else "Downloading",
        Color(0x293B82F6),
        Color(0x733B82F6),
        Color(0xFF93C5FD),
    )
    "queued", "wanted" -> AcqBadgeStyle("On wishlist", Color(0x29A855F7), Color(0x73A855F7), Color(0xFFD8B4FE))
    "missing", "failed" -> AcqBadgeStyle("Missing", Color(0x29F59E0B), Color(0x73F59E0B), Color(0xFFFCD34D))
    else -> AcqBadgeStyle("Coming soon", Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f), SoulSyncColors.TextSoft)
}

@Composable
fun CalendarScreen(
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val todayIso = remember { isoToday() }
    var days by remember { mutableIntStateOf(14) }
    var startOffset by remember { mutableIntStateOf(-3) }
    var calScope by remember { mutableStateOf("all") } // "all" | "watchlist"
    var statusFilter by remember { mutableStateOf(CalStatusFilter.ALL) }
    var isLoading by remember { mutableStateOf(true) }
    var calData by remember { mutableStateOf<VideoCalendarResponse?>(null) }
    var followedEps by remember { mutableStateOf<List<CalendarEpisode>>(emptyList()) }
    var showMode by remember { mutableStateOf("ask") }
    var movieMode by remember { mutableStateOf("ask") }
    val reqBusy = remember { mutableStateMapOf<String, Boolean>() }
    val reqDone = remember { mutableStateMapOf<String, String>() }

    val startIso = remember(todayIso, startOffset) { addIsoDays(todayIso, startOffset) }

    LaunchedEffect(api, startIso, days, calScope) {
        isLoading = true
        coroutineScope {
            val mainDef = async { api.fetchVideoCalendar(startIso, days, calScope) }
            val folDef = async { api.fetchVideoCalendarFollowed(startIso, days) }
            val optDef = async { api.fetchVideoRequestOptions() }

            when (val r = mainDef.await()) {
                is MusicApiResult.Success -> calData = r.data
                MusicApiResult.DeviceRevoked -> { onDeviceRevoked(); return@coroutineScope }
                is MusicApiResult.Error -> Unit
            }
            (folDef.await() as? MusicApiResult.Success)?.let { followedEps = it.data }
            (optDef.await() as? MusicApiResult.Success)?.let {
                showMode = it.data.show
                movieMode = it.data.movie
            }
        }
        isLoading = false
    }

    val realToday = calData?.today?.takeIf { it.isNotBlank() } ?: todayIso

    val groupedByDate = remember(calData, followedEps, statusFilter, realToday) {
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<CalUnifiedEntry>()
        for (ep in calData?.episodes.orEmpty() + followedEps) {
            if (ep.airDate.isBlank()) continue
            val dedupe = "${ep.tmdbId ?: ep.showId ?: ep.showTitle}-S${ep.seasonNumber}E${ep.episodeNumber}"
            if (!seen.add(dedupe)) continue
            entries += CalUnifiedEntry.Ep("ep:$dedupe", ep.airDate, ep)
        }
        for (mv in calData?.movies.orEmpty()) {
            val dt = mv.eventDate?.takeIf { it.isNotBlank() } ?: mv.releaseDate?.takeIf { it.isNotBlank() } ?: continue
            entries += CalUnifiedEntry.Mv("mv:${mv.tmdbId}:${mv.eventKind ?: dt}", dt, mv)
        }
        entries
            .filter { e ->
                when (statusFilter) {
                    CalStatusFilter.ALL -> true
                    CalStatusFilter.OWNED -> e.acq == "owned"
                    CalStatusFilter.MISSING -> e.acq == "missing" || e.acq == "failed"
                    CalStatusFilter.UPCOMING -> e.acq == "unaired" || e.date > realToday
                }
            }
            .groupBy { it.date }
            .entries
            .sortedBy { it.key }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header + Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Release Calendar",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            // Window navigation + duration chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    CalFilterChip(label = "← Prev", selected = false) { startOffset -= days }
                }
                item {
                    CalFilterChip(label = "Today", selected = startOffset == -3) { startOffset = -3 }
                }
                item {
                    CalFilterChip(label = "Next →", selected = false) { startOffset += days }
                }
                items(listOf(7 to "Week", 14 to "2 Weeks", 28 to "Month")) { (d, lbl) ->
                    CalFilterChip(label = lbl, selected = days == d) { days = d }
                }
            }

            // Status & scope filter chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CalStatusFilter.entries) { f ->
                    CalFilterChip(label = f.label, selected = statusFilter == f) { statusFilter = f }
                }
                item {
                    CalFilterChip(
                        label = if (calScope == "watchlist") "✓ Followed" else "All shows",
                        selected = calScope == "watchlist",
                    ) {
                        calScope = if (calScope == "all") "watchlist" else "all"
                    }
                }
            }
        }

        if (isLoading) {
            LoadingBox()
            return
        }

        if (groupedByDate.isEmpty()) {
            EmptyBox("Nothing on the calendar in this window.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groupedByDate.forEach { (dayIso, dayItems) ->
                val isToday = dayIso == realToday
                item(key = "hdr-$dayIso") {
                    Row(
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatCalDayHeading(dayIso, realToday),
                            color = if (isToday) SoulSyncColors.AccentLight else Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = dayIso,
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }

                items(dayItems, key = { it.key }) { entry ->
                    when (entry) {
                        is CalUnifiedEntry.Ep -> {
                            val ep = entry.ep
                            val style = acqStyle(ep.acq, ep.progress)
                            val code = "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
                            val aired = ep.airDate <= realToday
                            val canReq = aired && (ep.acq == "missing" || ep.acq == "failed") && (ep.tmdbId ?: 0) > 0 && showMode != "none"
                            val busy = reqBusy[entry.key] == true
                            val doneLabel = reqDone[entry.key]
                            val art = ep.showId?.let { api.artwork.showPoster(it) } ?: api.artwork.resolve(ep.posterUrl)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = ep.showId != null) {
                                        ep.showId?.let { onNavigate(PlayerDestination.ShowDetail(it)) }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = SoulSyncColors.Card,
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = art,
                                        contentDescription = ep.showTitle,
                                        fallbackIcon = Icons.Default.Movie,
                                        modifier = Modifier
                                            .width(48.dp)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = ep.showTitle,
                                                color = Color.White,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(SoulSyncColors.Accent.copy(alpha = 0.16f))
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            ) {
                                                Text(code, color = SoulSyncColors.AccentLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        if (!ep.title.isNullOrBlank()) {
                                            Text(
                                                text = ep.title!!,
                                                color = SoulSyncColors.TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(style.bg)
                                                    .border(0.75.dp, style.border, RoundedCornerShape(999.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                            ) {
                                                Text(style.label, color = style.fg, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                            if (canReq) {
                                                Button(
                                                    onClick = {
                                                        val tid = ep.tmdbId ?: return@Button
                                                        if (busy || doneLabel != null) return@Button
                                                        reqBusy[entry.key] = true
                                                        scope.launch {
                                                            when (
                                                                val res = api.requestVideoTitle(
                                                                    kind = "episode",
                                                                    tmdbId = tid,
                                                                    title = ep.showTitle,
                                                                    season = ep.seasonNumber,
                                                                    episode = ep.episodeNumber,
                                                                    get = showMode == "download",
                                                                )
                                                            ) {
                                                                is MusicApiResult.Success -> {
                                                                    reqDone[entry.key] = when (res.data) {
                                                                        VideoRequestResult.Got -> "Wishlist ✓"
                                                                        is VideoRequestResult.Asked -> "Requested ✓"
                                                                        VideoRequestResult.OnTheWay -> "On the way ✓"
                                                                        VideoRequestResult.Owned -> "In library ✓"
                                                                        is VideoRequestResult.Error -> "Retry"
                                                                    }
                                                                }
                                                                else -> Unit
                                                            }
                                                            reqBusy[entry.key] = false
                                                        }
                                                    },
                                                    enabled = !busy && doneLabel == null,
                                                    shape = CircleShape,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = SoulSyncColors.Accent,
                                                        contentColor = SoulSyncColors.OnAccent,
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = if (doneLabel != null) Icons.Default.Check else Icons.Default.Download,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp),
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        text = doneLabel ?: if (busy) "…" else if (showMode == "download") "Get" else "Request",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is CalUnifiedEntry.Mv -> {
                            val mv = entry.mv
                            val style = acqStyle(mv.acq)
                            val aired = entry.date <= realToday
                            val canReq = aired && (mv.acq == "missing" || mv.acq == "failed") && mv.tmdbId > 0 && movieMode != "none"
                            val busy = reqBusy[entry.key] == true
                            val doneLabel = reqDone[entry.key]
                            val art = mv.libraryId?.let { api.artwork.moviePoster(it) } ?: api.artwork.resolve(mv.posterUrl)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = mv.libraryId != null) {
                                        mv.libraryId?.let { onNavigate(PlayerDestination.MovieDetail(it)) }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = SoulSyncColors.Card,
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = art,
                                        contentDescription = mv.title,
                                        fallbackIcon = Icons.Default.Movie,
                                        modifier = Modifier
                                            .width(48.dp)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = mv.title,
                                                color = Color.White,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(Color(0x29F5C518))
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            ) {
                                                Text("Movie", color = Color(0xFFF5C518), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(style.bg)
                                                    .border(0.75.dp, style.border, RoundedCornerShape(999.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                            ) {
                                                Text(style.label, color = style.fg, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                            if (canReq) {
                                                Button(
                                                    onClick = {
                                                        if (busy || doneLabel != null) return@Button
                                                        reqBusy[entry.key] = true
                                                        scope.launch {
                                                            when (
                                                                val res = api.requestVideoTitle(
                                                                    kind = "movie",
                                                                    tmdbId = mv.tmdbId,
                                                                    title = mv.title,
                                                                    year = mv.year?.toString(),
                                                                    posterUrl = mv.posterUrl,
                                                                    get = movieMode == "download",
                                                                )
                                                            ) {
                                                                is MusicApiResult.Success -> {
                                                                    reqDone[entry.key] = when (res.data) {
                                                                        VideoRequestResult.Got -> "Wishlist ✓"
                                                                        is VideoRequestResult.Asked -> "Requested ✓"
                                                                        VideoRequestResult.OnTheWay -> "On the way ✓"
                                                                        VideoRequestResult.Owned -> "In library ✓"
                                                                        is VideoRequestResult.Error -> "Retry"
                                                                    }
                                                                }
                                                                else -> Unit
                                                            }
                                                            reqBusy[entry.key] = false
                                                        }
                                                    },
                                                    enabled = !busy && doneLabel == null,
                                                    shape = CircleShape,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = SoulSyncColors.Accent,
                                                        contentColor = SoulSyncColors.OnAccent,
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp),
                                                ) {
                                                    Text(
                                                        text = doneLabel ?: if (busy) "…" else if (movieMode == "download") "Get" else "Request",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
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
        }
    }
}

@Composable
private fun CalFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) SoulSyncColors.Accent else SoulSyncColors.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else SoulSyncColors.TextSoft,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

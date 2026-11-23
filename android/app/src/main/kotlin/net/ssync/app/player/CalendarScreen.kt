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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.CalendarEpisode
import net.ssync.player.CalendarMovie
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed class CalendarEntry {
    abstract val dateKey: String
    abstract val acq: String
    data class Episode(val ep: CalendarEpisode) : CalendarEntry() {
        override val dateKey: String get() = ep.airDate
        override val acq: String get() = ep.acq
    }
    data class Movie(val movie: CalendarMovie) : CalendarEntry() {
        override val dateKey: String get() = movie.effectiveDate
        override val acq: String get() = movie.acq
    }
}

private enum class CalViewMode(val label: String) {
    WEEK("Week"),
    CARDS("Cards"),
    LIST("Agenda"),
}

private enum class CalendarFilter(val label: String) {
    ALL("All"),
    OWNED("In library"),
    UPCOMING("Coming soon"),
    MISSING("Missing"),
    WISHLIST("Wishlist"),
}

private fun acqColor(acq: String): Color = when (acq) {
    "owned" -> Color(0xFF10B981)
    "downloading" -> Color(0xFF38BDF8)
    "wishlist" -> Color(0xFF818CF8)
    "missing" -> Color(0xFFF59E0B)
    else -> Color(0xFF94A3B8)
}

private fun acqLabel(acq: String, progress: Double? = null): String = when (acq) {
    "owned" -> "In library"
    "downloading" -> if (progress != null) "${progress.toInt()}%" else "Downloading"
    "wishlist" -> "Wishlist"
    "missing" -> "Missing"
    else -> "Upcoming"
}

private fun addCalendarDays(isoDate: String, delta: Int): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    cal.time = runCatching { fmt.parse(isoDate) }.getOrNull() ?: Date()
    cal.add(Calendar.DAY_OF_YEAR, delta)
    return fmt.format(cal.time)
}

private fun weekStartIso(isoDate: String): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    cal.time = runCatching { fmt.parse(isoDate) }.getOrNull() ?: Date()
    val dow = cal.get(Calendar.DAY_OF_WEEK) // Sunday = 1
    cal.add(Calendar.DAY_OF_YEAR, -(dow - Calendar.SUNDAY))
    return fmt.format(cal.time)
}

private fun todayIsoString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun formatDayHeading(isoDate: String, todayIso: String): Pair<String, Boolean> {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outFmt = SimpleDateFormat("EEEE, MMM d", Locale.US)
    val parsed = runCatching { inFmt.parse(isoDate) }.getOrNull()
    val base = if (parsed != null) outFmt.format(parsed) else isoDate
    return base to (isoDate == todayIso)
}

private fun formatShortDay(isoDate: String, todayIso: String): Pair<String, String> {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val wdFmt = SimpleDateFormat("EEE", Locale.US)
    val mdFmt = SimpleDateFormat("MMM d", Locale.US)
    val parsed = runCatching { inFmt.parse(isoDate) }.getOrNull()
    val wd = if (isoDate == todayIso) "Today" else (parsed?.let { wdFmt.format(it) } ?: isoDate)
    val md = parsed?.let { mdFmt.format(it) } ?: ""
    return wd to md
}

private fun formatRangeLabel(startIso: String, days: Int): String {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outFmt = SimpleDateFormat("MMM d", Locale.US)
    val s = runCatching { inFmt.parse(startIso) }.getOrNull()
    val e = runCatching { inFmt.parse(addCalendarDays(startIso, days - 1)) }.getOrNull()
    return if (s != null && e != null) "${outFmt.format(s)} – ${outFmt.format(e)}" else startIso
}

private fun calendarEpisodeArt(ep: CalendarEpisode, api: PlayerApiClient): String? {
    if (!ep.stillUrl.isNullOrBlank()) return api.artwork.resolve(ep.stillUrl)
    ep.showId?.let { return api.artwork.showBackdrop(it) }
    if (!ep.backdropUrl.isNullOrBlank()) return api.artwork.resolve(ep.backdropUrl)
    if (!ep.posterUrl.isNullOrBlank()) return api.artwork.resolve(ep.posterUrl)
    return null
}

private fun calendarMovieArt(m: CalendarMovie, api: PlayerApiClient): String? {
    val libId = m.effectiveLibraryId
    if (libId != null) return api.artwork.movieBackdrop(libId)
    if (!m.posterUrl.isNullOrBlank()) return api.artwork.resolve(m.posterUrl)
    return null
}

@Composable
fun CalendarScreen(
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val onOpenShow: (Int) -> Unit = { id -> onNavigate(PlayerDestination.ShowDetail(id)) }
    val onOpenMovie: (Int) -> Unit = { id -> onNavigate(PlayerDestination.MovieDetail(id)) }
    val scope = rememberCoroutineScope()
    val todayDefault = remember { todayIsoString() }
    var startDate by remember { mutableStateOf(weekStartIso(todayDefault)) }
    var viewMode by remember { mutableStateOf(CalViewMode.CARDS) }
    var filter by remember { mutableStateOf(CalendarFilter.ALL) }
    var scopeMode by remember { mutableStateOf("watchlist") }
    var loading by remember { mutableStateOf(true) }
    var todayIso by remember { mutableStateOf(todayDefault) }
    var episodes by remember { mutableStateOf<List<CalendarEpisode>>(emptyList()) }
    var movies by remember { mutableStateOf<List<CalendarMovie>>(emptyList()) }
    var requestMode by remember { mutableStateOf("none") }
    val requestedKeys = remember { mutableStateMapOf<String, String>() }

    val days = if (viewMode == CalViewMode.WEEK) 7 else 14

    LaunchedEffect(api, startDate, days, scopeMode) {
        loading = true
        coroutineScope {
            val calDef = async { api.fetchVideoCalendar(start = startDate, days = days, scope = scopeMode) }
            val folDef = async { api.fetchVideoCalendarFollowed(start = startDate, days = days) }
            val modeDef = async { api.fetchVideoRequestOptions() }

            val calRes = calDef.await()
            val folRes = folDef.await()
            val modeRes = modeDef.await()

            if (calRes is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            if (modeRes is MusicApiResult.Success) {
                requestMode = modeRes.data.show.takeIf { it != "none" } ?: modeRes.data.movie
            }
            val ownEps = (calRes as? MusicApiResult.Success)?.data?.episodes.orEmpty()
            val folEps = (folRes as? MusicApiResult.Success)?.data.orEmpty()
            val seen = ownEps.map { "${it.resolvedTmdbId}|${it.seasonNumber}|${it.episodeNumber}" }.toSet()
            episodes = ownEps + folEps.filter {
                "${it.resolvedTmdbId}|${it.seasonNumber}|${it.episodeNumber}" !in seen
            }
            movies = (calRes as? MusicApiResult.Success)?.data?.movies.orEmpty()
            (calRes as? MusicApiResult.Success)?.data?.today?.takeIf { it.isNotBlank() }?.let {
                todayIso = it
            }
        }
        loading = false
    }

    val canRequest = requestMode == "download" || requestMode == "ask"
    val requestVerb = if (requestMode == "download") "Get" else "Request"

    fun requestEpisode(ep: CalendarEpisode) {
        val tmdb = ep.resolvedTmdbId ?: return
        val key = "ep:$tmdb:${ep.seasonNumber}:${ep.episodeNumber}"
        requestedKeys[key] = "loading"
        scope.launch {
            val res = api.requestVideoTitle(
                kind = "episode",
                tmdbId = tmdb,
                title = "${ep.showTitle} · S${ep.seasonNumber}E${ep.episodeNumber}",
                season = ep.seasonNumber,
                episode = ep.episodeNumber,
                get = requestMode == "download",
            )
            requestedKeys[key] = if (res is MusicApiResult.Success) "done" else "error"
        }
    }

    fun requestMovie(m: CalendarMovie) {
        if (m.tmdbId <= 0) return
        val key = "movie:${m.tmdbId}"
        requestedKeys[key] = "loading"
        scope.launch {
            val res = api.requestVideoTitle(
                kind = "movie",
                tmdbId = m.tmdbId,
                title = m.title,
                year = m.year?.toString(),
                get = requestMode == "download",
            )
            requestedKeys[key] = if (res is MusicApiResult.Success) "done" else "error"
        }
    }

    val allEntries = remember(episodes, movies) {
        (episodes.map { CalendarEntry.Episode(it) } + movies.map { CalendarEntry.Movie(it) })
            .sortedWith(compareBy<CalendarEntry> { it.dateKey })
    }

    val filteredEntries = remember(allEntries, filter) {
        when (filter) {
            CalendarFilter.ALL -> allEntries
            CalendarFilter.OWNED -> allEntries.filter { it.acq == "owned" }
            CalendarFilter.UPCOMING -> allEntries.filter { it.acq == "upcoming" }
            CalendarFilter.MISSING -> allEntries.filter { it.acq == "missing" }
            CalendarFilter.WISHLIST -> allEntries.filter { it.acq == "wishlist" || it.acq == "downloading" }
        }
    }

    val groupedByDay = remember(filteredEntries) {
        filteredEntries.groupBy { it.dateKey }.toSortedMap()
    }

    // Featured "Next Up" hero episodes (soonest upcoming or today's episodes)
    val heroEpisodes = remember(episodes, todayIso) {
        val upcoming = episodes.filter { it.airDate >= todayIso }
            .sortedWith(compareBy<CalendarEpisode> { it.airDate }.thenBy { it.airsTime ?: "99:99" })
        (if (upcoming.isNotEmpty()) upcoming else episodes.sortedByDescending { it.airDate }).take(3)
    }

    val countAll = allEntries.size
    val countOwned = allEntries.count { it.acq == "owned" }
    val countUpcoming = allEntries.count { it.acq == "upcoming" }
    val countMissing = allEntries.count { it.acq == "missing" }
    val countWish = allEntries.count { it.acq == "wishlist" || it.acq == "downloading" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Premier Header + Navigation
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = if (startDate == weekStartIso(todayIso)) "This week" else "Calendar",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = formatRangeLabel(startDate, days),
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }

                    // Prev / Today / Next segmented control
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, SoulSyncColors.Line, CircleShape)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { startDate = addCalendarDays(startDate, -7) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White)
                        }
                        Text(
                            text = "Today",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { startDate = weekStartIso(todayIso) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        IconButton(
                            onClick = { startDate = addCalendarDays(startDate, 7) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = Color.White)
                        }
                    }
                }

                // View mode switcher (Week | Cards | Agenda) + Watchlist/All toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.38f))
                            .border(1.dp, SoulSyncColors.Line, CircleShape)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        CalViewMode.entries.forEach { m ->
                            val selected = viewMode == m
                            Text(
                                text = m.label,
                                color = if (selected) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) SoulSyncColors.Accent.copy(alpha = 0.35f) else Color.Transparent)
                                    .clickable { viewMode = m }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.38f))
                            .border(1.dp, SoulSyncColors.Line, CircleShape)
                            .padding(3.dp),
                    ) {
                        listOf("watchlist" to "Watchlist", "all" to "All").forEach { (key, lbl) ->
                            val on = scopeMode == key
                            Text(
                                text = lbl,
                                color = if (on) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 11.5.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { scopeMode = key }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                // State filter chips with live counts
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chipDefs = listOf(
                        Triple(CalendarFilter.ALL, countAll, Color.White),
                        Triple(CalendarFilter.OWNED, countOwned, acqColor("owned")),
                        Triple(CalendarFilter.UPCOMING, countUpcoming, acqColor("upcoming")),
                        Triple(CalendarFilter.MISSING, countMissing, acqColor("missing")),
                        Triple(CalendarFilter.WISHLIST, countWish, acqColor("wishlist")),
                    )
                    items(chipDefs) { (f, cnt, dotColor) ->
                        val active = filter == f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) SoulSyncColors.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                .border(
                                    1.dp,
                                    if (active) SoulSyncColors.Accent else SoulSyncColors.Line,
                                    CircleShape,
                                )
                                .clickable { filter = f }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            if (f != CalendarFilter.ALL) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                            }
                            Text(
                                text = "${f.label} $cnt",
                                color = if (active) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // 2. Next Up Billboard Hero (matching Desktop CalendarHero)
        if (!loading && heroEpisodes.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(heroEpisodes, key = { "hero-${it.id}-${it.showTmdbId}-${it.seasonNumber}-${it.episodeNumber}" }) { ep ->
                        val art = calendarEpisodeArt(ep, api)
                        val whenText = when {
                            ep.airDate == todayIso -> "TODAY"
                            ep.airDate == addCalendarDays(todayIso, 1) -> "TOMORROW"
                            else -> ep.airDate
                        }
                        Box(
                            modifier = Modifier
                                .width(310.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF151725))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                .clickable { ep.showId?.let(onOpenShow) },
                        ) {
                            if (art != null) {
                                AsyncImage(
                                    model = art,
                                    contentDescription = ep.showTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Black.copy(alpha = 0.18f),
                                                Color.Black.copy(alpha = 0.88f),
                                            ),
                                        ),
                                    ),
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(SoulSyncColors.AccentLight),
                                    )
                                    Text(
                                        text = buildString {
                                            append("NEXT UP · $whenText")
                                            ep.network?.takeIf { it.isNotBlank() }?.let { append(" · ${it.uppercase()}") }
                                        },
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = ep.showTitle,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                            (ep.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        color = SoulSyncColors.TextSoft,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    CalendarStatusPill(acq = ep.acq, progress = ep.progress)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Content
        if (loading) {
            item { LoadingBox() }
        } else if (groupedByDay.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = SoulSyncColors.TextSecondary,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "Nothing scheduled in this window",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Try switching to All shows or stepping to another week.",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else if (viewMode == CalViewMode.WEEK) {
            // Horizontal 7-day cable-column Week View (mirrors Desktop WeekView!)
            item {
                val weekDays = remember(startDate) { (0 until 7).map { addCalendarDays(startDate, it) } }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(weekDays, key = { it }) { day ->
                        val itemsForDay = groupedByDay[day].orEmpty()
                        val (wd, md) = formatShortDay(day, todayIso)
                        val isToday = day == todayIso
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isToday) SoulSyncColors.Accent.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.035f),
                            border = BorderStroke(
                                1.dp,
                                if (isToday) SoulSyncColors.Accent.copy(alpha = 0.55f) else SoulSyncColors.Line,
                            ),
                            modifier = Modifier.width(210.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = wd,
                                        color = if (isToday) SoulSyncColors.AccentLight else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = md,
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 11.sp,
                                    )
                                }
                                DayStateBar(items = itemsForDay)
                                if (itemsForDay.isEmpty()) {
                                    Text(
                                        text = "Nothing airs",
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    )
                                } else {
                                    itemsForDay.forEach { entry ->
                                        when (entry) {
                                            is CalendarEntry.Episode -> {
                                                val ep = entry.ep
                                                val reqKey = "ep:${ep.resolvedTmdbId}:${ep.seasonNumber}:${ep.episodeNumber}"
                                                val reqState = requestedKeys[reqKey]
                                                val aired = ep.airDate <= todayIso
                                                val showReq = canRequest && aired && ep.acq == "missing" && ep.resolvedTmdbId != null
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color.White.copy(alpha = 0.05f))
                                                        .border(1.dp, acqColor(ep.acq).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                                        .clickable { ep.showId?.let(onOpenShow) }
                                                        .padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        text = ep.showTitle,
                                                        color = Color.White,
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            text = "S${ep.seasonNumber}E${ep.episodeNumber}",
                                                            color = SoulSyncColors.TextSoft,
                                                            fontSize = 11.sp,
                                                        )
                                                        CalendarStatusPill(
                                                            acq = if (reqState == "done") "wishlist" else ep.acq,
                                                            progress = ep.progress,
                                                        )
                                                    }
                                                    if (showReq && reqState != "done") {
                                                        Text(
                                                            text = if (reqState == "loading") "…" else "+ $requestVerb",
                                                            color = SoulSyncColors.AccentLight,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(SoulSyncColors.Accent.copy(alpha = 0.18f))
                                                                .clickable { requestEpisode(ep) }
                                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                                        )
                                                    }
                                                }
                                            }
                                            is CalendarEntry.Movie -> {
                                                val m = entry.movie
                                                val mLibId = m.effectiveLibraryId
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color.White.copy(alpha = 0.05f))
                                                        .border(1.dp, acqColor(m.acq).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                                        .clickable { mLibId?.let(onOpenMovie) }
                                                        .padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        text = "🎬 ${m.title}",
                                                        color = Color.White,
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    CalendarStatusPill(acq = m.acq, progress = m.progress)
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
        } else {
            // CARDS view or AGENDA list view grouped by day with per-day StateBar
            groupedByDay.forEach { (dateKey, dayItems) ->
                item(key = "hdr-$dateKey") {
                    val (label, isToday) = formatDayHeading(dateKey, todayIso)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isToday) {
                                Text(
                                    text = "TODAY",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${dayItems.size} ${if (dayItems.size == 1) "item" else "items"}",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        DayStateBar(items = dayItems)
                    }
                }

                items(
                    items = dayItems,
                    key = { e ->
                        when (e) {
                            is CalendarEntry.Episode -> "ep-${e.ep.id}-${e.ep.resolvedTmdbId}-${e.ep.seasonNumber}-${e.ep.episodeNumber}"
                            is CalendarEntry.Movie -> "mv-${e.movie.tmdbId}-${e.movie.effectiveType}"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is CalendarEntry.Episode -> {
                            val ep = entry.ep
                            val epShowId = ep.showId
                            val reqKey = "ep:${ep.resolvedTmdbId}:${ep.seasonNumber}:${ep.episodeNumber}"
                            val reqState = requestedKeys[reqKey]
                            val aired = ep.airDate <= todayIso
                            val showRequest = canRequest && aired && ep.acq == "missing" && ep.resolvedTmdbId != null
                            if (viewMode == CalViewMode.CARDS) {
                                CalendarVisualCard(
                                    title = ep.showTitle,
                                    subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                        (ep.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    meta = listOfNotNull(ep.airsTime?.takeIf { it.isNotBlank() }, ep.network?.takeIf { it.isNotBlank() })
                                        .joinToString(" · "),
                                    codeBadge = "S${ep.seasonNumber}E${ep.episodeNumber}",
                                    artUrl = calendarEpisodeArt(ep, api),
                                    acq = if (reqState == "done") "wishlist" else ep.acq,
                                    progress = ep.progress,
                                    showRequest = showRequest,
                                    requestVerb = requestVerb,
                                    requestState = reqState,
                                    onRequest = { requestEpisode(ep) },
                                    onClick = if (epShowId != null && !ep.notInLibrary) ({ onOpenShow(epShowId) }) else null,
                                )
                            } else {
                                CalendarAgendaRow(
                                    title = ep.showTitle,
                                    subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                        (ep.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    meta = listOfNotNull(ep.airsTime?.takeIf { it.isNotBlank() }, ep.network?.takeIf { it.isNotBlank() })
                                        .joinToString(" · "),
                                    artUrl = calendarEpisodeArt(ep, api),
                                    isMovie = false,
                                    acq = if (reqState == "done") "wishlist" else ep.acq,
                                    progress = ep.progress,
                                    showRequest = showRequest,
                                    requestVerb = requestVerb,
                                    requestState = reqState,
                                    onRequest = { requestEpisode(ep) },
                                    onClick = if (epShowId != null && !ep.notInLibrary) ({ onOpenShow(epShowId) }) else null,
                                )
                            }
                        }

                        is CalendarEntry.Movie -> {
                            val m = entry.movie
                            val mLibId = m.effectiveLibraryId
                            val reqKey = "movie:${m.tmdbId}"
                            val reqState = requestedKeys[reqKey]
                            val aired = m.effectiveDate <= todayIso
                            val showRequest = canRequest && aired && m.acq == "missing" && m.tmdbId > 0
                            val releaseMeta = listOfNotNull(
                                if (m.effectiveType == "cinema") "In cinemas" else "Home release",
                                m.year?.toString(),
                            ).joinToString(" · ")
                            if (viewMode == CalViewMode.CARDS) {
                                CalendarVisualCard(
                                    title = "🎬 ${m.title}",
                                    subtitle = releaseMeta,
                                    meta = "",
                                    codeBadge = "MOVIE",
                                    artUrl = calendarMovieArt(m, api),
                                    acq = if (reqState == "done") "wishlist" else m.acq,
                                    progress = m.progress,
                                    showRequest = showRequest,
                                    requestVerb = requestVerb,
                                    requestState = reqState,
                                    onRequest = { requestMovie(m) },
                                    onClick = if (mLibId != null) ({ onOpenMovie(mLibId) }) else null,
                                )
                            } else {
                                CalendarAgendaRow(
                                    title = m.title,
                                    subtitle = releaseMeta,
                                    meta = "",
                                    artUrl = calendarMovieArt(m, api),
                                    isMovie = true,
                                    acq = if (reqState == "done") "wishlist" else m.acq,
                                    progress = m.progress,
                                    showRequest = showRequest,
                                    requestVerb = requestVerb,
                                    requestState = reqState,
                                    onRequest = { requestMovie(m) },
                                    onClick = if (mLibId != null) ({ onOpenMovie(mLibId) }) else null,
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
private fun DayStateBar(items: List<CalendarEntry>) {
    val total = items.size.coerceAtLeast(1)
    val order = listOf("owned", "downloading", "wishlist", "upcoming", "missing")
    val counts = order.mapNotNull { st ->
        val c = items.count { it.acq == st }
        if (c > 0) st to c else null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        counts.forEach { (st, c) ->
            Box(
                modifier = Modifier
                    .weight(c.toFloat() / total.toFloat())
                    .fillMaxSize()
                    .background(acqColor(st)),
            )
        }
    }
}

@Composable
private fun CalendarVisualCard(
    title: String,
    subtitle: String,
    meta: String,
    codeBadge: String,
    artUrl: String?,
    acq: String,
    progress: Double?,
    showRequest: Boolean,
    requestVerb: String,
    requestState: String?,
    onRequest: () -> Unit,
    onClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF141622)),
            ) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.55f)),
                            ),
                        ),
                )
                Text(
                    text = codeBadge,
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                ) {
                    CalendarStatusPill(acq = acq, progress = progress)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = SoulSyncColors.TextSoft,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showRequest) {
                    if (requestState == "done") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(14.dp))
                            Text("Requested", color = Color(0xFF6EE7B7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onRequest,
                            enabled = requestState != "loading",
                            colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (requestState == "loading") "…" else requestVerb,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarAgendaRow(
    title: String,
    subtitle: String,
    meta: String,
    artUrl: String?,
    isMovie: Boolean,
    acq: String,
    progress: Double?,
    showRequest: Boolean,
    requestVerb: String,
    requestState: String?,
    onRequest: () -> Unit,
    onClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, SoulSyncColors.Line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (isMovie) Icons.Default.Movie else Icons.Default.Tv,
                        contentDescription = null,
                        tint = SoulSyncColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = SoulSyncColors.TextSoft,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CalendarStatusPill(acq = acq, progress = progress)
                if (showRequest && requestState != "done") {
                    Text(
                        text = if (requestState == "loading") "…" else "+ $requestVerb",
                        color = SoulSyncColors.AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SoulSyncColors.Accent.copy(alpha = 0.2f))
                            .clickable(onClick = onRequest)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarStatusPill(acq: String, progress: Double?) {
    val c = acqColor(acq)
    Text(
        text = acqLabel(acq, progress),
        color = c,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, c.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

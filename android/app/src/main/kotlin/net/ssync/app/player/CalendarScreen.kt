package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
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
    SCHEDULE("Schedule"),
    CARDS("Cards"),
}

private enum class CalendarFilter(val label: String) {
    ALL("All"),
    OWNED("In library"),
    WISHLIST("Wanted"),
    MISSING("Missing"),
    UPCOMING("Upcoming"),
}

private fun acqColor(acq: String): Color = when (acq) {
    "owned" -> Color(0xFF22C55E)      // crisp manager green
    "downloading" -> Color(0xFF38BDF8)
    "wishlist" -> Color(0xFF818CF8)   // crisp manager indigo
    "missing" -> Color(0xFFF59E0B)    // crisp manager amber
    else -> Color(0xFF94A3B8)         // muted slate for upcoming/auto
}

private fun acqLabel(acq: String, progress: Double? = null): String = when (acq) {
    "owned" -> "In library"
    "downloading" -> if (progress != null) "${progress.toInt()}%" else "Downloading"
    "wishlist" -> "Wanted"
    "missing" -> "Missing"
    else -> "Upcoming"
}

/** Parse "HH:MM" into minutes since midnight for band grouping & friendly 12h formatting. */
private fun parseAirMins(airsTime: String?): Int? {
    val raw = airsTime?.trim().takeIf { !it.isNullOrBlank() } ?: return null
    val parts = raw.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return h * 60 + m
}

private fun formatAirTime12h(airsTime: String?): String? {
    val mins = parseAirMins(airsTime) ?: return null
    val h24 = (mins / 60) % 24
    val m = mins % 60
    val ampm = if (h24 >= 12) "PM" else "AM"
    val h12 = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return if (m == 0) "$h12 $ampm" else "$h12:%02d $ampm".format(m)
}

private fun timeBandLabel(airsTime: String?): String {
    val mins = parseAirMins(airsTime) ?: return "ANYTIME"
    val h = mins / 60
    return when {
        h < 12 -> "MORNING"
        h < 17 -> "AFTERNOON"
        h < 22 -> "PRIME TIME"
        else -> "LATE NIGHT"
    }
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
    val dow = cal.get(Calendar.DAY_OF_WEEK)
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

private fun formatStripDay(isoDate: String, todayIso: String): Triple<String, String, Boolean> {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val wdFmt = SimpleDateFormat("EEE", Locale.US)
    val numFmt = SimpleDateFormat("d", Locale.US)
    val parsed = runCatching { inFmt.parse(isoDate) }.getOrNull()
    val isToday = isoDate == todayIso
    val wd = if (isToday) "Today" else (parsed?.let { wdFmt.format(it) } ?: isoDate)
    val num = parsed?.let { numFmt.format(it) } ?: ""
    return Triple(wd, num, isToday)
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

/**
 * Best-in-class Mobile Release Calendar inspired directly by the Manager Calendar:
 * - Interactive 7-day selector strip with per-day item counts & color-coded StateBar
 * - Clean manager-style monochrome filter chips (`All`, `● In library`, `● Wanted`, `● Missing`, `○ Upcoming`)
 * - Full-width, ultra-readable schedule tiles with 3dp left status stripe, `MORNING / AFTERNOON / PRIME TIME / LATE NIGHT`
 *   time-of-day dividers, and clean `● Status` indicators (zero visual clutter!)
 * - Optional visual 16:9 **Cards** view with backdrop stills & 1-tap Request/Get buttons on aired missing items.
 */
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
    // Player 7-day window: 5 days look-behind + Today + 1 day look-ahead (Tomorrow)
    var weekStart by remember { mutableStateOf(addCalendarDays(todayDefault, -5)) }
    var selectedDay by remember { mutableStateOf<String?>(todayDefault) } // null = whole 7-day window
    var viewMode by remember { mutableStateOf(CalViewMode.SCHEDULE) }
    var filter by remember { mutableStateOf(CalendarFilter.ALL) }
    var scopeMode by remember { mutableStateOf("watchlist") }
    var loading by remember { mutableStateOf(true) }
    var todayIso by remember { mutableStateOf(todayDefault) }
    var episodes by remember { mutableStateOf<List<CalendarEpisode>>(emptyList()) }
    var movies by remember { mutableStateOf<List<CalendarMovie>>(emptyList()) }
    var requestMode by remember { mutableStateOf("none") }
    val requestedKeys = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(api, weekStart, scopeMode) {
        loading = true
        coroutineScope {
            val calDef = async { api.fetchVideoCalendar(start = weekStart, days = 7, scope = scopeMode) }
            val folDef = async { api.fetchVideoCalendarFollowed(start = weekStart, days = 7) }
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

    val weekDays = remember(weekStart) { (0 until 7).map { addCalendarDays(weekStart, it) } }

    val allEntries = remember(episodes, movies) {
        (episodes.map { CalendarEntry.Episode(it) } + movies.map { CalendarEntry.Movie(it) })
            .sortedWith(compareBy<CalendarEntry> { it.dateKey })
    }

    val filteredEntries = remember(allEntries, filter) {
        when (filter) {
            CalendarFilter.ALL -> allEntries
            CalendarFilter.OWNED -> allEntries.filter { it.acq == "owned" }
            CalendarFilter.WISHLIST -> allEntries.filter { it.acq == "wishlist" || it.acq == "downloading" }
            CalendarFilter.MISSING -> allEntries.filter { it.acq == "missing" }
            CalendarFilter.UPCOMING -> allEntries.filter { it.acq == "upcoming" }
        }
    }

    val entriesByDay = remember(filteredEntries) { filteredEntries.groupBy { it.dateKey } }
    val allByDay = remember(allEntries) { allEntries.groupBy { it.dateKey } }

    // Days shown below the strip: either the tapped day, or all 7 days if user tapped "All week"
    val visibleDays = remember(weekDays, selectedDay, entriesByDay) {
        if (selectedDay != null && selectedDay in weekDays) listOf(selectedDay!!)
        else weekDays.filter { !entriesByDay[it].isNullOrEmpty() }
    }

    val countAll = allEntries.size
    val countOwned = allEntries.count { it.acq == "owned" }
    val countWish = allEntries.count { it.acq == "wishlist" || it.acq == "downloading" }
    val countMissing = allEntries.count { it.acq == "missing" }
    val countUpcoming = allEntries.count { it.acq == "upcoming" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Clean Header + Week Navigation
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = if (weekStart == addCalendarDays(todayIso, -5)) "Recent & Tomorrow" else "Calendar",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = formatRangeLabel(weekStart, 7),
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.5.sp,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                weekStart = addCalendarDays(weekStart, -7)
                                selectedDay = null
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev week", tint = Color.White)
                        }
                        Text(
                            text = "Today",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    weekStart = addCalendarDays(todayIso, -5)
                                    selectedDay = todayIso
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        IconButton(
                            onClick = {
                                weekStart = addCalendarDays(weekStart, 7)
                                selectedDay = null
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week", tint = Color.White)
                        }
                    }
                }

                // Schedule | Cards + Watchlist | All Library
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF141620))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        CalViewMode.entries.forEach { m ->
                            val on = viewMode == m
                            Text(
                                text = m.label,
                                color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                fontSize = 12.5.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { viewMode = m }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF141620))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                            .padding(3.dp),
                    ) {
                        listOf("watchlist" to "Watchlist", "all" to "All").forEach { (k, lbl) ->
                            val on = scopeMode == k
                            Text(
                                text = lbl,
                                color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { scopeMode = k }
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                // Manager-style Monochrome Filter Chips with Tiny Status Dots
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chips = listOf(
                        Triple(CalendarFilter.ALL, countAll, null),
                        Triple(CalendarFilter.OWNED, countOwned, acqColor("owned")),
                        Triple(CalendarFilter.WISHLIST, countWish, acqColor("wishlist")),
                        Triple(CalendarFilter.MISSING, countMissing, acqColor("missing")),
                        Triple(CalendarFilter.UPCOMING, countUpcoming, acqColor("upcoming")),
                    )
                    items(chips) { (f, cnt, dotColor) ->
                        val active = filter == f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) Color.White.copy(alpha = 0.14f) else Color(0xFF12141E))
                                .border(
                                    1.dp,
                                    if (active) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                                    CircleShape,
                                )
                                .clickable { filter = if (filter == f && f != CalendarFilter.ALL) CalendarFilter.ALL else f }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            if (dotColor != null) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (f == CalendarFilter.UPCOMING) {
                                                Modifier.border(1.5.dp, dotColor, CircleShape)
                                            } else {
                                                Modifier.background(dotColor)
                                            },
                                        ),
                                )
                            }
                            Text(
                                text = f.label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.8f),
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                text = "$cnt",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // 2. Interactive 7-Day Strip (tap any day for crisp focused view, or "All" for the whole week!)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all-week") {
                    val on = selectedDay == null
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .width(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color(0xFF12141E))
                            .border(1.dp, if (on) SoulSyncColors.Accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .clickable { selectedDay = null }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                    ) {
                        Text("All", color = if (on) Color.White else SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${filteredEntries.size}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
                items(weekDays, key = { "strip-$it" }) { day ->
                    val (wd, num, isToday) = formatStripDay(day, todayIso)
                    val on = selectedDay == day
                    val dayAll = allByDay[day].orEmpty()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .width(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    on -> SoulSyncColors.Accent.copy(alpha = 0.24f)
                                    isToday -> Color(0xFF191C2B)
                                    else -> Color(0xFF12141E)
                                },
                            )
                            .border(
                                1.dp,
                                when {
                                    on -> SoulSyncColors.Accent
                                    isToday -> SoulSyncColors.Accent.copy(alpha = 0.45f)
                                    else -> Color.White.copy(alpha = 0.08f)
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { selectedDay = if (selectedDay == day) null else day }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                    ) {
                        Text(
                            text = wd,
                            color = if (isToday) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(num, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            if (dayAll.isNotEmpty()) {
                                Text("${dayAll.size}", color = SoulSyncColors.TextSecondary, fontSize = 10.sp)
                            }
                        }
                        DayStateBar(items = dayAll)
                    }
                }
            }
        }

        // 3. Body
        if (loading) {
            item { LoadingBox() }
        } else if (visibleDays.isEmpty() || visibleDays.all { entriesByDay[it].isNullOrEmpty() }) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(36.dp))
                    Text("Nothing airs", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Try selecting All week or another filter.", color = SoulSyncColors.TextSecondary, fontSize = 12.5.sp)
                }
            }
        } else {
            visibleDays.forEach { day ->
                val dayItems = entriesByDay[day].orEmpty()
                if (dayItems.isEmpty()) return@forEach
                val dayMovies = dayItems.filterIsInstance<CalendarEntry.Movie>().map { it.movie }
                val dayEpisodes = dayItems.filterIsInstance<CalendarEntry.Episode>()
                    .map { it.ep }
                    .sortedBy { parseAirMins(it.airsTime) ?: 9999 }

                // Day Header + StateBar
                item(key = "hdr-$day") {
                    val (label, isToday) = formatDayHeading(day, todayIso)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = label,
                                    color = if (isToday) SoulSyncColors.AccentLight else Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                if (isToday) {
                                    Text(
                                        text = "TODAY",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(SoulSyncColors.Accent.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "${dayItems.size}",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        DayStateBar(items = dayItems)
                    }
                }

                // Movies first (matching Manager Calendar!)
                items(dayMovies, key = { "m-$day-${it.tmdbId}-${it.effectiveType}" }) { m ->
                    val libId = m.effectiveLibraryId
                    val reqKey = "movie:${m.tmdbId}"
                    val reqState = requestedKeys[reqKey]
                    val aired = m.effectiveDate <= todayIso
                    val showReq = canRequest && aired && m.acq == "missing" && m.tmdbId > 0
                    val effectiveAcq = if (reqState == "done") "wishlist" else m.acq
                    val sub = listOfNotNull(
                        if (m.effectiveType == "cinema") "In cinemas" else "Home release",
                        m.year?.toString(),
                    ).joinToString(" · ")

                    if (viewMode == CalViewMode.CARDS) {
                        CalendarVisualCard(
                            title = m.title,
                            subtitle = sub,
                            meta = "",
                            codeBadge = "MOVIE",
                            artUrl = calendarMovieArt(m, api),
                            acq = effectiveAcq,
                            progress = m.progress,
                            showRequest = showReq,
                            requestVerb = requestVerb,
                            requestState = reqState,
                            onRequest = { requestMovie(m) },
                            onClick = if (libId != null) ({ onOpenMovie(libId) }) else null,
                        )
                    } else {
                        ManagerStyleTile(
                            title = m.title,
                            meta = sub,
                            isMovie = true,
                            acq = effectiveAcq,
                            progress = m.progress,
                            showRequest = showReq,
                            requestVerb = requestVerb,
                            requestState = reqState,
                            onRequest = { requestMovie(m) },
                            onClick = if (libId != null) ({ onOpenMovie(libId) }) else null,
                        )
                    }
                }

                // Episodes grouped by time band (MORNING / AFTERNOON / PRIME TIME / LATE NIGHT)
                val banded = dayEpisodes.groupBy { timeBandLabel(it.airsTime) }
                val bandOrder = listOf("MORNING", "AFTERNOON", "PRIME TIME", "LATE NIGHT", "ANYTIME")
                bandOrder.forEach { band ->
                    val bandEps = banded[band].orEmpty()
                    if (bandEps.isEmpty()) return@forEach

                    if (viewMode == CalViewMode.SCHEDULE) {
                        item(key = "band-$day-$band") {
                            Text(
                                text = band,
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                                modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 0.dp),
                            )
                        }
                    }

                    items(bandEps, key = { "ep-$day-${it.id}-${it.resolvedTmdbId}-${it.seasonNumber}-${it.episodeNumber}" }) { ep ->
                        val epShowId = ep.showId
                        val reqKey = "ep:${ep.resolvedTmdbId}:${ep.seasonNumber}:${ep.episodeNumber}"
                        val reqState = requestedKeys[reqKey]
                        val aired = ep.airDate <= todayIso
                        val showReq = canRequest && aired && ep.acq == "missing" && ep.resolvedTmdbId != null
                        val effectiveAcq = if (reqState == "done") "wishlist" else ep.acq
                        val time12 = formatAirTime12h(ep.airsTime)
                        val metaLine = listOfNotNull(
                            "S${ep.seasonNumber} · E${ep.episodeNumber}",
                            time12,
                            ep.network?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")

                        if (viewMode == CalViewMode.CARDS) {
                            CalendarVisualCard(
                                title = ep.showTitle,
                                subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                    (ep.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                meta = listOfNotNull(time12, ep.network?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                codeBadge = "S${ep.seasonNumber}E${ep.episodeNumber}",
                                artUrl = calendarEpisodeArt(ep, api),
                                acq = effectiveAcq,
                                progress = ep.progress,
                                showRequest = showReq,
                                requestVerb = requestVerb,
                                requestState = reqState,
                                onRequest = { requestEpisode(ep) },
                                onClick = if (epShowId != null && !ep.notInLibrary) ({ onOpenShow(epShowId) }) else null,
                            )
                        } else {
                            ManagerStyleTile(
                                title = ep.showTitle,
                                meta = metaLine,
                                isMovie = false,
                                acq = effectiveAcq,
                                progress = ep.progress,
                                showRequest = showReq,
                                requestVerb = requestVerb,
                                requestState = reqState,
                                onRequest = { requestEpisode(ep) },
                                onClick = if (epShowId != null && !ep.notInLibrary) ({ onOpenShow(epShowId) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exact Manager-Calendar tile aesthetic for mobile:
 * - Matte dark card with crisp 3dp left status stripe
 * - Generous 15sp bold title, clean dot-separated metadata on left, subtle `● Status` on right
 */
@Composable
private fun ManagerStyleTile(
    title: String,
    meta: String,
    isMovie: Boolean,
    acq: String,
    progress: Double?,
    showRequest: Boolean,
    requestVerb: String,
    requestState: String?,
    onRequest: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val accent = acqColor(acq)
    val bgColor = if (isMovie) Color(0xFF1A192D) else Color(0xFF141620)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Signature 3.5dp left vertical accent stripe
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(if (acq == "upcoming") Color.White.copy(alpha = 0.14f) else accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isMovie) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = meta,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    ManagerStatusInline(acq = acq, progress = progress)
                }
                if (showRequest) {
                    Spacer(modifier = Modifier.height(2.dp))
                    if (requestState == "done") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(14.dp))
                            Text("Added to wishlist", color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onRequest,
                            enabled = requestState != "loading",
                            colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (requestState == "loading") "…" else "$requestVerb now",
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
private fun ManagerStatusInline(acq: String, progress: Double?) {
    val c = acqColor(acq)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .then(
                    if (acq == "upcoming") Modifier.border(1.5.dp, c, CircleShape)
                    else Modifier.background(c),
                ),
        )
        Text(
            text = acqLabel(acq, progress),
            color = if (acq == "upcoming") SoulSyncColors.TextSecondary else c,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DayStateBar(items: List<CalendarEntry>) {
    val total = items.size.coerceAtLeast(1)
    val order = listOf("owned", "downloading", "wishlist", "missing", "upcoming")
    val counts = order.mapNotNull { st ->
        val c = items.count { it.acq == st }
        if (c > 0) st to c else null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f)),
    ) {
        counts.forEach { (st, c) ->
            Box(
                modifier = Modifier
                    .weight(c.toFloat() / total.toFloat())
                    .fillMaxSize()
                    .background(if (st == "upcoming") Color.White.copy(alpha = 0.18f) else acqColor(st)),
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
        color = Color(0xFF141620),
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
                    .background(Color(0xFF0E1018)),
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
                                listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.65f)),
                            ),
                        ),
                )
                Text(
                    text = codeBadge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    ManagerStatusInline(acq = acq, progress = progress)
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
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = SoulSyncColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (meta.isNotBlank()) {
                        Text(meta, color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showRequest && requestState != "done") {
                    Button(
                        onClick = onRequest,
                        enabled = requestState != "loading",
                        colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (requestState == "loading") "…" else requestVerb, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

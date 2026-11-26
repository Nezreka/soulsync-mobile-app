package net.ssync.app.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.HomeAlbum
import net.ssync.player.HomeMonth
import net.ssync.player.HomeOverview
import net.ssync.player.HomePaging
import net.ssync.player.HomeTimeline
import net.ssync.player.HomeTimelineRow
import net.ssync.player.HomeVideo
import net.ssync.player.HomeVideoText
import net.ssync.player.HomeVideosApiClient
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerLibrary
import kotlin.math.roundToInt

// the home videos library (scrap/HOME_VIDEOS_CONTRACT.md on the server side).
// phone and tablet here; the tv layout is in TvHomeVideos.kt, the sheet in HomeVideoSheet.kt.

internal object HomeColors {
    val Warm = Color(0xFFFFB46B)
    val WarmBg = Color(0x33FFB46B)
    val Muted = Color(0xFF9093A4)
    val Raise = Color(0xFF13131A)
    val Raise2 = Color(0xFF1C1C25)
    val Line = Color(0x12FFFFFF)
    val Line2 = Color(0x24FFFFFF)
    val Badge = Color(0xA6000000)
}

/** one list of clips the page shows: a month, an album, a search, or everything */
internal data class HomeList(
    val key: String,
    val month: String? = null,
    val album: String? = null,
    val query: String? = null,
    /** what the overview says it holds; a search starts at 0 */
    val count: Int = 0,
) {
    companion object {
        fun month(m: HomeMonth) = if (m.month.isEmpty()) HomeList("all", count = m.count) else HomeList("m:${m.month}", month = m.month, count = m.count)
        fun album(a: HomeAlbum) = HomeList("a:${a.key}", album = a.key, count = a.count)
        fun search(q: String) = HomeList("q:${q.trim().lowercase()}", query = q.trim())
    }
}

/**
 * what one library's page knows: the overview and the clips loaded so far.
 * it outlives the screen (going into an album and back keeps the place).
 */
@Stable
internal class HomeLibraryState(val libraryId: Int) {
    var overview by mutableStateOf<HomeOverview?>(null)
    var error by mutableStateOf<String?>(null)
    var revoked by mutableStateOf(false)
    /** a page that didn't load; the list offers to try again */
    var pageError by mutableStateOf<String?>(null)
    var retryEpoch by mutableIntStateOf(0)
    val slots = mutableStateMapOf<String, List<HomeVideo?>>()
    private val inflight = java.util.Collections.synchronizedSet(HashSet<String>())
    private val lists = java.util.concurrent.ConcurrentHashMap<String, HomeList>()

    fun awaitingPictures(): Boolean =
        overview?.onThisDay?.any { it.thumbUrl == null && !it.missing } == true ||
            slots.values.any { l -> l.any { it != null && it.thumbUrl == null && !it.missing } }

    /** the pages holding clips with no picture yet, asked for again (a few at a time) */
    suspend fun refreshPictures(home: HomeVideosApiClient) {
        if (overview?.onThisDay?.any { it.thumbUrl == null && !it.missing } == true) {
            (home.overview(libraryId) as? MusicApiResult.Success)?.data?.let { o ->
                overview?.let { cur -> overview = cur.copy(onThisDay = o.onThisDay, albums = if (cur.albums.map { it.key } == o.albums.map { it.key }) o.albums else cur.albums) }
            }
        }
        var budget = 4
        for ((key, l) in slots.toMap()) {
            val list = lists[key] ?: continue
            val starts = l.indices.filter { l[it] != null && l[it]!!.thumbUrl == null && !l[it]!!.missing }
                .map { (it / HomePaging.PAGE) * HomePaging.PAGE }.distinct()
            for (start in starts) {
                if (budget-- <= 0) return
                val r = home.videos(libraryId, month = list.month, album = list.album, query = list.query, offset = start)
                if (r is MusicApiResult.Success) {
                    slots[key] = HomePaging.merge(slots[key] ?: l, r.data.total, start, r.data.videos)
                }
            }
        }
    }

    var folders by mutableStateOf(false)
    var scrollIndex = 0
    var scrollOffset = 0

    suspend fun refresh(home: HomeVideosApiClient) {
        when (val r = home.overview(libraryId)) {
            is MusicApiResult.Success -> {
                val o = r.data
                // the months moved (a scan, an edit): the loaded pages no longer line up
                if (overview?.months != o.months || overview?.albums?.map { it.key to it.count } != o.albums.map { it.key to it.count }) slots.clear()
                overview = o
                error = null
            }
            is MusicApiResult.Error -> if (overview == null) error = r.message
            MusicApiResult.DeviceRevoked -> revoked = true
        }
    }

    /** loads the page of [list] that holds [index], once */
    suspend fun ensure(home: HomeVideosApiClient, list: HomeList, index: Int) {
        val cur = slots[list.key]
        val base = cur ?: List(list.count) { null }
        val start = HomePaging.pageFor(base, index) ?: if (cur == null) 0 else return
        lists[list.key] = list
        val tag = "${list.key}@$start"
        if (!inflight.add(tag)) return
        try {
            val r = home.videos(libraryId, month = list.month, album = list.album, query = list.query, offset = start)
            when (r) {
                is MusicApiResult.Success -> {
                    slots[list.key] = HomePaging.merge(slots[list.key] ?: base, r.data.total, start, r.data.videos)
                    pageError = null
                }
                is MusicApiResult.Error -> pageError = r.message
                MusicApiResult.DeviceRevoked -> revoked = true
            }
        } finally {
            inflight.remove(tag)
        }
    }

    /** a clip that changed (an edit, a play) put back everywhere it shows */
    fun replace(v: HomeVideo) {
        slots.keys.toList().forEach { k -> slots[k]?.let { slots[k] = HomePaging.replace(it, v) } }
        overview?.let { o ->
            if (o.onThisDay.any { it.id == v.id }) overview = o.copy(onThisDay = o.onThisDay.map { if (it.id == v.id) v else it })
        }
    }

    fun find(id: String): HomeVideo? =
        overview?.onThisDay?.firstOrNull { it.id == id } ?: slots.values.firstNotNullOfOrNull { l -> l.firstOrNull { it?.id == id } }
}

internal object HomeVideosStore {
    private val states = HashMap<String, HomeLibraryState>()
    fun get(baseUrl: String, libraryId: Int): HomeLibraryState = synchronized(states) {
        states.getOrPut("$baseUrl#$libraryId") { HomeLibraryState(libraryId) }
    }
}

/** the clip's picture; null until the server has made it (its url carries ?v=, so a new pick is a new url) */
internal fun homeThumb(home: HomeVideosApiClient, v: HomeVideo): String? = home.absolute(v.thumbUrl)

/** a clip's picture, or a steady coloured stand-in while the server is still making it */
@Composable
internal fun HomeThumb(v: HomeVideo, home: HomeVideosApiClient, modifier: Modifier = Modifier, maxPx: Int = 480) {
    val url = homeThumb(home, v)
    val dim = if (v.missing) 0.35f else 1f
    if (url == null) {
        val hue = ((v.id.toLongOrNull() ?: v.id.hashCode().toLong()) * 47 % 360).toFloat().let { if (it < 0) it + 360 else it }
        Box(
            modifier.alpha(dim).background(
                Brush.linearGradient(listOf(Color.hsv(hue, 0.45f, 0.42f), Color.hsv(hue, 0.55f, 0.16f))),
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Videocam, contentDescription = HomeVideoText.title(v), tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(22.dp)) }
    } else {
        SoulSyncArtwork(
            url = url,
            contentDescription = HomeVideoText.title(v),
            fallbackIcon = Icons.Default.Videocam,
            maxPx = maxPx,
            modifier = modifier.alpha(dim),
        )
    }
}

/**
 * pictures are made in the background after a scan: while clips on screen
 * have none, their pages are asked for again now and then
 */
@Composable
internal fun PictureWatcher(state: HomeLibraryState, home: HomeVideosApiClient) {
    LaunchedEffect(state) {
        repeat(40) {
            delay(6_000)
            if (state.awaitingPictures()) state.refreshPictures(home)
        }
    }
}

internal fun homePlayable(api: PlayerApiClient, home: HomeVideosApiClient, v: HomeVideo): ActivePlayable = ActivePlayable(
    itemType = "home",
    itemId = v.id,
    title = HomeVideoText.title(v),
    subtitle = listOf(HomeVideoText.date(v), HomeVideoText.albumName(HomeAlbum(v.albumKey, v.album))).filter { it.isNotBlank() }.joinToString(" · "),
    artworkUrl = homeThumb(home, v),
    streamUrl = api.streamUrl("home", v.id),
    isVideo = true,
    resumePositionMs = HomeVideoText.resumeMs(v),
    durationMs = (v.durationS * 1000).toLong(),
)

/** plays [v], with the clips after it in the list next */
internal fun playHomeClip(
    context: android.content.Context,
    api: PlayerApiClient,
    home: HomeVideosApiClient,
    coordinator: PlaybackCoordinator,
    v: HomeVideo,
    list: List<HomeVideo?> = emptyList(),
) {
    if (v.missing) {
        android.widget.Toast.makeText(context, "This video's file is missing.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val after = list.filterNotNull().dropWhile { it.id != v.id }.drop(1).filter { !it.missing }.take(40)
    coordinator.playVideo(homePlayable(api, home, v), after.map { homePlayable(api, home, it) })
}

/**
 * after a clip closes, its place in the lists shows how far it got: the
 * server is asked for the clip again
 */
@Composable
internal fun RefreshAfterPlay(coordinator: PlaybackCoordinator, state: HomeLibraryState, home: HomeVideosApiClient) {
    val active = coordinator.activeItem
    var lastHome by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active?.itemType, active?.itemId) {
        val was = lastHome
        lastHome = active?.takeIf { it.itemType == "home" }?.itemId
        if (was != null && was != lastHome) {
            delay(800) // the progress save goes first
            (home.detail(was) as? MusicApiResult.Success)?.data?.video?.let { fresh ->
                val old = state.find(was)
                state.replace(if (old != null) old.copy(positionS = fresh.positionS, watched = fresh.watched) else fresh)
            }
        }
    }
}

// ── the page ──────────────────────────────────────────────────────────────

@Composable
fun HomeVideosScreen(
    library: PlayerLibrary,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    isAdmin: Boolean,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val home = remember(api) { HomeVideosApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    val state = remember(api.baseUrl, library.id) { HomeVideosStore.get(api.baseUrl, library.id) }
    var loading by remember(state) { mutableStateOf(state.overview == null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(state, reload) {
        if (state.overview == null) loading = true
        state.refresh(home)
        loading = false
    }
    LaunchedEffect(state.revoked) { if (state.revoked) onDeviceRevoked() }
    RefreshAfterPlay(coordinator, state, home)
    PictureWatcher(state, home)

    val o = state.overview
    val name = library.name.ifBlank { "Home videos" }
    when {
        o == null && loading -> LoadingBox()
        o == null -> HomeMessage(
            title = "Can't load $name",
            body = state.error ?: "The server didn't answer.",
            action = "Try again",
            onAction = { state.error = null; reload++ },
        )
        o.videos <= 0 && o.months.isEmpty() && o.albums.isEmpty() -> HomeMessage(
            title = if (o.scanning) "Looking for videos" else "No home videos yet",
            body = if (o.scanning) "SoulSync is reading your home videos now. They show up here as they're found."
            else "Add a home videos folder or library on the server, and they show up here after the next scan.",
            action = if (o.scanning) "Check again" else null,
            onAction = { reload++ },
        )
        LocalIsTv.current -> TvHomeVideos(name, o, state, home, api, coordinator, onNavigate = { onNavigate(PlayerDestination.HomeVideoAlbum(library, it)) })
        else -> PhoneHomeVideos(name, o, state, home, api, coordinator, o.canEdit ?: isAdmin, onOpenAlbum = { onNavigate(PlayerDestination.HomeVideoAlbum(library, it)) })
    }
}

@Composable
fun HomeVideoAlbumScreen(
    library: PlayerLibrary,
    album: HomeAlbum,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    isAdmin: Boolean,
    onDeviceRevoked: () -> Unit,
) {
    val home = remember(api) { HomeVideosApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    val state = remember(api.baseUrl, library.id) { HomeVideosStore.get(api.baseUrl, library.id) }
    // the overview's copy is the fresher one (counts change after a scan)
    val a = state.overview?.albums?.firstOrNull { it.key == album.key } ?: album
    val list = HomeList.album(a)
    LaunchedEffect(state.revoked) { if (state.revoked) onDeviceRevoked() }
    RefreshAfterPlay(coordinator, state, home)
    PictureWatcher(state, home)
    if (LocalIsTv.current) {
        TvHomeAlbum(a, list, state, home, api, coordinator)
    } else {
        PhoneHomeAlbum(a, list, state, home, api, coordinator, state.overview?.canEdit ?: isAdmin)
    }
}

@Composable
internal fun HomeMessage(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(HomeColors.WarmBg),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = HomeColors.Warm, modifier = Modifier.size(30.dp)) }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = HomeColors.Muted, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.width(320.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) {
                Box(
                    Modifier.padding(top = 6.dp).clip(CircleShape).background(SoulSyncColors.AccentLight)
                        .dpadFocusable(CircleShape, onLightSurface = true)
                        .clickable(onClick = onAction).padding(horizontal = 20.dp, vertical = 10.dp),
                ) { Text(action, color = SoulSyncColors.OnAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
        }
    }
}

// ── phone and tablet ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneHomeVideos(
    name: String,
    o: HomeOverview,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    isAdmin: Boolean,
    onOpenAlbum: (HomeAlbum) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheetFor by remember { mutableStateOf<HomeVideo?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val listState = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    DisposableEffect(listState) {
        onDispose {
            state.scrollIndex = listState.firstVisibleItemIndex
            state.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 600.dp
        val cols = if (tablet) 5 else 3
        val albumCols = if (tablet) 4 else 2
        // no months from the server: one list of everything, still newest first
        val months = remember(o) { o.months.ifEmpty { if (o.videos > 0) listOf(HomeMonth("", o.videos)) else emptyList() } }
        val rows = remember(months, cols) { HomeTimeline.rows(months, cols) }
        val headers = remember(rows) { HomeTimeline.headerIndex(rows) }
        val searchList = if (submitted.isNotBlank()) HomeList.search(submitted) else null
        val folders = state.folders && searchList == null
        val showThisDay = !folders && searchList == null && o.onThisDay.isNotEmpty()
        // where the timeline's rows start in the list (the header, then on this day)
        val timelineStart = 1 + (if (showThisDay) 1 else 0)
        val gap = 3.dp
        val side = 16.dp

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "head") {
                PhoneHeader(
                    name = name,
                    line = HomeVideoText.libraryLine(o),
                    sources = HomeVideoText.sourcesLine(o),
                    scanning = o.scanning,
                    folders = state.folders,
                    hasAlbums = o.albums.isNotEmpty(),
                    searching = searching,
                    query = query,
                    onQuery = { query = it },
                    onSubmit = { submitted = query.trim() },
                    onSearch = {
                        searching = !searching
                        if (!searching) { query = ""; submitted = "" }
                    },
                    onFolders = { state.folders = it },
                )
            }
            if (showThisDay) {
                item(key = "thisday") {
                    Column(Modifier.padding(top = 6.dp, bottom = 18.dp)) {
                        SectionTitle("On this day", HomeVideoText.today(), Modifier.padding(horizontal = side))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = side),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(o.onThisDay, key = { it.id }) { v ->
                                MemoryCard(
                                    v = v,
                                    home = home,
                                    modifier = Modifier.width(if (tablet) 380.dp else (this@BoxWithConstraints.maxWidth * 0.82f)),
                                    onClick = { playHomeClip(context, api, home, coordinator, v, o.onThisDay) },
                                    onLongClick = { sheetFor = v },
                                )
                            }
                        }
                    }
                }
            }
            when {
                searchList != null -> {
                    val slots = state.slots[searchList.key]
                    if (slots == null) {
                        item(key = "q-load") {
                            LaunchedEffect(searchList.key, state.retryEpoch) { state.ensure(home, searchList, 0) }
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = SoulSyncColors.AccentLight, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                            }
                        }
                    } else if (slots.isEmpty()) {
                        item(key = "q-none") {
                            Text(
                                "Nothing matches “$submitted”.",
                                color = HomeColors.Muted, fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = side, vertical = 30.dp),
                            )
                        }
                    } else {
                        val n = (slots.size + cols - 1) / cols
                        items(n, key = { "q-${searchList.key}-$it" }) { r ->
                            ClipRow(
                                list = searchList, start = r * cols, end = minOf(r * cols + cols, slots.size), cols = cols, gap = gap, side = side,
                                state = state, home = home,
                                onPlay = { v -> playHomeClip(context, api, home, coordinator, v, state.slots[searchList.key].orEmpty()) },
                                onLong = { sheetFor = it },
                            )
                        }
                    }
                }
                folders -> {
                    val chunks = o.albums.chunked(albumCols)
                    items(chunks.size, key = { "al-$it" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = side).padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            chunks[r].forEach { a ->
                                AlbumCard(a, home, state, Modifier.weight(1f)) { onOpenAlbum(a) }
                            }
                            repeat(albumCols - chunks[r].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                else -> {
                    rows.forEach { row ->
                        when (row) {
                            is HomeTimelineRow.Header -> stickyHeader(key = row.key) {
                                MonthHeader(row.month, row.count, side)
                            }
                            is HomeTimelineRow.Cells -> item(key = row.key) {
                                val m = months.firstOrNull { it.month == row.month } ?: HomeMonth(row.month, row.end)
                                val list = HomeList.month(m)
                                ClipRow(
                                    list = list, start = row.start, end = row.end, cols = cols, gap = gap, side = side,
                                    state = state, home = home,
                                    onPlay = { v -> playHomeClip(context, api, home, coordinator, v, state.slots[list.key].orEmpty()) },
                                    onLong = { sheetFor = it },
                                )
                            }
                        }
                    }
                }
            }
            state.pageError?.let { msg ->
                item(key = "page-error") {
                    RetryLine(msg) { state.pageError = null; state.retryEpoch++ }
                }
            }
        }

        if (!folders && searchList == null && months.size > 2) {
            MonthScrubber(
                listState = listState,
                rows = rows,
                headers = headers,
                start = timelineStart,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(44.dp),
            )
        }
    }

    sheetFor?.let { v ->
        HomeVideoSheet(
            video = state.find(v.id) ?: v,
            home = home,
            api = api,
            isAdmin = isAdmin,
            onPlay = {
                sheetFor = null
                playHomeClip(context, api, home, coordinator, it)
            },
            onSaved = { saved, dateChanged ->
                state.replace(saved)
                // a new date moves it to another month (or out of on this day): the page loads again
                if (dateChanged) scope.launch {
                    state.slots.clear()
                    state.refresh(home)
                }
            },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun PhoneHeader(
    name: String,
    line: String,
    sources: String?,
    scanning: Boolean,
    folders: Boolean,
    hasAlbums: Boolean,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onSearch: () -> Unit,
    onFolders: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, color = HomeColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                if (sources != null) Text(sources, color = HomeColors.Muted.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (scanning) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(color = HomeColors.Warm, strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
                        Text("Looking for new videos", color = HomeColors.Warm, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (searching) Color.White else HomeColors.Raise)
                    .border(1.dp, HomeColors.Line, CircleShape).clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (searching) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searching) "Close search" else "Search home videos",
                    tint = if (searching) SoulSyncColors.BgPrimary else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Search names and notes", color = HomeColors.Muted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = HomeColors.Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                shape = RoundedCornerShape(14.dp),
                colors = homeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (hasAlbums) {
            Segmented(
                options = listOf("Timeline", "Folders"),
                selected = if (folders) 1 else 0,
                onSelect = { onFolders(it == 1) },
            )
        }
    }
}

@Composable
internal fun homeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = SoulSyncColors.BgPrimary,
    unfocusedContainerColor = SoulSyncColors.BgPrimary,
    focusedBorderColor = SoulSyncColors.AccentLight,
    unfocusedBorderColor = HomeColors.Line2,
    cursorColor = SoulSyncColors.AccentLight,
)

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(HomeColors.Raise).border(1.dp, HomeColors.Line, CircleShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(label, color = if (on) SoulSyncColors.BgPrimary else HomeColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, small: String?, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
        if (!small.isNullOrBlank()) Text(small, color = HomeColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun MonthHeader(month: String, count: Int, side: androidx.compose.ui.unit.Dp) {
    Row(
        Modifier.fillMaxWidth().background(SoulSyncColors.BgPrimary).padding(horizontal = side, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (month.isEmpty()) "All videos" else HomeVideoText.monthTitle(month), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(HomeVideoText.count(count), color = HomeColors.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/** one row of the grid: loads its page when it shows */
@Composable
private fun ClipRow(
    list: HomeList,
    start: Int,
    end: Int,
    cols: Int,
    gap: androidx.compose.ui.unit.Dp,
    side: androidx.compose.ui.unit.Dp,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    onPlay: (HomeVideo) -> Unit,
    onLong: (HomeVideo) -> Unit,
) {
    val slots = state.slots[list.key]
    val needs = slots == null || (start until end).any { slots.getOrNull(it) == null && it < slots.size }
    if (needs) LaunchedEffect(list.key, start, state.retryEpoch) { state.ensure(home, list, start) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = side).padding(bottom = gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        for (i in start until start + cols) {
            Box(Modifier.weight(1f)) {
                if (i < end) {
                    val v = slots?.getOrNull(i)
                    if (v != null) {
                        ClipCell(v, home, onClick = { onPlay(v) }, onLongClick = { onLong(v) })
                    } else if (slots == null || i < slots.size) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(HomeColors.Raise))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCell(v: HomeVideo, home: HomeVideosApiClient, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val title = HomeVideoText.title(v)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClickLabel = "Play",
                onLongClickLabel = "More",
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        HomeThumb(v, home, Modifier.fillMaxSize(), maxPx = 360)
        val day = HomeVideoText.day(v)
        if (day.isNotBlank()) Chip(day, Modifier.align(Alignment.TopStart).padding(4.dp))
        val dur = if (v.missing) "Missing" else HomeVideoText.duration(v.durationS)
        if (dur.isNotBlank()) Chip(dur, Modifier.align(Alignment.BottomEnd).padding(4.dp), tabular = true)
        v.progressFraction?.let { f ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(SoulSyncColors.AccentLight))
        }
        if (v.watched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.35f)))
    }
}

@Composable
internal fun Chip(text: String, modifier: Modifier = Modifier, tabular: Boolean = false, big: Boolean = false) {
    Text(
        text,
        color = Color.White,
        fontSize = if (big) 12.sp else 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        style = if (tabular) androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum") else androidx.compose.ui.text.TextStyle.Default,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(HomeColors.Badge).padding(horizontal = if (big) 7.dp else 5.dp, vertical = 1.dp),
    )
}

/** a large On this day card: the picture, how long ago, the name and date */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(v: HomeVideo, home: HomeVideosApiClient, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val title = HomeVideoText.title(v)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }),
    ) {
        HomeThumb(v, home, Modifier.fillMaxSize(), maxPx = 720)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        HomeVideoText.duration(v.durationS).takeIf { it.isNotBlank() }?.let { Chip(it, Modifier.align(Alignment.TopEnd).padding(10.dp), tabular = true, big = true) }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            HomeVideoText.yearsAgo(v)?.let { YearsPill(it) }
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(HomeVideoText.date(v), v.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        v.progressFraction?.let { f ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(SoulSyncColors.AccentLight))
        }
    }
}

@Composable
internal fun YearsPill(text: String, big: Boolean = false) {
    Text(
        text,
        color = HomeColors.Warm,
        fontSize = if (big) 13.sp else 11.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp).clip(CircleShape).background(HomeColors.WarmBg).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** a folder as a stack of its pictures */
@Composable
internal fun AlbumStack(
    a: HomeAlbum,
    home: HomeVideosApiClient,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 12.dp,
    /** a cover's url: the loaded clip's own (it carries ?v=) when there is one */
    state: HomeLibraryState? = null,
    cover: (String) -> String? = { id -> state?.find(id)?.let { homeThumb(home, it) } ?: home.thumbUrl(id) },
) {
    val covers = a.covers.take(3)
    val density = LocalDensity.current
    Box(modifier.aspectRatio(4f / 3f).padding(top = 8.dp, end = 10.dp)) {
        // the back two lean out behind the front one
        val back = listOf(Triple(10, -8, 4f) to 0.55f, Triple(5, -4, 2f) to 0.8f)
        back.forEachIndexed { i, (t, fade) ->
            val url = covers.getOrNull(covers.size - 1 - i)?.let(cover)
            Box(
                Modifier.fillMaxSize()
                    .offset { with(density) { IntOffset(t.first.dp.roundToPx(), t.second.dp.roundToPx()) } }
                    .graphicsLayer { rotationZ = t.third; alpha = fade }
                    .clip(RoundedCornerShape(radius))
                    .border(1.dp, HomeColors.Line2, RoundedCornerShape(radius)),
            ) {
                if (url != null && covers.size > 1 + i) {
                    SoulSyncArtwork(url = url, contentDescription = null, fallbackIcon = Icons.Default.PhotoLibrary, maxPx = 360, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(HomeColors.Raise2))
                }
            }
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(radius)).border(1.dp, HomeColors.Line2, RoundedCornerShape(radius))) {
            SoulSyncArtwork(
                url = covers.firstOrNull()?.let(cover),
                contentDescription = HomeVideoText.albumName(a),
                fallbackIcon = Icons.Default.PhotoLibrary,
                maxPx = 480,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AlbumCard(a: HomeAlbum, home: HomeVideosApiClient, state: HomeLibraryState, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlbumStack(a, home, Modifier.fillMaxWidth(), state = state)
        Column {
            Text(HomeVideoText.albumName(a), color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeVideoText.albumLine(a), color = HomeColors.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RetryLine(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(HomeColors.Raise)
            .border(1.dp, HomeColors.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Some videos didn't load. $message", color = HomeColors.Muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(
            "Try again",
            color = SoulSyncColors.AccentLight,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clip(CircleShape).dpadFocusable(CircleShape).clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * the fast scroller: shows while the timeline moves, and dragging it jumps
 * month by month with the month in a bubble
 */
@Composable
private fun MonthScrubber(
    listState: LazyListState,
    rows: List<HomeTimelineRow>,
    headers: Map<String, Int>,
    start: Int,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val moving = dragging || listState.isScrollInProgress
    LaunchedEffect(moving) {
        if (moving) shown = true else { delay(1400); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 300), label = "scrubber")
    val fraction by remember(rows, start) {
        derivedStateOf {
            val i = (listState.firstVisibleItemIndex - start).coerceAtLeast(0)
            if (rows.size <= 1) 0f else (i.toFloat() / (rows.size - 1)).coerceIn(0f, 1f)
        }
    }
    val month by remember(rows, start) {
        derivedStateOf { HomeTimeline.monthAt(rows, (listState.firstVisibleItemIndex - start).coerceAtLeast(0)) }
    }
    if (alpha <= 0.01f && !shown) return
    BoxWithConstraints(modifier.alpha(alpha)) {
        val trackPx = with(LocalDensity.current) { (maxHeight - 120.dp).toPx().coerceAtLeast(1f) }
        val topPad = 40.dp
        var lastMonth by remember { mutableStateOf<String?>(null) }
        fun jump(y: Float) {
            val f = (y / trackPx).coerceIn(0f, 1f)
            val idx = (f * (rows.size - 1)).roundToInt()
            val m = HomeTimeline.monthAt(rows, idx) ?: return
            val target = headers[m] ?: idx
            if (m != lastMonth) {
                lastMonth = m
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { listState.scrollToItem(start + target) }
            }
        }
        val thumbY = with(LocalDensity.current) { ((trackPx - 40.dp.toPx()).coerceAtLeast(0f) * fraction).toDp() }
        Box(
            Modifier.fillMaxSize().padding(top = topPad, bottom = 80.dp)
                .pointerInput(rows, headers, start) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; lastMonth = null; jump(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); jump(change.position.y) },
                    )
                },
        ) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = thumbY).padding(end = 4.dp)
                    .size(width = 6.dp, height = 40.dp).clip(CircleShape)
                    .background(if (dragging) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.7f)),
            )
        }
        if (dragging && month != null) {
            Text(
                if (month!!.isEmpty()) "All" else HomeVideoText.monthShort(month!!),
                color = SoulSyncColors.BgPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset(x = (-24).dp, y = topPad + thumbY + 4.dp)
                    .wrapContentWidthUnbounded()
                    .clip(RoundedCornerShape(10.dp)).background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** lets the month bubble be wider than the thin scrubber it hangs from */
private fun Modifier.wrapContentWidthUnbounded(): Modifier =
    this.then(Modifier.wrapContentSize(align = Alignment.CenterEnd, unbounded = true))

// ── an album ──────────────────────────────────────────────────────────────

@Composable
private fun PhoneHomeAlbum(
    a: HomeAlbum,
    list: HomeList,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    isAdmin: Boolean,
) {
    val context = LocalContext.current
    var sheetFor by remember { mutableStateOf<HomeVideo?>(null) }
    val slots = state.slots[list.key]
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (maxWidth >= 600.dp) 5 else 3
        val side = 16.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = side, end = side, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumStack(a, home, Modifier.width(92.dp), radius = 10.dp, state = state)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(HomeVideoText.albumName(a), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(HomeVideoText.albumLine(a), color = HomeColors.Muted, fontSize = 13.sp)
                        if (a.path.contains('/') || a.path.contains('\\')) {
                            Text(a.path, color = HomeColors.Muted.copy(alpha = 0.7f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        GroupDownloadButton(
                            api = api,
                            target = DownloadTarget(type = "home_album", id = a.key, title = HomeVideoText.albumName(a), subtitle = HomeVideoText.albumLine(a)),
                        )
                    }
                }
            }
            if (slots != null && slots.isEmpty()) {
                item(key = "none") { Text("This album is empty.", color = HomeColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = side, vertical = 30.dp)) }
            } else {
                val count = slots?.size ?: a.count.coerceAtLeast(1)
                val n = (count + cols - 1) / cols
                items(n, key = { "ar-$it" }) { r ->
                    ClipRow(
                        list = list, start = r * cols, end = minOf(r * cols + cols, count), cols = cols, gap = 3.dp, side = side,
                        state = state, home = home,
                        onPlay = { v -> playHomeClip(context, api, home, coordinator, v, state.slots[list.key].orEmpty()) },
                        onLong = { sheetFor = it },
                    )
                }
            }
            state.pageError?.let { msg -> item(key = "page-error") { RetryLine(msg) { state.pageError = null; state.retryEpoch++ } } }
        }
    }
    sheetFor?.let { v ->
        HomeVideoSheet(
            video = state.find(v.id) ?: v,
            home = home,
            api = api,
            isAdmin = isAdmin,
            onPlay = {
                sheetFor = null
                playHomeClip(context, api, home, coordinator, it, state.slots[list.key].orEmpty())
            },
            onSaved = { saved, _ -> state.replace(saved) },
            onDismiss = { sheetFor = null },
        )
    }
}

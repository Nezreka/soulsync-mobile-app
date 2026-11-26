package net.ssync.app.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.HomeAlbum
import net.ssync.player.HomeMonth
import net.ssync.player.HomeMonths
import net.ssync.player.HomeOverview
import net.ssync.player.HomeVideo
import net.ssync.player.HomeVideoText
import net.ssync.player.HomeVideosApiClient
import net.ssync.player.PlayerApiClient

// home videos on a tv: remote first. a hero On this day row, the albums,
// then a row per month, newest first, with a month and year jump list on
// the left. select plays; there's no editing on a tv.

private val TV_HERO_W = 380.dp
private val TV_CLIP_W = 216.dp
private val TV_ALBUM_W = 196.dp

@Composable
internal fun TvHomeVideos(
    name: String,
    o: HomeOverview,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (HomeAlbum) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val months = remember(o) { o.months.ifEmpty { if (o.videos > 0) listOf(HomeMonth("", o.videos)) else emptyList() } }
    val listState = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    DisposableEffect(listState) {
        onDispose {
            state.scrollIndex = listState.firstVisibleItemIndex
            state.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    // the first card of every row, so the jump list can land on it
    val rowFocus = remember(o) { HashMap<String, FocusRequester>() }
    fun req(key: String) = rowFocus.getOrPut(key) { FocusRequester() }

    // list positions: the header, on this day, albums, then the months
    val hasDay = o.onThisDay.isNotEmpty()
    val hasAlbums = o.albums.isNotEmpty()
    val dayIndex = 1
    val albumsIndex = 1 + (if (hasDay) 1 else 0)
    val monthStart = albumsIndex + (if (hasAlbums) 1 else 0)
    val firstKey = when {
        hasDay -> "day"
        hasAlbums -> "albums"
        else -> months.firstOrNull()?.let { "m:${it.month}" }
    }
    var currentRow by remember { mutableStateOf(firstKey) }

    LaunchedEffect(o) {
        // land on the first card when the page opens (not when coming back to a place)
        if (state.scrollIndex == 0 && firstKey != null) {
            delay(220)
            runCatching { req(firstKey).requestFocus() }
        }
    }

    fun jump(key: String, index: Int) {
        scope.launch {
            listState.animateScrollToItem(index)
            delay(80)
            runCatching { req(key).requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize()) {
        TvJumpList(
            hasDay = hasDay,
            hasAlbums = hasAlbums,
            months = months,
            current = currentRow,
            onDay = { jump("day", dayIndex) },
            onAlbums = { jump("albums", albumsIndex) },
            onMonth = { m -> jump("m:${m.month}", monthStart + months.indexOf(m)) },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 60.dp),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 10.dp)) {
                    Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                    Text(
                        listOfNotNull(HomeVideoText.libraryLine(o), HomeVideoText.sourcesLine(o)).joinToString(" · "),
                        color = HomeColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasDay) {
                item(key = "day") {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        SectionTitle("On this day", HomeVideoText.today(), Modifier.padding(start = 8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            items(o.onThisDay, key = { it.id }) { v ->
                                TvClipCard(
                                    v = v,
                                    home = home,
                                    width = TV_HERO_W,
                                    hero = true,
                                    modifier = if (v.id == o.onThisDay.first().id) Modifier.focusRequester(req("day")) else Modifier,
                                    onFocused = { currentRow = "day" },
                                    onClick = { playHomeClip(context, api, home, coordinator, v, o.onThisDay) },
                                )
                            }
                        }
                    }
                }
            }
            if (hasAlbums) {
                item(key = "albums") {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        SectionTitle("Albums", "each folder is an album", Modifier.padding(start = 8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(o.albums, key = { it.key }) { a ->
                                TvAlbumCard(
                                    a = a,
                                    home = home,
                                    state = state,
                                    modifier = if (a.key == o.albums.first().key) Modifier.focusRequester(req("albums")) else Modifier,
                                    onFocused = { currentRow = "albums" },
                                    onClick = { onNavigate(a) },
                                )
                            }
                        }
                    }
                }
            }
            items(months, key = { "m:${it.month}" }) { m ->
                val key = "m:${m.month}"
                TvMonthRow(
                    m = m,
                    state = state,
                    home = home,
                    firstFocus = req(key),
                    onFocused = { currentRow = key },
                    onPlay = { v, list -> playHomeClip(context, api, home, coordinator, v, list) },
                )
            }
            state.pageError?.let { msg -> item(key = "page-error") { RetryLine(msg) { state.pageError = null; state.retryEpoch++ } } }
        }
    }
}

/** the left column: On this day, Albums, then each year with its months */
@Composable
private fun TvJumpList(
    hasDay: Boolean,
    hasAlbums: Boolean,
    months: List<HomeMonth>,
    current: String?,
    onDay: () -> Unit,
    onAlbums: () -> Unit,
    onMonth: (HomeMonth) -> Unit,
) {
    val years = remember(months) { HomeMonths.years(months) }
    val listState = rememberLazyListState()
    // keep the month in view as the rows are browsed
    LaunchedEffect(current) {
        val idx = jumpIndex(hasDay, hasAlbums, years, current) ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == idx }) runCatching { listState.animateScrollToItem((idx - 3).coerceAtLeast(0)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.width(132.dp).fillMaxHeight().padding(end = 6.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (hasDay) item(key = "j-day") { JumpItem("On this day", null, current == "day", onDay) }
        if (hasAlbums) item(key = "j-albums") { JumpItem("Albums", null, current == "albums", onAlbums) }
        years.forEach { (year, ms) ->
            item(key = "y-$year") {
                Text(
                    if (year == 0) "Other" else year.toString(),
                    color = HomeColors.Warm,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                )
            }
            items(ms, key = { "j-${it.month}" }) { m ->
                val label = if (m.month.isEmpty()) "All videos" else HomeVideoText.monthTitle(m.month).substringBefore(' ')
                JumpItem(label, m.count, current == "m:${m.month}") { onMonth(m) }
            }
        }
    }
}

private fun jumpIndex(hasDay: Boolean, hasAlbums: Boolean, years: List<Pair<Int, List<HomeMonth>>>, key: String?): Int? {
    var i = 0
    if (hasDay) { if (key == "day") return i; i++ }
    if (hasAlbums) { if (key == "albums") return i; i++ }
    for ((_, ms) in years) {
        i++ // the year label
        for (m in ms) {
            if (key == "m:${m.month}") return i
            i++
        }
    }
    return null
}

@Composable
private fun JumpItem(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
            .background(if (on) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .dpadFocusable(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (on) Color.White else SoulSyncColors.TextSoft,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) Text("$count", color = HomeColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun TvMonthRow(
    m: HomeMonth,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    firstFocus: FocusRequester,
    onFocused: () -> Unit,
    onPlay: (HomeVideo, List<HomeVideo?>) -> Unit,
) {
    val list = HomeList.month(m)
    val slots = state.slots[list.key]
    val count = slots?.size ?: m.count
    if (slots == null) LaunchedEffect(list.key, state.retryEpoch) { state.ensure(home, list, 0) }
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (m.month.isEmpty()) "All videos" else HomeVideoText.monthTitle(m.month), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(HomeVideoText.count(m.count), color = HomeColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 1.dp))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(count, key = { "${list.key}-$it" }) { i ->
                val v = slots?.getOrNull(i)
                if (v == null && slots != null) LaunchedEffect(list.key, i / net.ssync.player.HomePaging.PAGE, state.retryEpoch) { state.ensure(home, list, i) }
                TvClipCard(
                    v = v,
                    home = home,
                    width = TV_CLIP_W,
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onFocused = onFocused,
                    onClick = { v?.let { onPlay(it, state.slots[list.key].orEmpty()) } },
                )
            }
        }
    }
}

/**
 * a 16:9 card for the remote: it grows when focused and shows the date and
 * length; the name sits under it. a card still loading is focusable too, so
 * the row can be crossed quickly.
 */
@Composable
private fun TvClipCard(
    v: HomeVideo?,
    home: HomeVideosApiClient,
    width: Dp,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    fill: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(200), label = "tvClipScale")
    // the date and length fade in on focus
    val reveal by animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) 160 else 120), label = "tvClipReveal")
    val shape = RoundedCornerShape(if (hero) 16.dp else 12.dp)
    val title = v?.let { HomeVideoText.title(it) }.orEmpty()
    Column(
        (if (fill) Modifier.fillMaxWidth() else Modifier.width(width))
            .zIndex(if (focused) 1f else 0f),
    ) {
        Box(
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else HomeColors.Line, shape)
                .background(HomeColors.Raise)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            if (v != null) {
                HomeThumb(v, home, Modifier.fillMaxSize(), maxPx = if (hero) 720 else 480)
                // a missing clip says so without focus
                if (v.missing && !hero) Chip("Missing", Modifier.align(Alignment.TopStart).padding(6.dp), big = true)
                if (hero) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        HomeVideoText.yearsAgo(v)?.let { YearsPill(it, big = true) }
                        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(HomeVideoText.date(v), if (focused) HomeVideoText.duration(v.durationS) else "").filter { it.isNotBlank() }.joinToString(" · "),
                            color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, maxLines = 1,
                        )
                    }
                } else {
                    if (reveal > 0.01f) {
                        Box(Modifier.fillMaxSize().alpha(reveal).background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f)))) {
                            Row(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(HomeVideoText.date(v), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                val d = if (v.missing) "Missing" else HomeVideoText.duration(v.durationS)
                                if (d.isNotBlank()) Chip(d, tabular = true, big = true)
                            }
                        }
                    }
                }
                v.progressFraction?.let { f ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(SoulSyncColors.AccentLight))
                }
            }
        }
        if (!hero) {
            Spacer(Modifier.height(8.dp))
            Text(
                title.ifBlank { " " },
                color = if (focused) Color.White else SoulSyncColors.TextSoft,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvAlbumCard(a: HomeAlbum, home: HomeVideosApiClient, state: HomeLibraryState, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, tween(200), label = "tvAlbumScale")
    Column(Modifier.width(TV_ALBUM_W).zIndex(if (focused) 1f else 0f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            AlbumStack(a, home, Modifier.fillMaxWidth(), state = state)
            if (focused) {
                Box(Modifier.matchParentSize().padding(top = 8.dp, end = 10.dp).border(2.5.dp, Color.White, RoundedCornerShape(12.dp)))
            }
        }
        Column {
            Text(HomeVideoText.albumName(a), color = if (focused) Color.White else SoulSyncColors.TextSoft, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeVideoText.albumLine(a), color = HomeColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** an album on a tv: its clips in a grid, oldest first */
@Composable
internal fun TvHomeAlbum(
    a: HomeAlbum,
    list: HomeList,
    state: HomeLibraryState,
    home: HomeVideosApiClient,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
) {
    val context = LocalContext.current
    val slots = state.slots[list.key]
    val count = slots?.size ?: a.count
    val first = remember { FocusRequester() }
    if (slots == null) LaunchedEffect(list.key, state.retryEpoch) { state.ensure(home, list, 0) }
    LaunchedEffect(slots != null) {
        if (slots != null && slots.isNotEmpty()) {
            delay(200)
            runCatching { first.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumStack(a, home, Modifier.width(120.dp), radius = 10.dp, state = state)
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(HomeVideoText.albumName(a), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text(HomeVideoText.albumLine(a), color = HomeColors.Muted, fontSize = 14.sp)
                }
            }
        }
        if (slots != null && slots.isEmpty()) {
            item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                Text("This album is empty.", color = HomeColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 30.dp))
            }
        }
        items(count, key = { "${list.key}-$it" }) { i ->
            val v = slots?.getOrNull(i)
            if (v == null && slots != null) LaunchedEffect(list.key, i / net.ssync.player.HomePaging.PAGE, state.retryEpoch) { state.ensure(home, list, i) }
            Box(Modifier.padding(vertical = 6.dp)) {
                TvClipCard(
                    v = v,
                    home = home,
                    width = TV_CLIP_W,
                    fill = true,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { v?.let { playHomeClip(context, api, home, coordinator, it, state.slots[list.key].orEmpty()) } },
                )
            }
        }
        state.pageError?.let { msg ->
            item(key = "page-error", span = { GridItemSpan(maxLineSpan) }) { RetryLine(msg) { state.pageError = null; state.retryEpoch++ } }
        }
    }
}

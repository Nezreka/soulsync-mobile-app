package net.ssync.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.DownloadQueue
import net.ssync.player.DownloadTask
import net.ssync.player.MusicApiResult
import net.ssync.player.OfflineApiClient
import net.ssync.player.OfflineDevice
import net.ssync.player.OfflineDeviceItem
import net.ssync.player.OfflineRule
import net.ssync.player.OfflineFormat
import net.ssync.player.OfflineGroup
import net.ssync.player.OfflineGrouping
import net.ssync.player.OfflineShelf
import net.ssync.player.OfflineSort
import net.ssync.player.OfflineStorage
import net.ssync.player.PlayerApiClient
import net.ssync.player.TaskState
import java.io.File

private fun shelfColor(s: OfflineShelf): Color = when (s) {
    OfflineShelf.TV -> Color(0xFF7C9CFF)
    OfflineShelf.MOVIES -> Color(0xFF2FD59A)
    OfflineShelf.MUSIC -> Color(0xFFF472B6)
    OfflineShelf.BOOKS -> Color(0xFFFFB547)
    OfflineShelf.PODCASTS -> Color(0xFFA78BFA)
    OfflineShelf.YOUTUBE -> Color(0xFFF87171)
    OfflineShelf.HOME -> Color(0xFFFFB46B)
}

// the screen's kinds in order: tv shows, movies, youtube, music, audiobooks, podcasts, home videos
private val SECTION_ORDER = OfflineGrouping.SECTIONS

/** newest first or a to z, and which kinds are folded away: this phone remembers */
private class DownloadsView(context: android.content.Context) {
    private val sp = context.getSharedPreferences("soulsync_downloads_view", android.content.Context.MODE_PRIVATE)
    var sort by mutableStateOf(if (sp.getString("sort", "") == "az") OfflineSort.AZ else OfflineSort.RECENT)
        private set
    var folded by mutableStateOf(sp.getStringSet("folded", emptySet()).orEmpty().toSet())
        private set

    fun sortBy(next: OfflineSort) {
        sort = next
        sp.edit().putString("sort", if (next == OfflineSort.AZ) "az" else "recent").apply()
    }

    fun toggle(shelf: OfflineShelf) {
        folded = if (shelf.name in folded) folded - shelf.name else folded + shelf.name
        sp.edit().putStringSet("folded", folded).apply()
    }
}

/** "12 episodes", "3 songs": what a kind holds, in its own words */
private fun kindCount(shelf: OfflineShelf, n: Int): String {
    val word = when (shelf) {
        OfflineShelf.TV, OfflineShelf.PODCASTS -> "episode"
        OfflineShelf.MOVIES -> "movie"
        OfflineShelf.BOOKS -> "book"
        OfflineShelf.MUSIC -> "song"
        OfflineShelf.YOUTUBE, OfflineShelf.HOME -> "video"
    }
    return "$n $word${if (n == 1) "" else "s"}"
}

/** play a downloaded item from its file, with the rest of its group after it for audio */
internal fun playDownloaded(coordinator: PlaybackCoordinator, api: PlayerApiClient, task: DownloadTask, group: List<DownloadTask> = emptyList()) {
    fun toPlayable(t: DownloadTask) = ActivePlayable(
        itemType = t.itemType,
        itemId = t.itemId,
        title = t.title,
        subtitle = t.subtitle.ifBlank { t.groupTitle },
        artworkUrl = artModel(api, t),
        streamUrl = android.net.Uri.fromFile(File(t.localPath)).toString(),
        isVideo = t.isVideo,
        // a finished one starts over; otherwise it picks up where it stopped
        resumePositionMs = if (t.watched || t.positionS < 10) 0L else (t.positionS * 1000).toLong(),
        durationMs = (t.durationS * 1000).toLong(),
        parentId = t.parentId,
    )
    val ready = group.filter { it.state == TaskState.DOWNLOADED && File(it.localPath).exists() }
    if (task.isVideo) {
        val after = ready.dropWhile { it.key != task.key }.drop(1).filter { it.isVideo }.map(::toPlayable)
        coordinator.playVideo(toPlayable(task), after)
    } else {
        val after = ready.dropWhile { it.key != task.key }.drop(1).map(::toPlayable)
        coordinator.playAudio(toPlayable(task), after)
    }
}

/**
 * the Downloads screen (approved mockup): a storage bar by kind against the
 * limit, filter chips, what's downloading with progress and speed, then
 * shows, movies, music and books as cards with their smart tags. on a tv it
 * manages the profile's phones instead: a tv keeps no downloads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    offlineMode: Boolean = false,
) {
    val isTv = LocalIsTv.current
    if (isTv) {
        TvDeviceDownloads(api)
        return
    }
    val context = LocalContext.current
    LaunchedEffect(context) { OfflineDownloads.init(context) }
    val tasks by OfflineDownloads.tasks.collectAsState()
    val live by OfflineDownloads.live.collectAsState()
    val settings by OfflineDownloads.settings.collectAsState()
    val rules by OfflineDownloads.rules.collectAsState()
    val online by OfflineSync.online.collectAsState()
    val waiting by DownloadService.waitingReason.collectAsState()
    var filter by remember { mutableStateOf<OfflineShelf?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var menuTask by remember { mutableStateOf<DownloadTask?>(null) }
    var freeUp by remember { mutableStateOf<List<DownloadTask>?>(null) }
    val view = remember(context) { DownloadsView(context) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tasks.size) { tick++ }

    // the profile's other phones and tablets, managed from here like the web player does
    val offlineApi = remember(api) { OfflineApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    val myId = remember(context) { OfflineDownloads.deviceId(context) }
    var others by remember { mutableStateOf<List<OfflineDevice>>(emptyList()) }
    var shown by remember { mutableStateOf<String?>(null) }
    var devicesTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(online, devicesTick) {
        if (!online) return@LaunchedEffect
        (offlineApi.devices() as? MusicApiResult.Success)?.data?.let { list ->
            others = list.filter { it.deviceId != myId && it.platform != "web" }
        }
    }
    val remoteDev = others.firstOrNull { it.deviceId == shown }?.takeIf { online && !offlineMode }

    val free = remember(tick, tasks) { OfflineDownloads.freeBytes() }
    val used = tasks.sumOf { it.diskBytes }
    val byShelf = OfflineStorage.bytesByShelf(tasks)
    val pending = DownloadQueue.pending(tasks).filter { filter == null || it.shelf == filter }
    val done = tasks.filter { it.state == TaskState.DOWNLOADED }
    // what's on the phone, filed by kind (a picked chip shows just that kind)
    val sections = OfflineGrouping.sections(done, view.sort).filter { filter == null || it.shelf == filter }
    val shelvesWithContent = tasks.map { it.shelf }.toSet()
    val watchedAll = OfflineStorage.freeUpPlan(tasks, 0)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0D14), SoulSyncColors.BgPrimary))),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                CircleIcon(Icons.Default.Settings, "Download settings") { showSettings = true }
            }
        }
        if (others.isNotEmpty() && online && !offlineMode) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        DeviceChip("This phone", "${OfflineFormat.bytes(used)}${if (settings.limitBytes > 0) " of ${OfflineFormat.bytes(settings.limitBytes)}" else ""}", shown == null) { shown = null }
                    }
                    items(others, key = { it.deviceId }) { d ->
                        DeviceChip(
                            d.deviceName.ifBlank { "Phone" },
                            listOfNotNull(
                                if (d.limitBytes > 0) "${OfflineFormat.bytes(d.usedBytes)} of ${OfflineFormat.bytes(d.limitBytes)}" else OfflineFormat.bytes(d.usedBytes),
                                d.lastSeen?.let { "seen ${seenAgo(it)}" },
                            ).joinToString(" · "),
                            shown == d.deviceId,
                        ) { shown = d.deviceId; devicesTick++ }
                    }
                }
            }
        }
        if (remoteDev != null) {
            item(key = "remote-" + remoteDev.deviceId) {
                RemoteDeviceCard(api, offlineApi, remoteDev, onChanged = { devicesTick++ })
            }
            return@LazyColumn
        }
        if (offlineMode || !online) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Brush.horizontalGradient(listOf(SoulSyncColors.Accent.copy(alpha = 0.14f), SoulSyncColors.Accent.copy(alpha = 0.03f))))
                        .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.AirplanemodeActive, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(18.dp))
                    Text(
                        buildString {
                            append("Offline. ")
                            append("Showing what's on this phone. Plays and positions sync when you're back.")
                        },
                        color = Color.White, fontSize = 13.sp,
                    )
                }
            }
        }

        // storage by kind against the limit
        item {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DlColors.Raise)
                    .border(1.dp, DlColors.Line, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("On this phone", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(OfflineFormat.bytes(used), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (settings.limitBytes > 0) "  of ${OfflineFormat.bytes(settings.limitBytes)} limit" else "  · ${OfflineFormat.bytes(free)} free",
                        color = DlColors.Muted, fontSize = 12.sp,
                    )
                }
                val scale = if (settings.limitBytes > 0) settings.limitBytes else (used + free).coerceAtLeast(1L)
                Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(DlColors.Raise2)) {
                    SECTION_ORDER.forEach { (shelf, _) ->
                        val b = byShelf[shelf] ?: 0L
                        if (b > 0) {
                            val w = (b.toFloat() / scale).coerceIn(0.004f, 1f)
                            Box(Modifier.fillMaxHeight().weight(w).background(shelfColor(shelf)))
                        }
                    }
                    val rest = 1f - (used.toFloat() / scale).coerceIn(0f, 1f)
                    if (rest > 0.001f) Spacer(Modifier.weight(rest))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SECTION_ORDER.forEach { (shelf, _) ->
                        val b = byShelf[shelf] ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(shelfColor(shelf)))
                            Text("${SECTION_ORDER.first { it.first == shelf }.second} ${OfflineFormat.bytes(b)}", color = DlColors.Muted, fontSize = 11.5.sp)
                        }
                    }
                    if (byShelf.isEmpty()) Text("Nothing downloaded yet", color = DlColors.Muted, fontSize = 11.5.sp)
                }
                if (done.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (watchedAll.isNotEmpty()) "${watchedAll.size} watched · ${OfflineFormat.bytes(watchedAll.sumOf { it.diskBytes })} you could free" else "Nothing watched to clear yet",
                            color = DlColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        PillButton("Free up space", Icons.Default.CleaningServices) {
                            // watched first; with nothing watched, enough of the oldest for a quarter of what's used
                            freeUp = OfflineStorage.freeUpPlan(tasks, if (watchedAll.isEmpty()) (used / 4).coerceAtLeast(1L) else 0L)
                        }
                    }
                }
            }
        }

        // kind chips (show just that kind) and newest first or a to z
        if (done.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shelvesWithContent.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { FilterChipPill("All", filter == null) { filter = null } }
                            items(SECTION_ORDER.filter { it.first in shelvesWithContent }) { (shelf, label) ->
                                FilterChipPill(label, filter == shelf) { filter = if (filter == shelf) null else shelf }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort", color = DlColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 2.dp))
                        FilterChipPill("Recent", view.sort == OfflineSort.RECENT) { view.sortBy(OfflineSort.RECENT) }
                        FilterChipPill("A-Z", view.sort == OfflineSort.AZ) { view.sortBy(OfflineSort.AZ) }
                    }
                }
            }
        }

        // downloading
        if (pending.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(
                        listOfNotNull("Downloading", "${pending.size}", if (settings.wifiOnly) "Wi-Fi only" else null).joinToString(" · "),
                        Modifier.weight(1f),
                    )
                    val anyActive = pending.any { it.state.isActive }
                    val anyStopped = pending.any { it.state == TaskState.PAUSED || it.state == TaskState.FAILED }
                    if (anyActive) TextPill("Pause all") { OfflineDownloads.pauseAll() }
                    else if (anyStopped) TextPill("Resume all") { OfflineDownloads.resumeAll(context) }
                }
                if (waiting != null && pending.any { it.state.isActive }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = DlColors.Warn, modifier = Modifier.size(14.dp))
                        Text(waiting!!, color = DlColors.Warn, fontSize = 12.sp)
                    }
                }
            }
            items(pending, key = { "p-" + it.key }) { t ->
                PendingRow(api, t, live[t.key], onMenu = { menuTask = t }, onAction = {
                    when (t.state) {
                        TaskState.PAUSED, TaskState.FAILED -> OfflineDownloads.resume(context, t.key)
                        else -> OfflineDownloads.pause(t.key)
                    }
                })
            }
        }

        // the library on the phone, by kind; a kind's heading folds it away
        sections.forEach { section ->
            // a picked chip opens its kind
            val open = filter == section.shelf || section.shelf.name !in view.folded
            item(key = "sec-${section.shelf}") {
                KindHeader(
                    label = section.label,
                    sub = "${kindCount(section.shelf, section.count)} · ${OfflineFormat.bytes(section.bytes)}",
                    color = shelfColor(section.shelf),
                    open = open,
                ) { view.toggle(section.shelf) }
            }
            if (!open) return@forEach
            items(section.groups, key = { "g-" + it.key }) { g ->
                GroupCard(
                    api = api,
                    group = g,
                    ruleText = smartTag(g, rules),
                    onOpen = { openGroup = g.key },
                    onPlay = { playDownloaded(coordinator, api, g.tasks.first { it.state == TaskState.DOWNLOADED }, g.tasks) },
                    onFreeWatched = { freeUp = g.watched },
                )
            }
        }

        if (tasks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(SoulSyncColors.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(30.dp))
                    }
                    Text("Nothing downloaded yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Tap Download on a movie, a season, an album or a playlist to keep it on this phone. It plays with no connection, and smart downloads keep your shows topped up.",
                        color = DlColors.Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (showSettings) DownloadSettingsSheet(api, onDismiss = { showSettings = false })
    menuTask?.let { t ->
        DownloadItemMenu(api = api, task = t, onDismiss = { menuTask = null }, onPlay = { playDownloaded(coordinator, api, t) })
    }
    openGroup?.let { gk ->
        val g = OfflineGrouping.sections(tasks.filter { it.state == TaskState.DOWNLOADED }).flatMap { it.groups }.firstOrNull { it.key == gk }
        if (g == null) openGroup = null else GroupSheet(api, coordinator, g, smartTag(g, rules), onDismiss = { openGroup = null })
    }
    freeUp?.let { list ->
        val bytes = list.sumOf { it.diskBytes }
        AlertDialog(
            onDismissRequest = { freeUp = null },
            containerColor = DlColors.Raise,
            title = { Text("Free up ${OfflineFormat.bytes(bytes)}?", color = Color.White) },
            text = {
                val watchedN = list.count { it.watched }
                Text(
                    when {
                        list.isEmpty() -> "Nothing to remove."
                        watchedN == list.size -> "Removes ${list.size} watched ${if (list.size == 1) "download" else "downloads"} from this phone. You can download them again any time."
                        else -> "Removes ${list.size} downloads, watched ones first, then the oldest. You can download them again any time."
                    },
                    color = DlColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { OfflineDownloads.remove(list.map { it.key }); freeUp = null }, enabled = list.isNotEmpty()) {
                    Text("Remove", color = DlColors.Bad)
                }
            },
            dismissButton = { TextButton(onClick = { freeUp = null }) { Text("Keep", color = Color.White) } },
        )
    }
}

@Composable
private fun DeviceChip(name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (on) SoulSyncColors.Accent else DlColors.Line2, RoundedCornerShape(14.dp))
            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.08f) else DlColors.Raise)
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = if (on) SoulSyncColors.AccentLight else DlColors.Muted, modifier = Modifier.size(18.dp))
        Column {
            Text(name, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, color = DlColors.Muted, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

/** a kind's heading: its colour, name, how many and how big; tap to fold it away */
@Composable
private fun KindHeader(label: String, sub: String, color: Color, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(DlColors.Raise).border(1.dp, DlColors.Line, RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (open) "Fold $label away" else "Show $label", onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = DlColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = DlColors.Muted,
            modifier = Modifier.size(20.dp).rotate(if (open) 0f else -90f),
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = DlColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, modifier = modifier)
}

@Composable
private fun CircleIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(DlColors.Raise2).dpadFocusable(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = DlColors.Muted, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun FilterChipPill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.15f) else DlColors.Raise2)
            .dpadFocusable(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (on) SoulSyncColors.AccentLight else DlColors.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PillButton(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).border(1.dp, DlColors.Line2, CircleShape).dpadFocusable(CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextPill(label: String, onClick: () -> Unit) = PillButton(label, null, onClick)

@Composable
private fun PendingRow(api: PlayerApiClient, t: DownloadTask, live: LiveProgress?, onMenu: () -> Unit, onAction: () -> Unit) {
    val progress = live?.let { if (it.totalBytes > 0) it.bytesDone.toFloat() / it.totalBytes else null } ?: t.progress.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoulSyncArtwork(
            url = artModel(api, t),
            contentDescription = t.title,
            modifier = Modifier.width(54.dp).height(if (t.isVideo) 78.dp else 54.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(rowTitle(t), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                statusLine(t, live),
                color = if (t.state == TaskState.FAILED) DlColors.Bad else DlColors.Muted,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (t.state == TaskState.DOWNLOADING || (t.state != TaskState.QUEUED && t.bytesDone > 0) || t.state == TaskState.PREPARING) {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(DlColors.Raise2)) {
                    val frac = if (t.state == TaskState.PREPARING) t.convertProgress.toFloat() else progress
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f))
                            .background(if (t.state == TaskState.PREPARING) DlColors.Smart else SoulSyncColors.Accent),
                    )
                }
            }
        }
        when (t.state) {
            TaskState.DOWNLOADING, TaskState.PREPARING -> PillButton("Pause", Icons.Default.Pause, onAction)
            TaskState.PAUSED -> PillButton("Resume", Icons.Default.PlayArrow, onAction)
            TaskState.FAILED -> PillButton("Retry", Icons.Default.Refresh, onAction)
            else -> Unit
        }
        CircleIcon(Icons.Default.MoreHoriz, "More", onMenu)
    }
}

private fun rowTitle(t: DownloadTask): String = when {
    t.itemType == "episode" && t.groupTitle.isNotBlank() && !t.title.startsWith(t.groupTitle) -> "${t.groupTitle} · ${t.title}"
    else -> t.title
}

@Composable
private fun GroupCard(
    api: PlayerApiClient,
    group: OfflineGroup,
    ruleText: String?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onFreeWatched: () -> Unit,
) {
    val single = group.tasks.size == 1 && (group.groupType == "movie" || group.groupType == group.tasks.first().itemType)
    val first = group.tasks.first()
    val video = first.isVideo
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).dpadFocusable(RoundedCornerShape(12.dp))
            .clickable { if (single) onPlay() else onOpen() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SoulSyncArtwork(
            url = artModel(api, first),
            contentDescription = group.title,
            modifier = Modifier.width(54.dp).height(if (video) 78.dp else 54.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(group.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = group.tasks.size
            val sub = if (single) {
                statusLine(first, null)
            } else {
                val noun = when (group.shelf) {
                    OfflineShelf.TV, OfflineShelf.PODCASTS -> if (n == 1) "episode" else "episodes"
                    OfflineShelf.BOOKS -> if (n == 1) "part" else "parts"
                    OfflineShelf.YOUTUBE, OfflineShelf.HOME -> if (n == 1) "video" else "videos"
                    else -> if (n == 1) "song" else "songs"
                }
                val kind = when (group.groupType) {
                    "playlist" -> "Playlist · "
                    OfflineGrouping.SONGS -> "On their own · "
                    "album", "home_album" -> "Album · "
                    "artist" -> "Artist · "
                    else -> ""
                }
                "$kind$n $noun · ${OfflineFormat.bytes(group.bytes)}"
            }
            Text(sub, color = DlColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                if (ruleText != null) DlTag(ruleText, DlColors.Smart, DlColors.SmartBg)
                val w = group.watched
                if (w.isNotEmpty() && !single) {
                    DlTag("${w.size} watched · free ${OfflineFormat.bytes(group.watchedBytes)}", DlColors.Warn, DlColors.Warn.copy(alpha = 0.14f), onFreeWatched)
                }
            }
        }
        PillButton(if (single) "Play" else "Open", if (single) Icons.Default.PlayArrow else null) { if (single) onPlay() else onOpen() }
    }
}

/** one group's downloads: play any, remove any, remove all, smart settings */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSheet(api: PlayerApiClient, coordinator: PlaybackCoordinator, group: OfflineGroup, ruleText: String?, onDismiss: () -> Unit) {
    var menuTask by remember { mutableStateOf<DownloadTask?>(null) }
    var smart by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    var addRest by remember { mutableStateOf(false) }
    val canSmart = group.groupType in setOf("show", "playlist", "podcast")
    AdaptiveTvSheet(onDismissRequest = onDismiss, containerColor = DlColors.Raise, panelWidth = 480.dp) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoulSyncArtwork(
                    url = artModel(api, group.tasks.first()),
                    contentDescription = group.title,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(group.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.tasks.size} on this phone · ${OfflineFormat.bytes(group.bytes)}", color = DlColors.Muted, fontSize = 12.5.sp)
                    if (ruleText != null) DlTag(ruleText, DlColors.Smart, DlColors.SmartBg)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Play", Icons.Default.PlayArrow) {
                    val firstUnwatched = group.tasks.firstOrNull { !it.watched } ?: group.tasks.first()
                    onDismiss()
                    playDownloaded(coordinator, api, firstUnwatched, group.tasks)
                }
                if (group.groupType in setOf("show", "album", "artist", "playlist", "podcast", "audiobook")) {
                    PillButton("Add the rest", Icons.Default.Download) { addRest = true }
                }
                if (canSmart) PillButton("Smart settings", Icons.Default.AutoAwesome) { smart = true }
                PillButton("Remove all", Icons.Default.DeleteOutline) { confirmAll = true }
            }
            // a show's episodes under their season
            val parts = if (group.groupType == "show") OfflineGrouping.seasons(group) else listOf("" to group.tasks)
            parts.forEach { (season, list) ->
            if (season.isNotBlank() && parts.size > 1) {
                Text(
                    "$season · ${list.size} ${if (list.size == 1) "episode" else "episodes"}",
                    color = DlColors.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp),
                )
            }
            list.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).dpadFocusable(RoundedCornerShape(10.dp))
                        .clickable { onDismiss(); playDownloaded(coordinator, api, t, group.tasks) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (t.watched) DlColors.Muted else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(statusLine(t, null), color = DlColors.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (t.watched) DlTag("Watched")
                    CircleIcon(Icons.Default.MoreHoriz, "More") { menuTask = t }
                }
            }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    menuTask?.let { t -> DownloadItemMenu(api = api, task = t, onDismiss = { menuTask = null }, onPlay = { playDownloaded(coordinator, api, t, group.tasks) }) }
    if (addRest) {
        val first = group.tasks.first()
        DownloadSheet(
            api = api,
            target = DownloadTarget(type = group.groupType, id = group.groupId, title = group.title, artwork = first.groupArtwork ?: first.artwork, groupTitle = group.title),
            sendMode = false,
            onDismiss = { addRest = false },
        )
    }
    if (smart) {
        val first = group.tasks.first()
        DownloadSheet(
            api = api,
            target = DownloadTarget(type = group.groupType, id = group.groupId, title = group.title, artwork = first.groupArtwork ?: first.artwork, groupTitle = group.title),
            sendMode = false,
            onDismiss = { smart = false },
        )
    }
    if (confirmAll) {
        val rules by OfflineDownloads.rules.collectAsState()
        val rule = rules.firstOrNull { it.type == group.groupType && it.id == group.groupId }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = DlColors.Raise,
            title = { Text("Remove ${group.title}?", color = Color.White) },
            text = {
                Text(
                    "Takes ${group.tasks.size} ${if (group.tasks.size == 1) "download" else "downloads"} (${OfflineFormat.bytes(group.bytes)}) off this phone." +
                        if (rule != null) " Smart downloads for it turn off too." else "",
                    color = DlColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rule?.ruleId?.takeIf { it.isNotBlank() }?.let { id ->
                        OfflineDownloads.addOp(net.ssync.player.OfflinePendingOp(op = "rule_delete", id = id))
                        OfflineDownloads.setRules(rules - rule)
                    }
                    OfflineDownloads.remove(group.tasks.map { it.key })
                    confirmAll = false
                    onDismiss()
                }) { Text("Remove", color = DlColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Keep", color = Color.White) } },
        )
    }
}

private val LIMITS_GB = listOf(0L, 5L, 10L, 25L, 50L, 100L, 200L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadSettingsSheet(api: PlayerApiClient, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineApi = remember(api) { OfflineApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    var server by remember { mutableStateOf<net.ssync.player.OfflineSettingsInfo?>(null) }
    var adminNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) { (offlineApi.settings() as? MusicApiResult.Success)?.let { server = it.data } }
    fun saveAdmin(allow: Boolean? = null, max: Int? = null, cache: Int? = null) {
        val before = server ?: return
        // shown at once, put back if the server says no
        server = before.copy(
            settings = before.settings.copy(
                allowConversionForMembers = allow ?: before.settings.allowConversionForMembers,
                maxConversions = max ?: before.settings.maxConversions,
                cacheGb = cache ?: before.settings.cacheGb,
            ),
        )
        scope.launch {
            when (val r = offlineApi.saveSettings(allow, max, cache)) {
                is MusicApiResult.Success -> { server = server?.copy(settings = r.data); adminNote = null }
                is MusicApiResult.Error -> { server = before; adminNote = r.message }
                MusicApiResult.DeviceRevoked -> server = before
            }
        }
    }
    val settings by OfflineDownloads.settings.collectAsState()
    val tasks by OfflineDownloads.tasks.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }
    val gb = 1024L * 1024 * 1024
    AdaptiveTvSheet(onDismissRequest = onDismiss, containerColor = DlColors.Raise) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Download settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            SheetToggle("Download on Wi-Fi only", settings.wifiOnly) { on -> OfflineDownloads.updateSettings(context) { it.copy(wifiOnly = on) } }
            Text("With it off, downloads also use mobile data.", color = DlColors.Muted, fontSize = 12.sp)

            SectionLabel("Storage limit")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LIMITS_GB.forEach { n ->
                    FilterChipPill(if (n == 0L) "No limit" else "$n GB", settings.limitBytes == n * gb) {
                        OfflineDownloads.updateSettings(context) { it.copy(limitBytes = n * gb) }
                    }
                }
            }
            Text(
                "Using ${OfflineFormat.bytes(tasks.sumOf { it.diskBytes })}, ${OfflineFormat.bytes(OfflineDownloads.freeBytes())} free on this phone. Smart downloads stop at the limit.",
                color = DlColors.Muted, fontSize = 12.sp,
            )

            SectionLabel("Quality for films and TV")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "Ask each time", "original" to "Original", "high" to "1080p", "phone" to "720p", "small" to "480p").forEach { (id, label) ->
                    FilterChipPill(label, settings.videoQuality == id) { OfflineDownloads.updateSettings(context) { it.copy(videoQuality = id) } }
                }
            }
            SectionLabel("Quality for music and books")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "Ask each time", "original" to "Original", "320" to "320 kbps", "256" to "256 kbps", "128" to "128 kbps").forEach { (id, label) ->
                    FilterChipPill(label, settings.audioQuality == id) { OfflineDownloads.updateSettings(context) { it.copy(audioQuality = id) } }
                }
            }
            Text("Picked once, used for songs you download from a track list. Films and shows always show the sizes first.", color = DlColors.Muted, fontSize = 12.sp)

            server?.takeIf { !it.allowDownloads }?.let {
                Text("Downloads are turned off for this profile. An admin can turn them on.", color = DlColors.Warn, fontSize = 12.5.sp)
            }
            server?.takeIf { !it.canConvert && it.allowDownloads }?.let {
                Text("This profile downloads originals only: smaller copies are off for members.", color = DlColors.Muted, fontSize = 12.sp)
            }

            // the admin's server-wide settings, as on the web player's settings page
            server?.takeIf { it.isAdmin }?.let { info ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("For everyone on this server", Modifier.weight(1f))
                    DlTag("ADMIN")
                }
                SheetToggle(
                    "Members may ask for smaller copies",
                    info.settings.allowConversionForMembers,
                    hint = "Off: members download originals only. Admins always can.",
                ) { saveAdmin(allow = it) }
                Text("Copies made at once", color = Color.White, fontSize = 13.5.sp)
                Text("Each one keeps a CPU core or the GPU busy.", color = DlColors.Muted, fontSize = 11.5.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4, 6, 8).forEach { n -> FilterChipPill("$n", info.settings.maxConversions == n) { saveAdmin(max = n) } }
                }
                Text("Room for smaller copies", color = Color.White, fontSize = 13.5.sp)
                Text(
                    "${OfflineFormat.bytes(info.cacheUsedBytes)} in use. When it's full, the copies used longest ago go first.",
                    color = DlColors.Muted, fontSize = 11.5.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 25, 50, 100, 250, 500).forEach { n -> FilterChipPill("$n GB", info.settings.cacheGb == n) { saveAdmin(cache = n) } }
                }
                adminNote?.let { Text(it, color = DlColors.Bad, fontSize = 12.sp) }
            }

            if (tasks.isNotEmpty()) SheetButton("Remove all downloads", primary = false, danger = true) { confirmAll = true }
            Spacer(Modifier.height(6.dp))
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = DlColors.Raise,
            title = { Text("Remove all downloads?", color = Color.White) },
            text = { Text("Everything downloaded or queued on this phone goes. Smart downloads stay on and refill when there's room.", color = DlColors.Muted) },
            confirmButton = {
                TextButton(onClick = { OfflineDownloads.remove(tasks.map { it.key }); confirmAll = false; onDismiss() }) { Text("Remove all", color = DlColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Keep", color = Color.White) } },
        )
    }
}

/** the tv's Downloads: every phone of the profile, what's on it, and remove */
@Composable
private fun TvDeviceDownloads(api: PlayerApiClient) {
    val offline = remember(api) { OfflineApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    var devices by remember { mutableStateOf<List<OfflineDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        when (val r = offline.devices()) {
            is MusicApiResult.Success -> { devices = r.data.filter { it.platform != "web" }; error = null }
            is MusicApiResult.Error -> error = r.message
            MusicApiResult.DeviceRevoked -> error = "This device was signed out."
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(SoulSyncColors.Accent.copy(alpha = 0.10f), Color(0xFF0A0C16)))),
        contentPadding = PaddingValues(start = 78.dp, end = 32.dp, top = 20.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Downloads", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Your phones and tablets keep the downloads. Send a movie or a season to one from its page, and manage what's on each here.",
                    color = DlColors.Muted, fontSize = 13.sp,
                )
            }
        }
        val list = devices
        when {
            error != null && list == null -> item { Text(error!!, color = DlColors.Bad, fontSize = 14.sp) }
            list == null -> item { CircularProgressIndicator(color = SoulSyncColors.AccentLight, modifier = Modifier.size(28.dp)) }
            list.isEmpty() -> item {
                Text("No phones yet. Open SoulSync on a phone or tablet signed in to this profile.", color = DlColors.Muted, fontSize = 14.sp)
            }
            else -> items(list, key = { it.deviceId }) { d ->
                RemoteDeviceCard(api, offline, d, onChanged = { refresh++ })
            }
        }
    }
}

/**
 * another device of the profile, managed from here like the web player does:
 * its storage, what it's waiting for (cancel), its shows, albums and
 * playlists with add the rest, smart settings and remove all, and each item.
 * changes go as wishes the device picks up the next time it's online
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteDeviceCard(api: PlayerApiClient, offline: OfflineApiClient, d: OfflineDevice, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = remember(context) { DownloadsView(context) }
    var note by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<DownloadTarget?>(null) }
    val name = d.deviceName.ifBlank { "the phone" }
    fun wishRemove(type: String, id: String, quality: String = "original") {
        scope.launch {
            val r = offline.wish(d.deviceId, "remove", type, id, quality)
            note = if (r is MusicApiResult.Success) "Done. $name changes the next time it's online." else "Didn't change: ${(r as? MusicApiResult.Error)?.message.orEmpty()}"
            onChanged()
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DlColors.Raise).border(1.dp, DlColors.Line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = SoulSyncColors.AccentLight)
            Column(Modifier.weight(1f)) {
                Text(d.deviceName.ifBlank { "Phone" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        if (d.limitBytes > 0) "${OfflineFormat.bytes(d.usedBytes)} of ${OfflineFormat.bytes(d.limitBytes)}" else "${OfflineFormat.bytes(d.usedBytes)} used",
                        "${OfflineFormat.bytes(d.freeBytes)} free",
                        if (d.wifiOnly) "Wi-Fi only" else null,
                        d.lastSeen?.let { "seen ${seenAgo(it)}" },
                    ).joinToString(" · "),
                    color = DlColors.Muted, fontSize = 12.5.sp,
                )
            }
        }
        val scale = if (d.limitBytes > 0) d.limitBytes else (d.usedBytes + d.freeBytes).coerceAtLeast(1L)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(DlColors.Raise2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((d.usedBytes.toFloat() / scale).coerceIn(0f, 1f)).background(SoulSyncColors.Accent))
        }
        note?.let { Text(it, color = SoulSyncColors.AccentLight, fontSize = 12.5.sp) }

        val waiting = d.wishes.filter { it.action == "add" }
        if (waiting.isNotEmpty()) {
            SectionLabel("Waiting for $name")
            waiting.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoulSyncArtwork(url = api.artwork.resolve(w.artwork), contentDescription = w.title, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(w.title ?: w.id, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(w.subtitle?.ifBlank { null }, OfflineFormat.qualityTag(w.quality), "next time it's online").joinToString(" · "), color = DlColors.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    PillButton("Cancel") { wishRemove(w.type, w.id, w.quality) }
                }
            }
        }

        if (d.items.isEmpty() && waiting.isEmpty()) Text("Nothing on it yet.", color = DlColors.Muted, fontSize = 13.sp)
        // filed by kind like this phone's own downloads, each kind folding away
        val byKind = remoteGroups(d.items).groupBy { net.ssync.player.shelfOf(it.items.first().itemType) }
        SECTION_ORDER.forEach { (shelf, label) ->
            val inKind = byKind[shelf] ?: return@forEach
            val newest = { g: RemoteGroup -> g.items.maxOf { it.downloadedAt.orEmpty() } }
            val sorted = when (view.sort) {
                // a show's seasons stay together, in order
                OfflineSort.AZ -> inKind.sortedWith(compareBy<RemoteGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id })
                OfflineSort.RECENT -> inKind.sortedWith(compareByDescending<RemoteGroup> { newest(it) }.thenBy { it.title })
            }
            val open = shelf.name !in view.folded
            KindHeader(
                label = label,
                sub = "${kindCount(shelf, inKind.sumOf { it.items.size })} · ${OfflineFormat.bytes(inKind.sumOf { g -> g.items.sumOf { it.sizeBytes } })}",
                color = shelfColor(shelf),
                open = open,
            ) { view.toggle(shelf) }
            if (open) sorted.forEach { g ->
                val rule = ruleFor(g.type, g.id, d.rules)
                val single = g.items.size == 1 && g.type in setOf("movie", "youtube", g.items.first().itemType)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoulSyncArtwork(url = api.artwork.resolve(g.artwork), contentDescription = g.title, modifier = Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f)) {
                            // a season says which one, so a show's seasons read in order
                            val season = g.id.substringAfter(':', "").toIntOrNull()?.takeIf { g.type == "season" }
                            Text(
                                if (season == null) g.title else "${g.title} · ${if (season == 0) "Specials" else "Season $season"}",
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("${g.items.size} on $name · ${OfflineFormat.bytes(g.items.sumOf { it.sizeBytes })}", color = DlColors.Muted, fontSize = 12.sp)
                            rule?.let { DlTag(ruleTag(it), DlColors.Smart, DlColors.SmartBg) }
                        }
                    }
                    if (!single) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillButton("Add the rest") { sheetFor = DownloadTarget(type = g.type, id = g.id, title = g.title, artwork = g.artwork, groupTitle = g.title) }
                            if (g.type in setOf("season", "show", "playlist", "podcast")) {
                                PillButton("Smart settings") {
                                    sheetFor = DownloadTarget(
                                        type = if (g.type == "season") "show" else g.type,
                                        id = if (g.type == "season") g.id.substringBefore(':') else g.id,
                                        title = g.title, artwork = g.artwork, groupTitle = g.title,
                                    )
                                }
                            }
                            PillButton("Remove all") { wishRemove(g.type, g.id) }
                        }
                    }
                    g.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = if (single) 0.dp else 12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title ?: item.itemId, color = if (item.watched) DlColors.Muted else Color.White, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        OfflineFormat.qualityTag(item.quality),
                                        OfflineFormat.bytes(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                                        when (item.state) {
                                            "downloaded" -> if (item.watched) "watched" else "on the phone"
                                            "downloading" -> "downloading ${(item.progress * 100).toInt()}%"
                                            "queued" -> "waiting"
                                            else -> item.state
                                        },
                                    ).joinToString(" · "),
                                    color = if (item.state == "downloaded" && !item.watched) DlColors.Done else DlColors.Muted, fontSize = 12.sp,
                                )
                            }
                            PillButton(if (item.state == "downloaded") "Remove" else "Cancel") { wishRemove(item.itemType, item.itemId, item.quality) }
                        }
                    }
                }
            }
        }
        Text("Changes reach $name the next time it's online.", color = DlColors.Muted, fontSize = 12.sp)
    }
    sheetFor?.let { t ->
        DownloadSheet(api = api, target = t, sendMode = LocalIsTv.current, initialDeviceId = d.deviceId, onDismiss = { sheetFor = null; onChanged() })
    }
}

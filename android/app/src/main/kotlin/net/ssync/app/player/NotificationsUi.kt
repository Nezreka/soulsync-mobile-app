package net.ssync.app.player

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.NotificationKindInfo
import net.ssync.player.NotificationPrefs
import net.ssync.player.NotificationTime
import net.ssync.player.NotificationsApiClient
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerNotification
import net.ssync.storage.PairedServerRecord

/** the list's gear opens settings on Notifications: read once by the settings screen */
object NotificationsSettingsJump {
    @Volatile private var pending = false
    fun request() { pending = true }
    fun take(): Boolean = pending.also { pending = false }
}

internal fun notificationsClient(api: PlayerApiClient): NotificationsApiClient =
    NotificationsApiClient(api.baseUrl, api.pinnedAuthenticatedClient)

// ── the bell ──

/** a round bell with the unread count, for the phone header and the tv top bar */
@Composable
internal fun NotificationBellButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp,
) {
    val isTv = LocalIsTv.current
    var focused by remember { mutableStateOf(false) }
    val label = if (count > 0) "Notifications, $count unread" else "Notifications"
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    when {
                        isTv && focused -> Color.White
                        isTv -> Color.Black.copy(alpha = 0.34f)
                        else -> SoulSyncColors.PanelGlass
                    },
                )
                .border(1.dp, if (isTv && focused) Color.White else Color.White.copy(alpha = 0.12f), CircleShape)
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .clickable(onClick = onClick)
                .dpadFocusable(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (count > 0) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                contentDescription = label,
                tint = if (isTv && focused) Color(0xFF0B0D14) else Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .height(17.dp)
                    .widthIn(min = 17.dp)
                    .clip(CircleShape)
                    .background(SoulSyncColors.Accent)
                    .border(1.5.dp, SoulSyncColors.BgPrimary, CircleShape)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 99) "99+" else "$count",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── the list ──

@Composable
fun NotificationsScreen(
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(api) { notificationsClient(api) }
    val isTv = LocalIsTv.current
    val changed by NotificationCenter.changed.collectAsState()
    var items by remember { mutableStateOf<List<PlayerNotification>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, changed, reload) {
        when (val res = client.fetch(limit = 100)) {
            is MusicApiResult.Success -> {
                items = res.data.notifications.sortedByDescending { it.id }
                NotificationCenter.setUnread(res.data.unread)
                error = null
            }
            is MusicApiResult.Error -> if (items == null) error = "Couldn't load notifications. ${res.message}"
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
        }
    }

    fun open(n: PlayerNotification) {
        if (!n.read) {
            items = items?.map { if (it.id == n.id) it.copy(read = true) else it }
            NotificationCenter.onRead(context, listOf(n.id))
            scope.launch { client.markRead(listOf(n.id)) }
        }
        if (NotificationRoutes.opensNotificationSettings(n.link)) NotificationsSettingsJump.request()
        NotificationRoutes.destinationFor(n.link)?.let(onNavigate)
    }

    val now = remember(items) { System.currentTimeMillis() }
    val list = items
    val unread = list.orEmpty().count { !it.read }
    val sidePad = if (isTv) 32.dp else 16.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = if (isTv) 78.dp else sidePad, end = sidePad, top = if (isTv) 24.dp else 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Notifications",
                        color = Color.White,
                        fontSize = if (isTv) 26.sp else 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (unread > 0) "$unread unread" else "You're all caught up",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                    )
                }
                if (unread > 0) {
                    HeaderChip(Icons.Default.DoneAll, "Mark all read") {
                        items = items?.map { it.copy(read = true) }
                        NotificationCenter.onAllRead(context)
                        scope.launch { client.markAllRead() }
                    }
                }
                HeaderChip(Icons.Default.Settings, if (isTv) "Settings" else null) {
                    NotificationsSettingsJump.request()
                    onNavigate(PlayerDestination.Settings)
                }
            }
        }
        when {
            list == null && error != null -> item {
                Text(error.orEmpty(), color = Color(0xFFFDA4AF), fontSize = 13.sp)
            }
            list == null -> item { LoadingBox() }
            list.isEmpty() -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(40.dp))
                    Text("Nothing yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "New episodes, films worth watching and finished requests show up here.",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            else -> {
                val fresh = list.filter { !it.read }
                val earlier = list.filter { it.read }
                if (fresh.isNotEmpty()) {
                    item { SectionLabel("New") }
                    items(fresh, key = { "n${it.id}" }) { n ->
                        NotificationRow(n, api.artwork.resolve(n.image), now, onOpen = { open(n) }, onDelete = {
                            items = items?.filterNot { it.id == n.id }
                            NotificationCenter.onRead(context, listOf(n.id))
                            scope.launch { client.delete(n.id) }
                        })
                    }
                }
                if (earlier.isNotEmpty()) {
                    item { SectionLabel("Earlier") }
                    items(earlier, key = { "e${it.id}" }) { n ->
                        NotificationRow(n, api.artwork.resolve(n.image), now, onOpen = { open(n) }, onDelete = {
                            items = items?.filterNot { it.id == n.id }
                            scope.launch { client.delete(n.id) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = SoulSyncColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HeaderChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, SoulSyncColors.Line, CircleShape)
            .clickable(onClick = onClick)
            .dpadFocusable(CircleShape)
            .padding(horizontal = if (label == null) 9.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label ?: "Notification settings", tint = Color.White, modifier = Modifier.size(16.dp))
        if (label != null) {
            Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun NotificationRow(
    n: PlayerNotification,
    art: String?,
    nowMs: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (n.read) Color.White.copy(alpha = 0.03f) else SoulSyncColors.Accent.copy(alpha = 0.10f))
            .border(1.dp, if (n.read) SoulSyncColors.Line else SoulSyncColors.Accent.copy(alpha = 0.35f), shape)
            .dpadFocusable(shape)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artMod = Modifier.size(width = 44.dp, height = 62.dp).clip(RoundedCornerShape(8.dp))
        if (art != null) {
            AsyncImage(
                model = art,
                imageLoader = LocalSoulSyncImageLoader.current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = artMod.background(Color.White.copy(alpha = 0.06f)),
            )
        } else {
            Box(artMod.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!n.read) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(SoulSyncColors.AccentLight))
                }
                Text(
                    n.title.ifBlank { net.ssync.player.NotificationChannels.fallbackLabel(n.kind) },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (n.read) FontWeight.SemiBold else FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (n.body.isNotBlank()) {
                Text(n.body, color = SoulSyncColors.TextSoft, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val ago = NotificationTime.ago(n.createdAt, nowMs)
            if (ago.isNotEmpty()) {
                Text(ago, color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
            }
        }
        // a tv reaches the x with the d-pad; a phone taps it
        Box(
            modifier = Modifier
                .size(if (isTv) 32.dp else 30.dp)
                .clip(CircleShape)
                .dpadFocusable(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

// ── settings → notifications ──

private val QUIET_STARTS = listOf("20:00", "21:00", "22:00", "23:00", "00:00")
private val QUIET_ENDS = listOf("06:00", "07:00", "08:00", "09:00", "10:00")
private val DAY_TIMES = listOf("08:00", "09:00", "12:00", "18:00", "20:00")

private fun withCurrent(options: List<String>, current: String): List<Pair<String, String>> =
    (if (current in options) options else (options + current).sorted()).map { it to it }

@Composable
internal fun NotificationsSettingsSection(
    record: PairedServerRecord,
    api: PlayerApiClient,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isTv = LocalIsTv.current
    val client = remember(api) { notificationsClient(api) }
    var kinds by remember { mutableStateOf<List<NotificationKindInfo>>(emptyList()) }
    var prefs by remember { mutableStateOf<NotificationPrefs?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var deviceOn by remember { mutableStateOf(NotificationCenter.deviceEnabled(context)) }
    var testState by remember { mutableStateOf<String?>(null) }
    // re-read when coming back from the system's notification settings
    var permissionTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { permissionTick += 1 }
    }
    val allowed = remember(permissionTick) { NotificationCenter.systemAllows(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionTick += 1
        if (it) NotificationCenter.schedule(context)
    }

    LaunchedEffect(client) {
        when (val res = client.fetchPrefs()) {
            is MusicApiResult.Success -> {
                kinds = res.data.kinds
                prefs = res.data.prefs
                NotificationCenter.rememberKinds(context, res.data.kinds)
            }
            is MusicApiResult.Error -> loadError = "Couldn't load notification settings. ${res.message}"
            MusicApiResult.DeviceRevoked -> Unit
        }
    }

    fun save(edit: NotificationPrefs) {
        val before = prefs ?: return
        // quiet hours and the digest run on the profile's clock: the first time
        // either is set from here, this device's zone becomes it
        val zone = java.util.TimeZone.getDefault().id
        // only a named zone ("Europe/Oslo"); the server refuses an offset like GMT+05:30
        val next = if (edit.timezone.isBlank() && (edit.quietHours || edit.digest) && '/' in zone) {
            edit.copy(timezone = zone)
        } else {
            edit
        }
        prefs = next
        saveError = null
        scope.launch {
            when (val res = client.savePrefs(next.changesFrom(before))) {
                is MusicApiResult.Success -> {
                    res.data?.let { prefs = it }
                    onSaved()
                }
                else -> {
                    prefs = before
                    saveError = "Couldn't save that change. Try again in a moment."
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // this device: phones only, a tv shows notifications in the app alone
        if (!isTv) {
            SettingsCard(
                icon = Icons.Default.NotificationsActive,
                title = "This device",
                scopeBadge = "DEVICE",
                subtitle = "Whether this phone shows SoulSync notifications. Your other screens keep their own choice.",
            ) {
                SettingsToggleRow(
                    label = "Notifications on this device",
                    help = if (deviceOn) "Checks about every 15 minutes, even with the app closed" else "Only the bell in the app",
                    checked = deviceOn,
                    onCheckedChange = {
                        deviceOn = it
                        NotificationCenter.setDeviceEnabled(context, it)
                        onSaved()
                    },
                )
                if (deviceOn && !allowed) {
                    SettingsDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Android needs your OK to show them",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "SoulSync only sends what you switch on below: a new episode of a show you watch, a film worth your time, a finished request. Never ads.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                        val canAsk = Build.VERSION.SDK_INT >= 33 && !NotificationCenter.askedPermission(context)
                        PillButton(if (canAsk) "Allow notifications" else "Open notification settings") {
                            if (canAsk) {
                                NotificationCenter.markAskedPermission(context)
                                runCatching { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                            } else {
                                // asked once already: the system settings are the only way now
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val p = prefs
        SettingsCard(
            icon = Icons.Default.Notifications,
            title = "What to tell you",
            scopeBadge = "PROFILE",
            subtitle = "Shared with every screen of ${record.profileName}.",
        ) {
            when {
                p == null && loadError != null -> Text(loadError.orEmpty(), color = Color(0xFFFDA4AF), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                p == null -> Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("Loading…", color = SoulSyncColors.TextSecondary, fontSize = 13.sp) }
                else -> kinds.forEachIndexed { i, k ->
                    if (i > 0) SettingsDivider()
                    val on = p.enabled(k)
                    SettingsToggleRow(
                        label = k.label,
                        help = if (k.locked) (k.help.ifBlank { "From your admin" } + " · always on") else k.help,
                        checked = on,
                        enabled = !k.locked,
                        onCheckedChange = { save(p.copy(kinds = p.kinds + (k.id to it))) },
                    )
                    if (on && k.id == "featured_movie") {
                        SettingsToggleRow(
                            label = "Also films not in your library",
                            help = "Newly released anywhere, not just what's here to watch",
                            checked = p.featuredNotInLibrary,
                            onCheckedChange = { save(p.copy(featuredNotInLibrary = it)) },
                        )
                    }
                    if (on && k.id == "home_memories") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Time of day", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                            SegmentedPillRow(withCurrent(DAY_TIMES, p.memoriesTime), p.memoriesTime) { save(p.copy(memoriesTime = it)) }
                        }
                    }
                }
            }
        }

        if (p != null) {
            SettingsCard(
                icon = Icons.Default.NotificationsNone,
                title = "When",
                scopeBadge = "PROFILE",
                subtitle = "Quiet hours and the daily digest, on your profile's clock.",
            ) {
                SettingsToggleRow(
                    label = "Quiet hours",
                    help = if (p.quietHours) "Held from ${p.quietStart} to ${p.quietEnd}, then sent. Admin messages still come through" else "Off",
                    checked = p.quietHours,
                    onCheckedChange = { save(p.copy(quietHours = it)) },
                )
                if (p.quietHours) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("From", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                        SegmentedPillRow(withCurrent(QUIET_STARTS, p.quietStart), p.quietStart) { save(p.copy(quietStart = it)) }
                        Text("Until", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                        SegmentedPillRow(withCurrent(QUIET_ENDS, p.quietEnd), p.quietEnd) { save(p.copy(quietEnd = it)) }
                    }
                }
                SettingsDivider()
                SettingsToggleRow(
                    label = "Daily digest",
                    help = if (p.digest) "One notification a day at ${p.digestTime}, instead of each one. Invites and admin messages still come right away" else "Each one as it happens",
                    checked = p.digest,
                    onCheckedChange = { save(p.copy(digest = it)) },
                )
                if (p.digest) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegmentedPillRow(withCurrent(DAY_TIMES, p.digestTime), p.digestTime) { save(p.copy(digestTime = it)) }
                    }
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Send a test", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            testState ?: if (isTv) "It lands on the bell" else "It shows on this device in a moment",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    PillButton("Send") {
                        testState = "Sending…"
                        scope.launch {
                            testState = when (client.sendTest()) {
                                is MusicApiResult.Success -> {
                                    NotificationCenter.check(
                                        context, client, record.lookupId, record.profileId,
                                        mayPost = !isTv, forceTray = true,
                                    )
                                    if (isTv || !deviceOn || !allowed) "Sent. Look at the bell" else "Sent"
                                }
                                else -> "Couldn't send it. Try again in a moment."
                            }
                        }
                    }
                }
            }
        }

        saveError?.let { Text(it, color = Color(0xFFFDA4AF), fontSize = 12.5.sp) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.55f), CircleShape)
            .dpadFocusable(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** keeps the bell fresh while the app is on screen: on open, on resume, every few minutes */
@Composable
internal fun NotificationsWhileOpen(api: PlayerApiClient, record: PairedServerRecord, isTv: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val client = remember(api) { notificationsClient(api) }
    LaunchedEffect(client, record.lookupId, record.profileId, lifecycle) {
        if (!isTv) NotificationCenter.schedule(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            NotificationCenter.visibleScreens += 1
            try {
                while (true) {
                    NotificationCenter.check(context, client, record.lookupId, record.profileId, mayPost = false)
                    kotlinx.coroutines.delay(5 * 60_000L)
                }
            } finally {
                NotificationCenter.visibleScreens = (NotificationCenter.visibleScreens - 1).coerceAtLeast(0)
            }
        }
    }
}

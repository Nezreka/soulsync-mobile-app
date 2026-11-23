package net.ssync.app.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.ProgressMeta
import net.ssync.player.TogetherTitle
import net.ssync.player.VideoCatalog
import net.ssync.player.WatchParty
import net.ssync.player.WatchingSession

private val PARTY_EMOJIS = listOf("🍿", "🔥", "😂", "😮", "👏", "❤️")

private data class MobilePartySeed(
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val backdrop: String? = null,
    val showId: Int? = null,
)

private fun formatClockSec(sec: Double): String {
    val total = sec.toLong().coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

private fun formatCountdown(scheduledSec: Double?): String {
    if (scheduledSec == null) return ""
    val diff = (scheduledSec - System.currentTimeMillis() / 1000.0).toLong().coerceAtLeast(0L)
    if (diff < 60L) return "Starting now"
    val mins = diff / 60L
    if (mins < 60L) return "In ${mins}m"
    val hrs = mins / 60L
    val remM = mins % 60L
    return if (hrs < 24L) {
        if (remM > 0) "In ${hrs}h ${remM}m" else "In ${hrs}h"
    } else {
        "In ${hrs / 24L}d"
    }
}

@Composable
fun WatchingNowScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit = {},
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<WatchingSession>>(emptyList()) }
    var parties by remember { mutableStateOf<List<WatchParty>>(emptyList()) }
    var together by remember { mutableStateOf<List<TogetherTitle>>(emptyList()) }
    var sharing by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var createSeed by remember { mutableStateOf<MobilePartySeed?>(null) }
    var activePartyId by remember { mutableStateOf<String?>(null) }

    suspend fun refreshAll() {
        coroutineScope {
            val pDef = async { api.fetchPresence() }
            val wpDef = async { api.fetchWatchParties() }
            val tDef = async { api.fetchWatchTogether() }
            val p = pDef.await()
            if (p is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (p as? MusicApiResult.Success)?.let {
                sessions = it.data.sessions
                sharing = it.data.sharing
            }
            (wpDef.await() as? MusicApiResult.Success)?.let { parties = it.data }
            (tDef.await() as? MusicApiResult.Success)?.let { together = it.data }
        }
    }

    LaunchedEffect(api) {
        isLoading = true
        refreshAll()
        isLoading = false
        while (true) {
            delay(8_000L)
            refreshAll()
        }
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 74.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header + Start Watch Party CTA
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Watching Now",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "${parties.size} parties · ${sessions.size} live screens",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = {
                        createSeed = null
                        showCreate = true
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Watch Party", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!sharing) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoulSyncColors.Card,
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Activity sharing is off in Settings — others can't see what you're playing.",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // ── Section 1: Watch Parties (Live Lobbies & Scheduled Premieres) ──
        item {
            SectionHeader("Watch Parties")
        }
        if (parties.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SoulSyncColors.Card,
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            createSeed = null
                            showCreate = true
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(SoulSyncColors.Accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.People, contentDescription = null, tint = SoulSyncColors.AccentLight)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start a Theater Watch Party", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Open a waiting room with music from your library, or schedule a premiere.",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        } else {
            items(parties, key = { "party-${it.id}" }) { party ->
                WatchPartyCinemaCard(
                    party = party,
                    api = api,
                    onOpen = { activePartyId = party.id },
                )
            }
        }

        // ── Section 2: Right Now (Redesigned Cinema Backdrop Cards) ──
        item {
            SectionHeader("Right Now")
        }
        if (sessions.isEmpty()) {
            item {
                EmptyBox("Nobody else is watching or listening right now.")
            }
        } else {
            items(sessions, key = { "s-${it.profileId}-${it.itemType}-${it.itemId}" }) { s ->
                CinemaWatchingCard(
                    session = s,
                    api = api,
                    onJoin = {
                        val isAudio = s.itemType == "track" || s.itemType == "audiobook" || s.itemType == "podcast_episode"
                        val art = api.artwork.resolve(s.resolvedArtwork)
                            ?: api.artwork.forItem(s.itemType, s.itemId, preferBackdrop = !isAudio)
                        val playable = ActivePlayable(
                            itemType = s.itemType,
                            itemId = s.itemId,
                            title = s.resolvedTitle,
                            subtitle = s.resolvedSubtitle ?: s.resolvedName,
                            artworkUrl = art,
                            streamUrl = api.streamUrl(s.itemType, s.itemId),
                            isVideo = !isAudio,
                            resumePositionMs = (s.resolvedPosition * 1000.0).toLong(),
                        )
                        if (isAudio) coordinator.playAudio(playable) else coordinator.playVideo(playable)
                    },
                    onWatchTogether = {
                        createSeed = MobilePartySeed(
                            itemType = s.itemType,
                            itemId = s.itemId,
                            title = s.resolvedTitle,
                            subtitle = s.resolvedSubtitle,
                            artwork = s.meta?.artwork ?: s.artwork,
                            backdrop = s.meta?.backdrop,
                        )
                        showCreate = true
                    },
                    onDetails = {
                        val idInt = s.itemId.toIntOrNull()
                        if (s.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                    },
                )
            }
        }

        // ── Section 3: Watching the Same Thing ──
        if (together.isNotEmpty()) {
            item { SectionHeader("Watching the Same Thing") }
            items(together, key = { it.key.ifBlank { "${it.itemType}:${it.itemId}" } }) { t ->
                val mine = t.mine
                val title = mine?.meta?.parentTitle ?: mine?.meta?.title ?: t.title.ifBlank { "Shared Title" }
                val artUrl = api.artwork.resolve(mine?.meta?.artwork ?: t.artwork)
                val itemType = mine?.itemType?.ifBlank { t.itemType } ?: t.itemType
                val itemId = mine?.itemId?.ifBlank { t.itemId } ?: t.itemId
                val othersSummary = if (t.others.isNotEmpty()) {
                    t.others.joinToString(" · ") { o -> o.profile?.name ?: "Someone" }
                } else {
                    t.profiles.joinToString(", ")
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SoulSyncColors.Card,
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SoulSyncArtwork(
                            url = artUrl,
                            contentDescription = title,
                            modifier = Modifier
                                .width(52.dp)
                                .height(76.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (othersSummary.isNotBlank()) {
                                Text(
                                    text = "Also watching: $othersSummary",
                                    color = SoulSyncColors.TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (itemType == "movie" || itemType == "episode" || itemType == "youtube") {
                                OutlinedButton(
                                    onClick = {
                                        createSeed = MobilePartySeed(
                                            itemType = itemType,
                                            itemId = itemId,
                                            title = title,
                                            subtitle = mine?.meta?.subtitle,
                                            artwork = mine?.meta?.artwork ?: t.artwork,
                                            backdrop = mine?.meta?.backdrop,
                                        )
                                        showCreate = true
                                    },
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.55f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text("🍿 Watch Party", color = SoulSyncColors.AccentLight, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateWatchPartyDialog(
            api = api,
            initialSeed = createSeed,
            onDismiss = { showCreate = false },
            onCreated = { party ->
                showCreate = false
                activePartyId = party.id
                scope.launch { refreshAll() }
            },
        )
    }

    val openId = activePartyId
    if (openId != null) {
        WatchPartyRoomDialog(
            partyId = openId,
            api = api,
            coordinator = coordinator,
            onDismiss = {
                activePartyId = null
                scope.launch { refreshAll() }
            },
        )
    }
}

// ── Cinema-Grade Watching Now Card ──────────────────────────────────────────

@Composable
private fun CinemaWatchingCard(
    session: WatchingSession,
    api: PlayerApiClient,
    onJoin: () -> Unit,
    onWatchTogether: () -> Unit,
    onDetails: () -> Unit,
) {
    val bgUrl = api.artwork.resolve(session.resolvedArtwork)
        ?: api.artwork.forItem(session.itemType, session.itemId, preferBackdrop = true)
    val isAudio = session.itemType == "track" || session.itemType == "audiobook" || session.itemType == "podcast_episode"
    val ch = session.channel
    val statusText = when {
        session.live && ch != null -> "CH ${ch.number ?: ""} · ${ch.name}"
        session.isPlayingNow -> if (isAudio) "LISTENING" else "WATCHING"
        else -> "PAUSED"
    }
    val dur = session.resolvedDuration
    val frac = if (dur != null && dur > 0.0) (session.resolvedPosition / dur).toFloat().coerceIn(0f, 1f) else null
    val canParty = session.itemType == "movie" || session.itemType == "episode" || session.itemType == "youtube"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(214.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SoulSyncColors.CardRaised)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
    ) {
        if (bgUrl != null) {
            AsyncImage(
                model = bgUrl,
                imageLoader = LocalSoulSyncImageLoader.current,
                contentDescription = null,
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
                            Color(0x66070A10),
                            Color(0xAA070A10),
                            Color(0xF5070A10),
                        ),
                    ),
                ),
        )

        // Top floating live pill + profile chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xB8070A10),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.16f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (session.isPlayingNow) Color(0xFF34D399) else SoulSyncColors.TextSecondary),
                    )
                    Text(statusText, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Surface(
                shape = CircleShape,
                color = Color(0xB8070A10),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
            ) {
                Text(
                    text = if (session.you) "${session.resolvedName} (You)" else session.resolvedName,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Bottom cinema details + progress + actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = session.resolvedTitle,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!session.resolvedSubtitle.isNullOrBlank()) {
                Text(
                    text = session.resolvedSubtitle.orEmpty(),
                    color = SoulSyncColors.TextSoft,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (frac != null && dur != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { frac },
                        color = SoulSyncColors.AccentLight,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                    )
                    Text(
                        text = "${formatClockSec(session.resolvedPosition)} / ${formatClockSec(dur)}",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (!session.you) {
                    Button(
                        onClick = onJoin,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (session.live) "Tune in" else "Join ${formatClockSec(session.resolvedPosition)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (canParty) {
                    OutlinedButton(
                        onClick = onWatchTogether,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("🍿 Watch Together", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (session.itemType == "movie") {
                    OutlinedButton(
                        onClick = onDetails,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("Details", color = Color.White, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

// ── Watch Party Cinema Card ─────────────────────────────────────────────────

@Composable
private fun WatchPartyCinemaCard(
    party: WatchParty,
    api: PlayerApiClient,
    onOpen: () -> Unit,
) {
    val bgUrl = api.artwork.resolve(party.meta?.backdrop ?: party.meta?.artwork)
        ?: api.artwork.forItem(party.itemType, party.itemId, preferBackdrop = true)
    val badgeText = when (party.status) {
        "playing" -> "LIVE · ${formatClockSec(party.livePosition)}"
        "paused" -> "PAUSED · ${formatClockSec(party.livePosition)}"
        "scheduled" -> "SCHEDULED · ${formatCountdown(party.scheduledFor)}"
        else -> "LOBBY OPEN · ${party.readyCount}/${party.memberCount} READY"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SoulSyncColors.CardRaised)
            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        if (bgUrl != null) {
            AsyncImage(
                model = bgUrl,
                imageLoader = LocalSoulSyncImageLoader.current,
                contentDescription = null,
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
                            Color(0x55070A10),
                            Color(0xAA070A10),
                            Color(0xF6070A10),
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xC2070A10),
                border = BorderStroke(0.75.dp, SoulSyncColors.Accent.copy(alpha = 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (party.status == "playing") Color(0xFF34D399) else SoulSyncColors.AccentLight),
                    )
                    Text(badgeText, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Surface(
                shape = CircleShape,
                color = Color(0xB8070A10),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
            ) {
                Text(
                    text = "${party.memberCount} ${if (party.memberCount == 1) "viewer" else "viewers"}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = party.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Hosted by ${party.hostProfile?.name ?: "Host"}" + (party.meta?.subtitle?.let { " · $it" } ?: ""),
                color = SoulSyncColors.TextSoft,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val lt = party.lobbyTrack
            if (lt != null && (party.status == "lobby" || party.status == "scheduled")) {
                Text(
                    text = "♪ Lobby music: ${lt.title}" + if (lt.artist.isNotBlank()) " — ${lt.artist}" else "",
                    color = SoulSyncColors.AccentLight,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onOpen,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when (party.status) {
                        "playing" -> "Join & Sync"
                        "scheduled" -> if (party.isMember) "Open Room" else "RSVP / Enter"
                        else -> "Enter Theater Lobby"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Create Watch Party Dialog ───────────────────────────────────────────────

@Composable
private fun CreateWatchPartyDialog(
    api: PlayerApiClient,
    initialSeed: MobilePartySeed?,
    onDismiss: () -> Unit,
    onCreated: (WatchParty) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(initialSeed) }
    var roomTitle by remember { mutableStateOf(initialSeed?.title ?: "") }
    var scheduleOffsetMin by remember { mutableStateOf<Int?>(null) } // null = open lobby now
    var controlMode by remember { mutableStateOf("host") }
    var lobbyMusic by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        (api.fetchVideoCatalog() as? MusicApiResult.Success)?.let { catalog = it.data }
    }

    val candidates = remember(catalog, search) {
        val q = search.trim().lowercase()
        val out = mutableListOf<MobilePartySeed>()
        for (m in catalog.movies) {
            if (q.isNotEmpty() && !m.title.lowercase().contains(q)) continue
            out += MobilePartySeed(
                itemType = "movie",
                itemId = m.id.toString(),
                title = m.title,
                subtitle = m.year?.toString(),
                artwork = if (m.hasPoster) "/api/video/artwork/movie/${m.id}/poster" else null,
                backdrop = if (m.hasBackdrop) "/api/video/artwork/movie/${m.id}/backdrop" else null,
            )
            if (out.size >= 18) break
        }
        for (sh in catalog.shows) {
            if (q.isNotEmpty() && !sh.title.lowercase().contains(q)) continue
            val latest = sh.episodes.lastOrNull() ?: continue
            val epId = latest.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
            val season = latest.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 1
            val epNum = latest.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 1
            val epTitle = latest.getOrNull(4)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            out += MobilePartySeed(
                itemType = "episode",
                itemId = epId.toString(),
                title = "${sh.title} · S$season E$epNum",
                subtitle = "S$season E$epNum" + (epTitle?.let { " · $it" } ?: ""),
                artwork = if (sh.hasPoster) "/api/video/artwork/show/${sh.id}/poster" else null,
                backdrop = if (sh.hasBackdrop) "/api/video/artwork/show/${sh.id}/backdrop" else null,
                showId = sh.id,
            )
            if (out.size >= 34) break
        }
        out
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SoulSyncColors.BgPrimary,
            border = BorderStroke(1.dp, SoulSyncColors.Line),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Start a Watch Party", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search movies & shows (latest episode)…", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(candidates, key = { "${it.itemType}:${it.itemId}" }) { c ->
                        val on = picked?.itemType == c.itemType && picked?.itemId == c.itemId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (on) SoulSyncColors.Accent.copy(alpha = 0.22f) else SoulSyncColors.Card,
                            border = BorderStroke(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line),
                            modifier = Modifier
                                .width(156.dp)
                                .clickable {
                                    picked = c
                                    roomTitle = c.title
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SoulSyncArtwork(
                                    url = api.artwork.resolve(c.artwork),
                                    contentDescription = c.title,
                                    modifier = Modifier.width(34.dp).height(48.dp).clip(RoundedCornerShape(6.dp)),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (c.itemType == "movie") "Movie" else "Latest Ep", color = SoulSyncColors.TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = roomTitle,
                    onValueChange = { roomTitle = it },
                    placeholder = { Text("Party name", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Schedule presets
                Text("WHEN", color = SoulSyncColors.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(
                        null to "🍿 Lobby Now",
                        30 to "🗓 In 30m",
                        60 to "🗓 In 1 hr",
                        120 to "🗓 In 2 hrs",
                    )
                    items(presets) { (mins, label) ->
                        val on = scheduleOffsetMin == mins
                        Surface(
                            shape = CircleShape,
                            color = if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else SoulSyncColors.Card,
                            border = BorderStroke(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line),
                            modifier = Modifier.clickable { scheduleOffsetMin = mins },
                        ) {
                            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }

                // Controls & Lobby Music toggles
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = if (controlMode == "host") SoulSyncColors.Accent.copy(alpha = 0.22f) else SoulSyncColors.Card,
                        border = BorderStroke(1.dp, if (controlMode == "host") SoulSyncColors.AccentLight else SoulSyncColors.Line),
                        modifier = Modifier.clickable { controlMode = if (controlMode == "host") "everyone" else "host" },
                    ) {
                        Text(
                            text = if (controlMode == "host") "Host controls" else "Anyone controls",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (lobbyMusic) SoulSyncColors.Accent.copy(alpha = 0.22f) else SoulSyncColors.Card,
                        border = BorderStroke(1.dp, if (lobbyMusic) SoulSyncColors.AccentLight else SoulSyncColors.Line),
                        modifier = Modifier.clickable { lobbyMusic = !lobbyMusic },
                    ) {
                        Text(
                            text = if (lobbyMusic) "♪ Lobby music: On" else "♪ Lobby music: Off",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                Button(
                    onClick = {
                        val sel = picked ?: return@Button
                        if (creating) return@Button
                        creating = true
                        scope.launch {
                            val schedSec = scheduleOffsetMin?.let { (System.currentTimeMillis() / 1000L) + it * 60L }
                            val r = api.createWatchParty(
                                title = roomTitle.trim().ifBlank { sel.title },
                                itemType = sel.itemType,
                                itemId = sel.itemId,
                                meta = ProgressMeta(
                                    title = sel.title,
                                    subtitle = sel.subtitle,
                                    artwork = sel.artwork,
                                    backdrop = sel.backdrop,
                                ),
                                showId = sel.showId,
                                scheduledForSec = schedSec,
                                controlMode = controlMode,
                                lobbyMusic = lobbyMusic,
                            )
                            creating = false
                            if (r is MusicApiResult.Success) onCreated(r.data)
                        }
                    },
                    enabled = picked != null && !creating,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent, contentColor = SoulSyncColors.OnAccent),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(
                        text = if (creating) "Opening…" else if (scheduleOffsetMin != null) "Schedule Watch Party" else "Open Theater Lobby",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Watch Party Room Dialog (Theater Waiting Room + Lobby Music + Chat) ─────

@Composable
private fun WatchPartyRoomDialog(
    partyId: String,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var party by remember { mutableStateOf<WatchParty?>(null) }
    var chatInput by remember { mutableStateOf("") }
    var autoJoined by remember { mutableStateOf(false) }
    var showtimeCountdown by remember { mutableStateOf<Int?>(null) }
    var curtainsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(party?.status, showtimeCountdown) {
        val st = party?.status ?: return@LaunchedEffect
        if (st == "playing" || st == "paused") {
            curtainsOpen = true
        } else if (showtimeCountdown == null) {
            curtainsOpen = false
        }
    }

    suspend fun refreshRoom() {
        (api.fetchWatchParty(partyId) as? MusicApiResult.Success)?.let { party = it.data }
    }

    LaunchedEffect(partyId, api) {
        refreshRoom()
        while (true) {
            delay(2_500L)
            refreshRoom()
        }
    }

    LaunchedEffect(party?.id) {
        val p = party ?: return@LaunchedEffect
        if (!autoJoined && !p.isMember) {
            autoJoined = true
            api.joinWatchParty(p.id, rsvp = p.status == "scheduled")
            refreshRoom()
        }
    }

    fun launchSyncedVideo(p: WatchParty) {
        coordinator.playVideo(
            ActivePlayable(
                itemType = p.itemType,
                itemId = p.itemId,
                title = p.title,
                subtitle = "Watch Party · ${p.hostProfile?.name ?: "Host"}",
                artworkUrl = api.artwork.resolve(p.meta?.backdrop ?: p.meta?.artwork)
                    ?: api.artwork.forItem(p.itemType, p.itemId, preferBackdrop = true),
                streamUrl = api.streamUrl(p.itemType, p.itemId),
                isVideo = true,
                resumePositionMs = (p.livePosition * 1000.0).toLong(),
                partyId = p.id,
            ),
        )
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = SoulSyncColors.BgPrimary,
            border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 22.dp),
        ) {
            val p = party
            if (p == null) {
                LoadingBox()
                return@Surface
            }
            val me = p.members.firstOrNull { it.you }

            Column(modifier = Modifier.fillMaxSize()) {
                // Hero top bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                ) {
                    val bgUrl = api.artwork.resolve(p.meta?.backdrop ?: p.meta?.artwork)
                        ?: api.artwork.forItem(p.itemType, p.itemId, preferBackdrop = true)
                    if (bgUrl != null) {
                        AsyncImage(
                            model = bgUrl,
                            imageLoader = LocalSoulSyncImageLoader.current,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0x99070A10), Color(0xF6070A10)))),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp),
                    ) {
                        Text(
                            text = when (p.status) {
                                "playing" -> "● LIVE · ${formatClockSec(p.livePosition)}"
                                "paused" -> "❚❚ PAUSED · ${formatClockSec(p.livePosition)}"
                                "scheduled" -> "🗓 SCHEDULED · ${formatCountdown(p.scheduledFor)}"
                                else -> "🍿 THEATER WAITING ROOM"
                            },
                            color = SoulSyncColors.AccentLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = p.title,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Hosted by ${p.hostProfile?.name ?: "Host"} · Room #${p.id}",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.5.sp,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 16:9 Cinema Proscenium Stage with Animated Pleated Velvet Curtains
                    item {
                        VelvetCurtainStage(
                            title = p.title,
                            subtitle = p.meta?.subtitle.orEmpty(),
                            artworkUrl = api.artwork.resolve(p.meta?.backdrop) ?: api.artwork.resolve(p.meta?.artwork),
                            status = p.status,
                            readyCount = p.members.count { it.ready },
                            memberCount = p.members.size,
                            livePosition = p.livePosition,
                            curtainsOpen = curtainsOpen,
                            countdown = showtimeCountdown,
                        )
                    }

                    // Curved Theater Seating Row
                    if (p.members.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF14101A))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                    .padding(10.dp),
                            ) {
                                items(p.members, key = { "seat-${it.profileId}" }) { member ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier
                                            .width(68.dp)
                                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                                            .background(if (member.ready) Color(0xFF22C55E).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                                            .border(
                                                1.dp,
                                                if (member.ready) Color(0xFF22C55E).copy(alpha = 0.5f) else Color(0xFFA61226).copy(alpha = 0.55f),
                                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(if (member.isHost) Color(0xFFF6C978).copy(alpha = 0.25f) else SoulSyncColors.Accent.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = (member.profile?.name ?: "U").take(1).uppercase(),
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                            )
                                        }
                                        Text(
                                            text = member.profile?.name ?: "Guest",
                                            color = Color.White,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (member.isHost) "★ HOST" else if (member.ready) "✓ READY" else "SEATED",
                                            color = if (member.ready) Color(0xFF86EFAC) else if (member.isHost) Color(0xFFF6C978) else SoulSyncColors.TextSecondary,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Synced Theater Lobby Music Bar
                    val lt = p.lobbyTrack
                    if (lt != null && (p.status == "lobby" || p.status == "scheduled")) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = SoulSyncColors.Accent.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = api.artwork.resolve(lt.artwork) ?: api.artwork.albumArtwork(lt.albumId),
                                        contentDescription = lt.title,
                                        fallbackIcon = Icons.Default.MusicNote,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("♪ LOBBY MUSIC", color = SoulSyncColors.AccentLight, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                        Text(lt.title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (lt.artist.isNotBlank()) {
                                            Text(lt.artist, color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            coordinator.playAudio(
                                                ActivePlayable(
                                                    itemType = "track",
                                                    itemId = lt.trackId,
                                                    title = lt.title,
                                                    subtitle = "${lt.artist} · Theater Lobby",
                                                    artworkUrl = api.artwork.resolve(lt.artwork) ?: api.artwork.albumArtwork(lt.albumId),
                                                    streamUrl = api.streamUrl("track", lt.trackId),
                                                    resumePositionMs = (lt.livePosition * 1000.0).toLong(),
                                                ),
                                            )
                                        },
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play lobby music", tint = SoulSyncColors.AccentLight)
                                    }
                                    if (p.canControl) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    api.updateWatchPartyState(p.id, shuffleLobbyMusic = true)
                                                    refreshRoom()
                                                }
                                            },
                                        ) {
                                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle lobby track", tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Primary Action Controls
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.status == "lobby" || p.status == "scheduled") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (p.canControl) {
                                        Button(
                                            onClick = {
                                                if (showtimeCountdown != null) return@Button
                                                scope.launch {
                                                    showtimeCountdown = 3
                                                    delay(800L)
                                                    showtimeCountdown = 2
                                                    delay(800L)
                                                    showtimeCountdown = 1
                                                    delay(800L)
                                                    showtimeCountdown = null
                                                    curtainsOpen = true
                                                    val r = api.updateWatchPartyState(p.id, action = "start")
                                                    delay(1350L)
                                                    if (r is MusicApiResult.Success) launchSyncedVideo(r.data)
                                                }
                                            },
                                            enabled = showtimeCountdown == null,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent, contentColor = SoulSyncColors.OnAccent),
                                            modifier = Modifier.weight(1f).height(44.dp),
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = showtimeCountdown?.let { "Starting in $it…" } ?: "Unfurl & Start",
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                api.joinWatchParty(p.id, ready = !(me?.ready ?: false))
                                                refreshRoom()
                                            }
                                        },
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, if (me?.ready == true) SoulSyncColors.Success else SoulSyncColors.Line),
                                        modifier = Modifier.weight(1f).height(44.dp),
                                    ) {
                                        if (me?.ready == true) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = SoulSyncColors.Success, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = if (me?.ready == true) "Ready!" else "Mark Ready",
                                            color = if (me?.ready == true) SoulSyncColors.Success else Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { launchSyncedVideo(p) },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent, contentColor = SoulSyncColors.OnAccent),
                                        modifier = Modifier.weight(1f).height(44.dp),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Watch & Sync", fontWeight = FontWeight.Bold)
                                    }
                                    if (p.canControl) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    api.updateWatchPartyState(p.id, action = if (p.status == "playing") "pause" else "play")
                                                    refreshRoom()
                                                }
                                            },
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                                            modifier = Modifier.height(44.dp),
                                        ) {
                                            Icon(
                                                if (p.status == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (p.isHost) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            api.deleteWatchParty(p.id)
                                            onDismiss()
                                        }
                                    },
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                ) {
                                    Text("End Watch Party", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Guest Roster
                    item {
                        Text(
                            text = "IN THE ROOM (${p.readyCount}/${p.memberCount} READY)",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                    items(p.members, key = { "m-${it.profileId}" }) { m ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SoulSyncColors.Card,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = (m.profile?.name ?: "Guest") + if (m.isHost) " (Host)" else if (m.you) " (You)" else "",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (m.ready) SoulSyncColors.Success.copy(alpha = 0.16f) else SoulSyncColors.CardRaised,
                                ) {
                                    Text(
                                        text = if (m.ready) "Ready" else if (m.status == "rsvp") "RSVP" else "Waiting",
                                        color = if (m.ready) SoulSyncColors.Success else SoulSyncColors.TextSecondary,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Lounge Chat
                    item {
                        Text(
                            text = "LOUNGE CHAT",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                    items(p.messages.takeLast(20), key = { "msg-${it.id}" }) { msg ->
                        if (msg.kind == "system") {
                            Text(
                                text = "${msg.profile?.name ?: "Someone"} ${msg.body.lowercase()}",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                                fontStyle = FontStyle.Italic,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = msg.profile?.name ?: "Someone",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(msg.body, color = Color.White, fontSize = 12.5.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = SoulSyncColors.Line, thickness = 0.75.dp)

                // Quick Emoji Reactions + Chat Composer
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PARTY_EMOJIS.forEach { em ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SoulSyncColors.Card,
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        scope.launch {
                                            api.postWatchPartyChat(p.id, "reaction", em)
                                            refreshRoom()
                                        }
                                    },
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(em, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = { Text("Say something…", fontSize = 13.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val txt = chatInput.trim()
                                if (txt.isEmpty()) return@IconButton
                                chatInput = ""
                                scope.launch {
                                    api.postWatchPartyChat(p.id, "chat", txt)
                                    refreshRoom()
                                }
                            },
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = SoulSyncColors.AccentLight)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 16:9 Cinema Proscenium Stage with animated pleated crimson velvet curtains,
 * golden valance trim, vintage marquee plaque, and 3-2-1 film-leader countdown.
 */
@Composable
private fun VelvetCurtainStage(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    status: String,
    readyCount: Int,
    memberCount: Int,
    livePosition: Double,
    curtainsOpen: Boolean,
    countdown: Int?,
) {
    val openFrac by animateFloatAsState(
        targetValue = if (curtainsOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 1750, easing = FastOutSlowInEasing),
        label = "velvet_curtain_unfurl",
    )
    val velvetPleats = remember {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF6B0714),
                Color(0xFFA61226),
                Color(0xFF4A040E),
                Color(0xFF960E20),
                Color(0xFF590511),
                Color(0xFFA61226),
                Color(0xFF6B0714),
            ),
        )
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF06070C))
            .border(1.dp, Color(0x55F6C978), RoundedCornerShape(16.dp)),
    ) {
        val halfW = maxWidth * 0.52f
        val slideOffset = halfW * 0.85f * openFrac

        // Underlying movie/show backdrop screen
        if (!artworkUrl.isNullOrBlank()) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Warm overhead theater sconce glow + bottom vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x44F6C978),
                            Color(0x33000000),
                            Color(0xCC06070C),
                        ),
                    ),
                ),
        )

        // Left Pleated Velvet Curtain
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(halfW)
                .fillMaxHeight()
                .offset(x = -slideOffset)
                .background(velvetPleats)
                .border(BorderStroke(1.5.dp, Color(0x88F6C978))),
        )

        // Right Pleated Velvet Curtain
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(halfW)
                .fillMaxHeight()
                .offset(x = slideOffset)
                .background(velvetPleats)
                .border(BorderStroke(1.5.dp, Color(0x88F6C978))),
        )

        // Top Golden-Trimmed Crimson Valance
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(16.dp)
                .background(velvetPleats)
                .border(BorderStroke(1.5.dp, Color(0xAAF6C978))),
        )

        // Center Marquee / 3-2-1 Film Leader Countdown
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (countdown != null) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xDD08060C))
                        .border(2.5.dp, Color(0xFFF6C978), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$countdown",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xBA0A070C))
                        .border(1.dp, Color(0x66F6C978), RoundedCornerShape(14.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = when {
                            curtainsOpen && status == "playing" -> "● NOW SCREENING"
                            curtainsOpen -> "❚❚ SCREENING PAUSED"
                            status == "scheduled" -> "PREMIERE WAITING ROOM"
                            else -> "FEATURE PRESENTATION"
                        },
                        color = Color(0xFFF6C978),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                    )
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (curtainsOpen) {
                            val sec = livePosition.toInt().coerceAtLeast(0)
                            "Synced at ${sec / 60}:${"%02d".format(sec % 60)}"
                        } else {
                            "$readyCount of $memberCount seats ready" + if (subtitle.isNotBlank()) " · $subtitle" else ""
                        },
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

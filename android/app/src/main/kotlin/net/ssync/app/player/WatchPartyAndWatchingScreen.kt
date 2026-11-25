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
import androidx.compose.foundation.lazy.itemsIndexed
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

data class MobilePartySeed(
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
    initialSeed: MobilePartySeed? = null,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<WatchingSession>>(emptyList()) }
    var parties by remember { mutableStateOf<List<WatchParty>>(emptyList()) }
    var together by remember { mutableStateOf<List<TogetherTitle>>(emptyList()) }
    var sharing by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreate by remember(initialSeed) { mutableStateOf(initialSeed != null) }
    var createSeed by remember(initialSeed) { mutableStateOf<MobilePartySeed?>(initialSeed) }
    var activePartyId by remember { mutableStateOf<String?>(null) }

    fun jumpIntoLiveParty(party: WatchParty) {
        scope.launch {
            if (!party.isMember) {
                api.joinWatchParty(party.id, ready = true)
            }
            val fresh = (api.fetchWatchParty(party.id) as? MusicApiResult.Success)?.data ?: party
            val art = api.artwork.resolve(fresh.meta?.backdrop ?: fresh.meta?.artwork)
                ?: api.artwork.forItem(fresh.itemType, fresh.itemId, preferBackdrop = true)
            coordinator.playVideo(
                ActivePlayable(
                    itemType = fresh.itemType,
                    itemId = fresh.itemId,
                    title = fresh.title,
                    subtitle = "Watch Party · ${fresh.hostProfile?.name ?: "Host"}",
                    artworkUrl = art,
                    streamUrl = api.streamUrl(fresh.itemType, fresh.itemId),
                    isVideo = true,
                    resumePositionMs = (fresh.livePosition * 1000.0).toLong().coerceAtLeast(0L),
                    partyId = fresh.id,
                ),
            )
        }
    }

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

    val onScreen = rememberOnScreen()
    LaunchedEffect(api) {
        isLoading = true
        refreshAll()
        isLoading = false
        while (true) {
            delay(8_000L)
            if (onScreen.value) refreshAll()
        }
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    val isTv = net.ssync.pairing.ui.LocalIsTv.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isTv) Modifier.background(
                    Brush.radialGradient(listOf(SoulSyncColors.Accent.copy(alpha = 0.12f), Color(0xFF0A0C16))),
                ) else Modifier,
            ),
        contentPadding = PaddingValues(
            start = if (isTv) 78.dp else 16.dp,
            end = if (isTv) 32.dp else 16.dp,
            top = if (isTv) 22.dp else 16.dp,
            bottom = 36.dp,
        ),
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

        // ── Section 1: Cinema Lounge Hero Spotlight / Active Watch Parties ──
        if (parties.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SoulSyncColors.Card,
                    border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            createSeed = null
                            showCreate = true
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        SoulSyncColors.Accent.copy(alpha = 0.16f),
                                        Color(0xFF0D121D),
                                    ),
                                ),
                            )
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "★ SOULSYNC THEATER LOUNGE",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp,
                        )
                        Text(
                            text = "Watch movies & shows together in sync",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = "Open a waiting room with music from your library, unfurl the velvet curtains when everyone is ready, and react live with floating emojis, chat, and pee-break pauses.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(2.dp))
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
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        ) {
                            Text("🍿 Open a Theater Room", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            val featured = parties.first()
            val extraParties = parties.drop(1)
            item(key = "featured-party-${featured.id}") {
                val bgUrl = api.artwork.resolve(featured.meta?.backdrop ?: featured.meta?.artwork)
                    ?: api.artwork.forItem(featured.itemType, featured.itemId, preferBackdrop = true)
                val isLive = featured.status == "playing" || featured.status == "paused"
                val statusLabel = when (featured.status) {
                    "playing" -> "LIVE NOW · ${formatClockSec(featured.livePosition)}"
                    "paused" -> "PAUSED · ${formatClockSec(featured.livePosition)}"
                    "scheduled" -> "SCHEDULED · ${formatCountdown(featured.scheduledFor)}"
                    else -> "THEATER LOBBY · ${featured.readyCount}/${featured.memberCount} READY"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(226.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0D121D))
                        .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.36f), RoundedCornerShape(20.dp))
                        .clickable {
                            if (isLive) jumpIntoLiveParty(featured) else activePartyId = featured.id
                        },
                ) {
                    SoulSyncArtwork(
                        url = bgUrl,
                        contentDescription = featured.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0x55070A10),
                                        Color(0xCC070A10),
                                        Color(0xFA070A10),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xB8070A10))
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isLive) Color(0xFF22C55E) else SoulSyncColors.AccentLight),
                                )
                                Text(
                                    text = statusLabel,
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                            Text(
                                text = "${featured.memberCount} ${if (featured.memberCount == 1) "viewer" else "viewers"}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0x99070A10))
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = featured.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("Hosted by ${featured.hostProfile?.name ?: "Host"}")
                                    featured.meta?.subtitle?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                },
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                if (isLive) {
                                    Button(
                                        onClick = { jumpIntoLiveParty(featured) },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = Color.Black,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Jump In (${formatClockSec(featured.livePosition)})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { activePartyId = featured.id },
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                    ) {
                                        Text("Lounge", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    Button(
                                        onClick = { activePartyId = featured.id },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = Color.Black,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (featured.status == "scheduled") "RSVP & Enter" else "Enter Theater Lobby",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (extraParties.isNotEmpty()) {
                itemsIndexed(extraParties, key = { idx, party -> "party-$idx-${party.id}" }) { _, party ->
                    WatchPartyCinemaCard(
                        party = party,
                        api = api,
                        onOpen = { activePartyId = party.id },
                    )
                }
            }
        }

        // ── Section 2: On Air Right Now (Live Screens) ──
        item {
            SectionHeader("On Air Right Now")
        }
        if (sessions.isEmpty()) {
            item {
                EmptyBox("All screens are quiet — live sessions appear here.")
            }
        } else {
            itemsIndexed(sessions, key = { idx, s -> "s-$idx-${s.profileId}-${s.itemType}-${s.itemId}" }) { _, s ->
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
                        val parentInt = s.meta?.parentId?.toIntOrNull()
                        when (s.itemType) {
                            "movie" -> if (idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                            "show" -> if (idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                            "episode" -> if (parentInt != null) onNavigate(PlayerDestination.ShowDetail(parentInt))
                            "youtube" -> onNavigate(PlayerDestination.YouTube)
                            "track" -> if (parentInt != null) onNavigate(PlayerDestination.AlbumDetail(parentInt))
                        }
                    },
                )
            }
        }

        // ── Section 3: Finish Together (60-Day Shared Watch History — Horizontal Strip) ──
        if (together.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Finish Together")
                    Text(
                        text = "Shared watch history · last 60 days",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(
                        together,
                        key = { idx, t -> "tog-$idx-${t.key.ifBlank { "${t.itemType}:${t.itemId}" }}" },
                    ) { _, t ->
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
                            modifier = Modifier.width(276.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SoulSyncArtwork(
                                    url = artUrl,
                                    contentDescription = title,
                                    modifier = Modifier
                                        .width(50.dp)
                                        .height(74.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (othersSummary.isNotBlank()) {
                                        Text(
                                            text = "With $othersSummary",
                                            color = SoulSyncColors.TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (mine != null && !mine.finished) {
                                            Button(
                                                onClick = {
                                                    val isAudio = itemType == "track" || itemType == "audiobook" || itemType == "podcast_episode"
                                                    val playable = ActivePlayable(
                                                        itemType = itemType,
                                                        itemId = itemId,
                                                        title = title,
                                                        subtitle = mine.meta?.subtitle ?: "",
                                                        artworkUrl = artUrl,
                                                        streamUrl = api.streamUrl(itemType, itemId),
                                                        isVideo = !isAudio,
                                                        resumePositionMs = (mine.position * 1000.0).toLong().coerceAtLeast(0L),
                                                    )
                                                    if (isAudio) coordinator.playAudio(playable) else coordinator.playVideo(playable)
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color.White,
                                                    contentColor = Color.Black,
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                            ) {
                                                Text("Resume", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                            }
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
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                            ) {
                                                Text("🍿 Party", color = SoulSyncColors.AccentLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                        modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
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
                val canOpenDetails = session.itemType in setOf("movie", "show", "youtube") ||
                    (session.itemType in setOf("episode", "track") && !session.meta?.parentId.isNullOrBlank())
                if (canOpenDetails) {
                    OutlinedButton(
                        onClick = onDetails,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                        modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                    ) {
                        Text("Details", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
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
                artwork = if (m.hasPoster) "/api/video/poster/movie/${m.id}?w=342" else null,
                backdrop = if (m.hasBackdrop) "/api/video/backdrop/movie/${m.id}?w=1280" else null,
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
                artwork = if (sh.hasPoster) "/api/video/poster/show/${sh.id}?w=342" else null,
                backdrop = if (sh.hasBackdrop) "/api/video/backdrop/show/${sh.id}?w=1280" else null,
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
                    IconButton(onClick = onDismiss, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
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
                    modifier = Modifier.fillMaxWidth().height(46.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
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

private data class RoomSideEmojiBubble(
    val uid: String,
    val emoji: String,
    val sender: String,
    val isLeft: Boolean,
    val edgeDp: Float,
    val swayDp: Float,
)

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
    var starting by remember { mutableStateOf(false) }
    var prevStatus by remember { mutableStateOf<String?>(null) }
    var bubbles by remember { mutableStateOf<List<RoomSideEmojiBubble>>(emptyList()) }
    var rateLimitedUntilMs by remember { mutableStateOf(0L) }
    var roomToast by remember { mutableStateOf<String?>(null) }
    val seenMsgIds = remember(partyId) { HashSet<Int>() }
    var msgsInitialized by remember(partyId) { mutableStateOf(false) }
    val reactionTimestamps = remember(partyId) { mutableListOf<Long>() }

    fun spawnRoomBubble(uid: String, emoji: String, sender: String, seed: Int) {
        val positiveSeed = seed and 0x7FFFFFFF
        val isLeft = (positiveSeed % 2 == 0)
        val edgeDp = (12f + (((positiveSeed % 38) * 19) % 38)).coerceIn(8f, 52f)
        val swayDp = (if (positiveSeed % 3 == 0) 1f else -1f) * (10f + (positiveSeed % 14))
        val b = RoomSideEmojiBubble(uid, emoji, sender, isLeft, edgeDp, swayDp)
        bubbles = (bubbles.takeLast(14)) + b
        scope.launch {
            delay(2800L)
            bubbles = bubbles.filterNot { it.uid == uid }
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

    suspend fun refreshRoom() {
        val res = api.fetchWatchParty(partyId)
        if (res is MusicApiResult.Success) {
            val fetched = res.data
            party = fetched
            val msgs = fetched.messages
            if (!msgsInitialized) {
                msgsInitialized = true
                msgs.forEach { seenMsgIds += it.id }
            } else {
                for (m in msgs) {
                    if (seenMsgIds.add(m.id)) {
                        val body = m.body.trim()
                        if (m.kind == "reaction" || body in PARTY_EMOJIS || body.startsWith("🚽")) {
                            spawnRoomBubble("room-srv-${m.id}", body, m.profile?.name.orEmpty(), m.id)
                        }
                    }
                }
            }
        }
    }

    // Auto-transition everyone in the waiting room into the fullscreen video player when the movie starts!
    LaunchedEffect(party?.status) {
        val p = party ?: return@LaunchedEffect
        val old = prevStatus
        prevStatus = p.status
        if ((old == "lobby" || old == "scheduled") && p.status == "playing" && !starting) {
            launchSyncedVideo(p)
        }
    }

    LaunchedEffect(partyId, api) {
        refreshRoom()
        while (true) {
            delay(2_200L)
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

    fun sendRoomReaction(p: WatchParty, emoji: String) {
        val now = System.currentTimeMillis()
        reactionTimestamps.removeAll { now - it >= 4_000L }
        if (reactionTimestamps.size >= 5) {
            rateLimitedUntilMs = now + 2_800L
            roomToast = "Emoji cooldown (max 5 reactions per 4s)…"
            scope.launch {
                delay(2_800L)
                if (roomToast?.startsWith("Emoji") == true) roomToast = null
            }
            return
        }
        reactionTimestamps += now
        if (reactionTimestamps.size >= 5) {
            rateLimitedUntilMs = now + 2_500L
        }
        val seed = (now.toInt() + reactionTimestamps.size * 17)
        spawnRoomBubble("room-local-$now-${reactionTimestamps.size}", emoji, "You", seed)
        scope.launch {
            val r = api.postWatchPartyChat(p.id, "reaction", emoji)
            if (r is MusicApiResult.Success) seenMsgIds += r.data.id
            refreshRoom()
        }
    }

    var lobbyMuted by remember(partyId) { mutableStateOf(false) }
    var lastAutoPlayedLobbyTrackId by remember(partyId) { mutableStateOf<String?>(null) }

    // Auto-play synced SoulSync library lobby music while waiting in the Theater Room
    LaunchedEffect(party?.lobbyTrack?.trackId, party?.status, lobbyMuted) {
        val p = party ?: return@LaunchedEffect
        val lt = p.lobbyTrack
        val shouldPlay = !lobbyMuted && lt != null && (p.status == "lobby" || p.status == "scheduled")
        if (shouldPlay && lt != null) {
            if (lastAutoPlayedLobbyTrackId != lt.trackId || !coordinator.isPlaying) {
                lastAutoPlayedLobbyTrackId = lt.trackId
                coordinator.playAudio(
                    ActivePlayable(
                        itemType = "track",
                        itemId = lt.trackId,
                        title = lt.title,
                        subtitle = "${lt.artist} · Theater Lobby",
                        artworkUrl = api.artwork.resolve(lt.artwork) ?: api.artwork.albumArtwork(lt.albumId),
                        streamUrl = api.streamUrl("track", lt.trackId),
                        resumePositionMs = (lt.livePosition * 1000.0).toLong().coerceAtLeast(0L),
                    ),
                )
            }
        } else if (lobbyMuted && coordinator.activeItem?.subtitle?.contains("Theater Lobby") == true && coordinator.isPlaying) {
            coordinator.togglePlayPause()
        }
    }

    androidx.compose.runtime.DisposableEffect(partyId) {
        onDispose {
            if (coordinator.activeItem?.subtitle?.contains("Theater Lobby") == true && coordinator.isPlaying) {
                coordinator.togglePlayPause()
            }
        }
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
            val majorityReady = p.majorityReady || (p.memberCount > 0 && p.readyCount * 2 > p.memberCount)
            val canStartFromLobby = p.isHost || (p.memberCount > 1 && majorityReady) || p.canControl
            val peeBreak = p.peeBreak
            val isMyPeeBreak = peeBreak != null && me?.profileId == peeBreak.profileId
            val isRateLimited = System.currentTimeMillis() < rateLimitedUntilMs

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Hero top bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(126.dp),
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
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
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
                                    "paused" -> if (peeBreak != null) "🚽 PEE BREAK · ${formatClockSec(p.livePosition)}" else "❚❚ PAUSED · ${formatClockSec(p.livePosition)}"
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
                        // Cinema Feature Presentation Showcase Card (Velvet curtains unfurl full-screen over the video player!)
                        item {
                            val posterUrl = api.artwork.resolve(p.meta?.artwork ?: p.meta?.backdrop)
                                ?: api.artwork.forItem(p.itemType, p.itemId, preferBackdrop = false)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF101524),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = posterUrl,
                                        contentDescription = p.title,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(
                                            text = when (p.status) {
                                                "playing" -> "● NOW SCREENING · ${formatClockSec(p.livePosition)}"
                                                "paused" -> "❚❚ PAUSED AT ${formatClockSec(p.livePosition)}"
                                                else -> "★ FEATURE PRESENTATION"
                                            },
                                            color = Color(0xFFF6C978),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 1.2.sp,
                                        )
                                        Text(
                                            text = p.title,
                                            color = Color.White,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!p.meta?.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = p.meta?.subtitle.orEmpty(),
                                                color = SoulSyncColors.TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = if (majorityReady) Color(0xFF10B981).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (majorityReady) Color(0xFF34D399).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f),
                                            ),
                                        ) {
                                            Text(
                                                text = if (majorityReady) {
                                                    "✓ ${p.readyCount}/${p.memberCount} Ready · Majority Ready!"
                                                } else {
                                                    "${p.readyCount}/${p.memberCount} Ready · Majority or Host starts"
                                                },
                                                color = if (majorityReady) Color(0xFF6EE7B7) else SoulSyncColors.TextSoft,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Active 🚽 5-Minute Pee Break Banner
                        if (peeBreak != null) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFF2B1C0C),
                                    border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.55f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text("🚽", fontSize = 24.sp)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${peeBreak.profile?.name ?: "Someone"} called a 5-min pee break!",
                                                color = Color(0xFFFEF3C7),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                            )
                                            Text(
                                                text = "Stream paused for everyone until they return.",
                                                color = Color(0xFFFCD34D),
                                                fontSize = 11.5.sp,
                                            )
                                        }
                                        if (isMyPeeBreak || p.isHost) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val r = api.updateWatchPartyState(p.id, action = "resume")
                                                        if (r is MusicApiResult.Success) launchSyncedVideo(r.data)
                                                    }
                                                },
                                                shape = CircleShape,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF10B981),
                                                    contentColor = Color(0xFF041B11),
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.height(34.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                            ) {
                                                Text("I'm Back (5s)", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                                            }
                                        }
                                    }
                                }
                            }
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
                                        OutlinedButton(
                                            onClick = {
                                                lobbyMuted = !lobbyMuted
                                                if (!lobbyMuted) {
                                                    lastAutoPlayedLobbyTrackId = null
                                                }
                                            },
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Text(
                                                text = if (lobbyMuted) "Unmute" else "Mute",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        if (p.canControl) {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        api.updateWatchPartyState(p.id, shuffleLobbyMusic = true)
                                                        refreshRoom()
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                                            ) {
                                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle lobby track", tint = Color.White, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!roomToast.isNullOrBlank()) {
                            item {
                                Text(
                                    text = roomToast.orEmpty(),
                                    color = Color(0xFFFBBF24),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // Premier Screening Room Action Controls
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (p.status == "lobby" || p.status == "scheduled") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                if (starting) return@Button
                                                starting = true
                                                scope.launch {
                                                    val r = api.updateWatchPartyState(p.id, action = "start")
                                                    if (r is MusicApiResult.Success) {
                                                        launchSyncedVideo(r.data)
                                                    } else {
                                                        starting = false
                                                    }
                                                }
                                            },
                                            enabled = canStartFromLobby && !starting,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor = Color(0xFF090C14),
                                                disabledContainerColor = Color.White.copy(alpha = 0.25f),
                                                disabledContentColor = Color.White.copy(alpha = 0.55f),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1.25f).height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = if (starting) "Starting…" else "Start Screening",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    api.joinWatchParty(p.id, ready = !(me?.ready ?: false))
                                                    refreshRoom()
                                                }
                                            },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (me?.ready == true) Color(0x2834D399) else Color.White.copy(alpha = 0.08f),
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                if (me?.ready == true) Color(0x9934D399) else Color.White.copy(alpha = 0.18f),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(0.9f).height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            if (me?.ready == true) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = if (me?.ready == true) "Ready" else "Mark Ready",
                                                color = if (me?.ready == true) Color(0xFF6EE7B7) else Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp,
                                            )
                                        }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { launchSyncedVideo(p) },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor = Color(0xFF090C14),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1f).height(38.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("Join Live", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val act = if (p.status == "playing") "pause" else "resume"
                                                    val r = api.updateWatchPartyState(p.id, action = act)
                                                    refreshRoom()
                                                    if (act == "resume" && r is MusicApiResult.Success) {
                                                        launchSyncedVideo(r.data)
                                                    }
                                                }
                                            },
                                            enabled = peeBreak == null || isMyPeeBreak || p.isHost,
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                                            modifier = Modifier.height(44.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Text(
                                                text = if (p.status == "playing") "⏸ Pause All" else "▶ Resume (5s)",
                                                color = Color.White,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        val cooldownMin = ((p.peeCooldownRemaining + 59) / 60).coerceAtLeast(1)
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val r = api.updateWatchPartyState(p.id, action = "pee_break")
                                                    if (r is MusicApiResult.Success) {
                                                        sendRoomReaction(p, "🚽💨")
                                                        refreshRoom()
                                                    } else if (r is MusicApiResult.Error) {
                                                        roomToast = r.message
                                                    }
                                                }
                                            },
                                            enabled = p.peeCooldownRemaining <= 0 && peeBreak == null,
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.45f)),
                                            modifier = Modifier.height(44.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Text(
                                                text = if (p.peeCooldownRemaining > 0) "🚽 ${cooldownMin}m" else "🚽 5m",
                                                color = Color(0xFFFDE68A),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (p.isMember) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    api.leaveWatchParty(p.id)
                                                    coordinator.detachWatchParty()
                                                    onDismiss()
                                                }
                                            },
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.45f)),
                                            modifier = Modifier.weight(1f).height(36.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Text("Leave Watch Party", color = Color(0xFFFDA4AF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (p.isHost) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    api.deleteWatchParty(p.id)
                                                    coordinator.detachWatchParty()
                                                    onDismiss()
                                                }
                                            },
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                                            modifier = Modifier.weight(1f).height(36.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                        ) {
                                            Text("End Watch Party", color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Guest Roster
                        item {
                            Text(
                                text = "IN THE ROOM (${p.readyCount}/${p.memberCount} READY" + (if (majorityReady) " · MAJORITY READY" else "") + ")",
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
                                    color = if (isRateLimited) SoulSyncColors.Card.copy(alpha = 0.45f) else SoulSyncColors.Card,
                                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isRateLimited) {
                                            sendRoomReaction(p, em)
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
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = SoulSyncColors.AccentLight)
                            }
                        }
                    }
                }

                // Dual-Side (Left + Right) Floating Emoji Bubbles inside the Room Dialog
                bubbles.forEach { b ->
                    androidx.compose.runtime.key(b.uid) {
                        val safeEdge = b.edgeDp.coerceAtLeast(0f)
                        RoomFloatingEmojiBubbleView(
                            bubble = b,
                            modifier = Modifier
                                .align(if (b.isLeft) Alignment.BottomStart else Alignment.BottomEnd)
                                .padding(
                                    start = if (b.isLeft) safeEdge.dp else 0.dp,
                                    end = if (!b.isLeft) safeEdge.dp else 0.dp,
                                    bottom = 96.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomFloatingEmojiBubbleView(
    bubble: RoomSideEmojiBubble,
    modifier: Modifier = Modifier,
) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bubble.uid) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2700, easing = FastOutSlowInEasing),
        )
    }
    val p = progress.value
    val yOffset = (-260f * p).dp
    val xOffset = (kotlin.math.sin(p * Math.PI * 2.0).toFloat() * bubble.swayDp).dp
    val alpha = when {
        p < 0.14f -> p / 0.14f
        p > 0.72f -> ((1f - p) / 0.28f).coerceIn(0f, 1f)
        else -> 1f
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.offset(x = xOffset, y = yOffset),
    ) {
        Text(
            text = bubble.emoji,
            fontSize = 26.sp,
            color = Color.White.copy(alpha = alpha),
        )
        if (bubble.sender.isNotBlank()) {
            Surface(
                shape = CircleShape,
                color = Color(0xBB080B12).copy(alpha = alpha * 0.85f),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.16f * alpha)),
            ) {
                Text(
                    text = bubble.sender,
                    color = Color.White.copy(alpha = alpha),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

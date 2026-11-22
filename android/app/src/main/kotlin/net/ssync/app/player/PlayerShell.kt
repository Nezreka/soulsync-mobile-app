package net.ssync.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import net.ssync.app.R
import net.ssync.network.CandidateAddressRacer
import net.ssync.network.CertPinningOkHttp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.storage.KeystoreServerVault
import net.ssync.storage.PairedServerRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerShell(
    record: PairedServerRecord,
    mediaController: MediaController?,
    onDeviceRevoked: () -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pinnedClient = remember(record) {
        CertPinningOkHttp.createPinnedClient(
            expectedCertFingerprintSha256 = record.certFingerprintSha256,
            deviceTokenProvider = { record.deviceToken },
            onUnauthorized = onDeviceRevoked,
        )
    }
    var activeBaseUrl by remember(record) { mutableStateOf(record.lastWorkingBaseUrl) }
    val api = remember(activeBaseUrl, pinnedClient) {
        PlayerApiClient(
            baseUrl = activeBaseUrl,
            pinnedAuthenticatedClient = pinnedClient,
        )
    }

    // Seamless Wi-Fi <-> 5G LAN/WAN failover: race cached candidate addresses on start
    LaunchedEffect(record, pinnedClient) {
        if (record.candidateAddresses.size > 1) {
            val best = CandidateAddressRacer(pinnedClient).selectBestBaseUrl(record.candidateAddresses)
            if (best != null && best != activeBaseUrl) {
                activeBaseUrl = best
                KeystoreServerVault(context.applicationContext).save(record.copy(lastWorkingBaseUrl = best))
            }
        }
    }
    val imageLoader = remember(context, pinnedClient) {
        createPinnedImageLoader(context.applicationContext, pinnedClient)
    }
    val coordinator = remember(api) {
        PlaybackCoordinator(
            api = api,
            scope = scope,
            controllerProvider = { mediaController },
        )
    }

    var backStack by remember { mutableStateOf<List<PlayerDestination>>(listOf(PlayerDestination.Home)) }
    val current = backStack.last()
    var showMoreSheet by remember { mutableStateOf(false) }
    var sidebarCollapsed by remember { mutableStateOf(false) }
    var headerScrolled by remember { mutableStateOf(false) }
    var watchingOthersCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(current) {
        headerScrolled = false
    }

    fun navigateTo(dest: PlayerDestination, clearToRoot: Boolean = false) {
        backStack = if (clearToRoot) listOf(dest) else backStack + dest
    }

    fun popBack() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    BackHandler(enabled = coordinator.showVideoOverlay || coordinator.showAudioSheet || backStack.size > 1) {
        when {
            coordinator.showVideoOverlay -> coordinator.closeVideo(coordinator.positionMs, coordinator.durationMs)
            coordinator.showAudioSheet -> coordinator.showAudioSheet = false
            else -> popBack()
        }
    }

    LaunchedEffect(coordinator, mediaController) {
        while (true) {
            coordinator.syncAudioProgress()
            delay(1_000L)
        }
    }
    LaunchedEffect(api) {
        (api.fetchPresence() as? MusicApiResult.Success)?.let { p ->
            watchingOthersCount = p.data.sessions.count { !it.you }
        }
    }

    val hasFullBleedHero = current is PlayerDestination.Home ||
        current is PlayerDestination.MovieDetail ||
        current is PlayerDestination.ShowDetail

    CompositionLocalProvider(LocalSoulSyncImageLoader provides imageLoader) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(SoulSyncColors.BgPrimary),
        ) {
            val useSidebar = maxWidth >= 700.dp

            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Adaptive 1:1 `/player` Left Glass Sidebar on Tablets, Landscape & Android TV
                    if (useSidebar) {
                        PlayerSidebar(
                            current = current,
                            collapsed = sidebarCollapsed,
                            watchingCount = watchingOthersCount,
                            onNavigate = { navigateTo(it, clearToRoot = true) },
                            onToggleCollapse = { sidebarCollapsed = !sidebarCollapsed },
                        )
                    }

                    // Main Content + Floating Translucent Header
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = if (hasFullBleedHero) 0.dp else 64.dp),
                        ) {
                            PlayerContent(
                                current = current,
                                record = record,
                                api = api,
                                coordinator = coordinator,
                                onNavigate = { navigateTo(it) },
                                onScrolledChanged = { headerScrolled = it },
                                onDeviceRevoked = onDeviceRevoked,
                                onUnpair = onUnpair,
                            )
                        }

                        // Floating Translucent Top Header (clear over Hero top, solid frosted once scrolled)
                        FloatingTopHeader(
                            record = record,
                            showBack = backStack.size > 1,
                            showBrand = !useSidebar,
                            transparentOverHero = hasFullBleedHero && !headerScrolled,
                            onBack = { popBack() },
                            onSearch = { navigateTo(PlayerDestination.Search) },
                            onSettings = { navigateTo(PlayerDestination.Settings) },
                        )
                    }
                }

                // Persistent Mini Transport Bar
                val active = coordinator.activeItem
                if (active != null && !active.isVideo) {
                    MiniTransportBar(
                        item = active,
                        isPlaying = coordinator.isPlaying,
                        onExpand = { coordinator.showAudioSheet = true },
                        onTogglePlayPause = { coordinator.togglePlayPause() },
                        onNext = { coordinator.skipNext() },
                    )
                }

                // Phone Bottom Glass Tab Bar (mirrors `player.module.css @media (max-width: 768px)`)
                if (!useSidebar) {
                    HorizontalDivider(color = SoulSyncColors.Line, thickness = 1.dp)
                    NavigationBar(
                        containerColor = SoulSyncColors.BgPrimary.copy(alpha = 0.96f),
                        contentColor = SoulSyncColors.TextPrimary,
                        tonalElevation = 0.dp,
                    ) {
                        val activeTab = tabForDestination(current)
                        BottomTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    when (tab) {
                                        BottomTab.HOME -> navigateTo(PlayerDestination.Home, clearToRoot = true)
                                        BottomTab.GUIDE -> navigateTo(PlayerDestination.Guide, clearToRoot = true)
                                        BottomTab.MOVIES -> navigateTo(PlayerDestination.Movies, clearToRoot = true)
                                        BottomTab.TV -> navigateTo(PlayerDestination.Tv, clearToRoot = true)
                                        BottomTab.MUSIC -> navigateTo(PlayerDestination.Music, clearToRoot = true)
                                        BottomTab.MORE -> showMoreSheet = true
                                    }
                                },
                                icon = {
                                    if (tab == BottomTab.MORE && watchingOthersCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$watchingOthersCount") } }) {
                                            Icon(iconForTab(tab), contentDescription = tab.label, modifier = Modifier.size(22.dp))
                                        }
                                    } else {
                                        Icon(iconForTab(tab), contentDescription = tab.label, modifier = Modifier.size(22.dp))
                                    }
                                },
                                label = { Text(tab.label, fontSize = 10.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SoulSyncColors.AccentLight,
                                    selectedTextColor = SoulSyncColors.AccentLight,
                                    indicatorColor = SoulSyncColors.Accent.copy(alpha = 0.16f),
                                    unselectedIconColor = SoulSyncColors.TextSecondary,
                                    unselectedTextColor = SoulSyncColors.TextSecondary,
                                ),
                            )
                        }
                    }
                }
            }

            if (showMoreSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showMoreSheet = false },
                    containerColor = SoulSyncColors.PanelSolid,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        MoreSheetRow(Icons.Default.Bookmark, "Watchlist") {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.Watchlist)
                        }
                        MoreSheetRow(
                            icon = Icons.Default.Group,
                            label = if (watchingOthersCount > 0) "Watching now ($watchingOthersCount)" else "Watching now",
                        ) {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.WatchingNow)
                        }
                        MoreSheetRow(Icons.Default.SmartDisplay, "YouTube") {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.YouTube)
                        }
                        MoreSheetRow(Icons.Default.Podcasts, "Podcasts") {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.Podcasts)
                        }
                        MoreSheetRow(Icons.Default.Headphones, "Audiobooks") {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.Audiobooks)
                        }
                        MoreSheetRow(Icons.Default.Settings, "Player Settings") {
                            showMoreSheet = false
                            navigateTo(PlayerDestination.Settings)
                        }
                    }
                }
            }

            if (coordinator.showAudioSheet) {
                AudioNowPlayingSheet(
                    coordinator = coordinator,
                    onDismiss = { coordinator.showAudioSheet = false },
                )
            }

            val videoItem = coordinator.activeItem
            if (coordinator.showVideoOverlay && videoItem != null) {
                VideoPlayerOverlay(
                    item = videoItem,
                    pinnedHttpClient = pinnedClient,
                    onClose = { pos, dur -> coordinator.closeVideo(pos, dur) },
                )
            }
        }
    }
}

@Composable
private fun FloatingTopHeader(
    record: PairedServerRecord,
    showBack: Boolean,
    showBrand: Boolean,
    transparentOverHero: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val bgModifier = if (transparentOverHero) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(SoulSyncColors.BgPrimary.copy(alpha = 0.78f), Color.Transparent),
            ),
        )
    } else {
        Modifier
            .background(SoulSyncColors.BgPrimary.copy(alpha = 0.92f))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(bgModifier)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SoulSyncColors.PanelGlass)
                    .dpadFocusable(CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(10.dp))
        } else if (showBrand) {
            Image(
                painter = painterResource(id = R.drawable.soulsync_mark),
                contentDescription = "SoulSync",
                modifier = Modifier.size(30.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SoulSync",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = (-0.3).sp,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Profile chip (exact `/player` top-right pill style)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SoulSyncColors.PanelGlass)
                .border(1.dp, SoulSyncColors.LineStrong, RoundedCornerShape(999.dp))
                .clickable(onClick = onSettings)
                .dpadFocusable(RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SoulSyncColors.AccentLight),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = record.profileName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = onSearch,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SoulSyncColors.PanelGlass)
                .dpadFocusable(CircleShape),
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun PlayerSidebar(
    current: PlayerDestination,
    collapsed: Boolean,
    watchingCount: Int,
    onNavigate: (PlayerDestination) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(if (collapsed) 72.dp else 224.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x0AFFFFFF), Color(0x05FFFFFF)),
                ),
            )
            .border(BorderStroke(1.dp, SoulSyncColors.Line))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.soulsync_mark),
                contentDescription = "SoulSync",
                modifier = Modifier.size(32.dp),
            )
            if (!collapsed) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SoulSync",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = (-0.2).sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        SidebarItem(Icons.Default.Home, "Home", current is PlayerDestination.Home, collapsed) { onNavigate(PlayerDestination.Home) }
        SidebarItem(Icons.Default.LiveTv, "Guide", current is PlayerDestination.Guide, collapsed) { onNavigate(PlayerDestination.Guide) }
        SidebarItem(Icons.Default.Bookmark, "Watchlist", current is PlayerDestination.Watchlist, collapsed) { onNavigate(PlayerDestination.Watchlist) }
        SidebarItem(
            icon = Icons.Default.Group,
            label = if (watchingCount > 0) "Watching ($watchingCount)" else "Watching now",
            selected = current is PlayerDestination.WatchingNow,
            collapsed = collapsed,
        ) { onNavigate(PlayerDestination.WatchingNow) }

        if (!collapsed) SidebarGroupLabel("WATCH") else Spacer(Modifier.height(10.dp))
        SidebarItem(Icons.Default.Movie, "Movies", current is PlayerDestination.Movies || current is PlayerDestination.MovieDetail, collapsed) { onNavigate(PlayerDestination.Movies) }
        SidebarItem(Icons.Default.Tv, "TV", current is PlayerDestination.Tv || current is PlayerDestination.ShowDetail, collapsed) { onNavigate(PlayerDestination.Tv) }
        SidebarItem(Icons.Default.SmartDisplay, "YouTube", current is PlayerDestination.YouTube || current is PlayerDestination.YouTubeChannel, collapsed) { onNavigate(PlayerDestination.YouTube) }

        if (!collapsed) SidebarGroupLabel("LISTEN") else Spacer(Modifier.height(10.dp))
        SidebarItem(Icons.Default.MusicNote, "Music", current is PlayerDestination.Music || current is PlayerDestination.AlbumDetail || current is PlayerDestination.ArtistDetail || current is PlayerDestination.PlaylistDetail, collapsed) { onNavigate(PlayerDestination.Music) }
        SidebarItem(Icons.Default.Podcasts, "Podcasts", current is PlayerDestination.Podcasts || current is PlayerDestination.PodcastDetail, collapsed) { onNavigate(PlayerDestination.Podcasts) }
        SidebarItem(Icons.Default.Headphones, "Audiobooks", current is PlayerDestination.Audiobooks || current is PlayerDestination.AudiobookDetail, collapsed) { onNavigate(PlayerDestination.Audiobooks) }

        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(color = SoulSyncColors.Line, modifier = Modifier.padding(vertical = 10.dp))

        SidebarItem(Icons.Default.Settings, "Settings", current is PlayerDestination.Settings, collapsed) { onNavigate(PlayerDestination.Settings) }
        SidebarItem(
            icon = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
            label = "Collapse",
            selected = false,
            collapsed = collapsed,
            onClick = onToggleCollapse,
        )
    }
}

@Composable
private fun SidebarGroupLabel(text: String) {
    Text(
        text = text,
        color = SoulSyncColors.TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) SoulSyncColors.Accent.copy(alpha = 0.16f) else Color.Transparent
    val fg = if (selected) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        if (!collapsed) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = if (selected) Color.White else fg,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerContent(
    current: PlayerDestination,
    record: PairedServerRecord,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onScrolledChanged: (Boolean) -> Unit,
    onDeviceRevoked: () -> Unit,
    onUnpair: () -> Unit,
) {
    var openHomeEditor by remember { mutableStateOf(false) }
    when (current) {
        PlayerDestination.Home -> HomeScreen(
            api = api,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
            onScrolledChanged = onScrolledChanged,
            openEditorInitially = openHomeEditor,
            onEditorConsumed = { openHomeEditor = false },
        )
        PlayerDestination.Guide -> GuideScreen(api, coordinator, onDeviceRevoked)
        PlayerDestination.Movies -> MoviesScreen(api, { onNavigate(PlayerDestination.MovieDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.MovieDetail -> MovieDetailScreen(current.movieId, api, coordinator, onNavigate, onDeviceRevoked)
        PlayerDestination.Tv -> TvScreen(api, { onNavigate(PlayerDestination.ShowDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.ShowDetail -> ShowDetailScreen(current.showId, api, coordinator, onDeviceRevoked)
        is PlayerDestination.PersonDetail -> PersonDetailScreen(current.tmdbId, current.name, api, onNavigate, onDeviceRevoked)
        PlayerDestination.Music -> MusicScreen(api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.ArtistDetail -> ArtistDetailScreen(current.artistId, api, { onNavigate(PlayerDestination.AlbumDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.AlbumDetail -> AlbumDetailScreen(current.albumId, api, coordinator, onDeviceRevoked)
        is PlayerDestination.PlaylistDetail -> PlaylistDetailScreen(current.playlistId, api, coordinator, onDeviceRevoked)
        PlayerDestination.YouTube -> YouTubeScreen(api, coordinator, { id, title -> onNavigate(PlayerDestination.YouTubeChannel(id, title)) }, onDeviceRevoked)
        is PlayerDestination.YouTubeChannel -> YouTubeChannelScreen(current.channelId, current.title, api, coordinator, onDeviceRevoked)
        PlayerDestination.Podcasts -> PodcastsScreen(api, { onNavigate(PlayerDestination.PodcastDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.PodcastDetail -> PodcastDetailScreen(current.sub, api, coordinator, onDeviceRevoked)
        PlayerDestination.Audiobooks -> AudiobooksScreen(api, { onNavigate(PlayerDestination.AudiobookDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.AudiobookDetail -> AudiobookDetailScreen(current.book, api, coordinator)
        PlayerDestination.Watchlist -> WatchlistScreen(api, onNavigate, onDeviceRevoked)
        PlayerDestination.WatchingNow -> WatchingNowScreen(api, onDeviceRevoked)
        PlayerDestination.Search -> SearchScreen(api, coordinator, onNavigate, onDeviceRevoked)
        PlayerDestination.Settings -> SettingsScreen(
            record = record,
            api = api,
            onCustomizeHome = {
                openHomeEditor = true
                onNavigate(PlayerDestination.Home)
            },
            onUnpair = onUnpair,
        )
    }
}

@Composable
private fun MiniTransportBar(
    item: ActivePlayable,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = SoulSyncColors.PanelSolid,
        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoulSyncArtwork(
                url = item.artworkUrl,
                contentDescription = item.title,
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.dpadFocusable()) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = SoulSyncColors.AccentLight,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = SoulSyncColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun MoreSheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SoulSyncColors.AccentLight)
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

private fun iconForTab(tab: BottomTab): ImageVector = when (tab) {
    BottomTab.HOME -> Icons.Default.Home
    BottomTab.GUIDE -> Icons.Default.LiveTv
    BottomTab.MOVIES -> Icons.Default.Movie
    BottomTab.TV -> Icons.Default.Tv
    BottomTab.MUSIC -> Icons.Default.MusicNote
    BottomTab.MORE -> Icons.Default.Menu
}

private fun tabForDestination(dest: PlayerDestination): BottomTab = when (dest) {
    PlayerDestination.Home -> BottomTab.HOME
    PlayerDestination.Guide -> BottomTab.GUIDE
    PlayerDestination.Movies, is PlayerDestination.MovieDetail -> BottomTab.MOVIES
    PlayerDestination.Tv, is PlayerDestination.ShowDetail -> BottomTab.TV
    PlayerDestination.Music,
    is PlayerDestination.ArtistDetail,
    is PlayerDestination.AlbumDetail,
    is PlayerDestination.PlaylistDetail,
    -> BottomTab.MUSIC
    else -> BottomTab.MORE
}

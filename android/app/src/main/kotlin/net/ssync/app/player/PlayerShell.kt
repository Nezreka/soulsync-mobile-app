package net.ssync.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import net.ssync.app.R
import net.ssync.network.CertPinningOkHttp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
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
    val api = remember(record, pinnedClient) {
        PlayerApiClient(
            baseUrl = record.lastWorkingBaseUrl,
            pinnedAuthenticatedClient = pinnedClient,
        )
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
    var watchingOthersCount by remember { mutableIntStateOf(0) }

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

    // Periodic audio progress sync (1s) and household presence count
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

    CompositionLocalProvider(LocalSoulSyncImageLoader provides imageLoader) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = SoulSyncColors.BgPrimary,
                topBar = {
                    Surface(
                        color = SoulSyncColors.Panel,
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (backStack.size > 1) {
                                IconButton(onClick = { popBack() }, modifier = Modifier.dpadFocusable()) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = SoulSyncColors.TextPrimary,
                                    )
                                }
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.soulsync_logo),
                                    contentDescription = "SoulSync",
                                    modifier = Modifier.size(34.dp).padding(start = 4.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.serverName ?: "SoulSync",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${record.profileName} • Direct",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(
                                onClick = { navigateTo(PlayerDestination.Search) },
                                modifier = Modifier.dpadFocusable(),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = SoulSyncColors.TextSoft)
                            }
                            IconButton(
                                onClick = { navigateTo(PlayerDestination.Settings) },
                                modifier = Modifier.dpadFocusable(),
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = SoulSyncColors.TextSoft)
                            }
                        }
                    }
                },
                bottomBar = {
                    Column {
                        // Mini Transport Bar when audio is active
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

                        NavigationBar(
                            containerColor = SoulSyncColors.Panel,
                            contentColor = SoulSyncColors.TextPrimary,
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
                                                Icon(iconForTab(tab), contentDescription = tab.label)
                                            }
                                        } else {
                                            Icon(iconForTab(tab), contentDescription = tab.label)
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
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    when (val dest = current) {
                        PlayerDestination.Home -> HomeScreen(
                            api = api,
                            coordinator = coordinator,
                            onNavigate = { navigateTo(it) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Guide -> GuideScreen(
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Movies -> MoviesScreen(
                            api = api,
                            onMovieClick = { navigateTo(PlayerDestination.MovieDetail(it)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.MovieDetail -> MovieDetailScreen(
                            movieId = dest.movieId,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Tv -> TvScreen(
                            api = api,
                            onShowClick = { navigateTo(PlayerDestination.ShowDetail(it)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.ShowDetail -> ShowDetailScreen(
                            showId = dest.showId,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Music -> MusicScreen(
                            api = api,
                            coordinator = coordinator,
                            onNavigate = { navigateTo(it) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.ArtistDetail -> ArtistDetailScreen(
                            artistId = dest.artistId,
                            api = api,
                            onAlbumClick = { navigateTo(PlayerDestination.AlbumDetail(it)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.AlbumDetail -> AlbumDetailScreen(
                            albumId = dest.albumId,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.PlaylistDetail -> PlaylistDetailScreen(
                            playlistId = dest.playlistId,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.YouTube -> YouTubeScreen(
                            api = api,
                            coordinator = coordinator,
                            onChannelClick = { id, title -> navigateTo(PlayerDestination.YouTubeChannel(id, title)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.YouTubeChannel -> YouTubeChannelScreen(
                            channelId = dest.channelId,
                            fallbackTitle = dest.title,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Podcasts -> PodcastsScreen(
                            api = api,
                            onPodcastClick = { navigateTo(PlayerDestination.PodcastDetail(it)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.PodcastDetail -> PodcastDetailScreen(
                            sub = dest.sub,
                            api = api,
                            coordinator = coordinator,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Audiobooks -> AudiobooksScreen(
                            api = api,
                            onBookClick = { navigateTo(PlayerDestination.AudiobookDetail(it)) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        is PlayerDestination.AudiobookDetail -> AudiobookDetailScreen(
                            book = dest.book,
                            api = api,
                            coordinator = coordinator,
                        )
                        PlayerDestination.Watchlist -> WatchlistScreen(
                            api = api,
                            onNavigate = { navigateTo(it) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.WatchingNow -> WatchingNowScreen(
                            api = api,
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Search -> SearchScreen(
                            api = api,
                            coordinator = coordinator,
                            onNavigate = { navigateTo(it) },
                            onDeviceRevoked = onDeviceRevoked,
                        )
                        PlayerDestination.Settings -> SettingsScreen(
                            record = record,
                            api = api,
                            onUnpair = onUnpair,
                        )
                    }
                }
            }

            // More Sheet (Watchlist, Watching Now, YouTube, Podcasts, Audiobooks, Settings)
            if (showMoreSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showMoreSheet = false },
                    containerColor = SoulSyncColors.Card,
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

            // Full-screen Audio Now Playing Sheet
            if (coordinator.showAudioSheet) {
                AudioNowPlayingSheet(
                    coordinator = coordinator,
                    onDismiss = { coordinator.showAudioSheet = false },
                )
            }

            // Full-screen Video Player Overlay
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
private fun MiniTransportBar(
    item: ActivePlayable,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = SoulSyncColors.CardRaised,
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

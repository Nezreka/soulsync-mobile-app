package net.ssync.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.app.R
import net.ssync.connect.CandidateAddress
import net.ssync.connect.CandidateScope
import net.ssync.network.CandidateAddressRacer
import net.ssync.network.CertPinningOkHttp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.DeviceProfileInfo
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
    onSettingsChanged: (net.ssync.player.PlayerSettings) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentRecord by remember(record) { mutableStateOf(record) }
    var profileEpoch by remember { mutableIntStateOf(0) }

    val pinnedClient = remember(currentRecord.certFingerprintSha256, currentRecord.deviceToken) {
        CertPinningOkHttp.createPinnedClient(
            expectedCertFingerprintSha256 = currentRecord.certFingerprintSha256,
            deviceTokenProvider = { currentRecord.deviceToken },
            onUnauthorized = onDeviceRevoked,
        )
    }
    var activeBaseUrl by remember(currentRecord.lastWorkingBaseUrl) {
        mutableStateOf(currentRecord.lastWorkingBaseUrl)
    }
    val api = remember(activeBaseUrl, pinnedClient, profileEpoch) {
        PlayerApiClient(
            baseUrl = activeBaseUrl,
            pinnedAuthenticatedClient = pinnedClient,
        )
    }

    var networkEpoch by remember { mutableIntStateOf(0) }

    // Listen for Wi-Fi <-> 5G/LTE network transitions so we automatically re-race LAN/WAN candidates
    DisposableEffect(context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkEpoch += 1
            }

            override fun onLost(network: Network) {
                networkEpoch += 1
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    // Initialize Offline Downloads manager, sync any offline deletions, and check admin role
    var isAdminUser by remember { mutableStateOf(false) }
    LaunchedEffect(api, context) {
        OfflineDownloadManager.init(context)
        OfflineDownloadManager.syncPendingRemovals(api)
        val dlMeta = (api.fetchMyDownloads(includeRemoved = false) as? MusicApiResult.Success)?.data
        if (dlMeta != null) isAdminUser = dlMeta.isAdmin
    }

    // Refresh live LAN + WAN candidate addresses from /api/devices/me (picks up UPnP port changes without re-pairing)
    LaunchedEffect(api, currentRecord.lookupId) {
        val urls = (api.fetchDeviceAddresses() as? MusicApiResult.Success)?.data.orEmpty()
        if (urls.isNotEmpty()) {
            val fresh = urls.map { url ->
                val host = url.removePrefix("https://").substringBefore("/").substringBefore(":")
                val firstLabel = host.substringBefore(".")
                val isLan = firstLabel.startsWith("10-") ||
                    firstLabel.startsWith("192-168-") ||
                    (firstLabel.startsWith("172-") && (firstLabel.split("-").getOrNull(1)?.toIntOrNull() in 16..31))
                CandidateAddress(url = url, scope = if (isLan) CandidateScope.LAN else CandidateScope.WAN)
            }
            if (fresh != currentRecord.candidateAddresses) {
                val updated = currentRecord.copy(candidateAddresses = fresh)
                currentRecord = updated
                KeystoreServerVault(context.applicationContext).save(updated)
            }
        }
    }

    // Seamless Wi-Fi <-> 5G LAN/WAN failover: race cached candidate addresses on start & network change
    LaunchedEffect(currentRecord.lookupId, pinnedClient, networkEpoch) {
        if (currentRecord.candidateAddresses.size > 1) {
            val best = CandidateAddressRacer(pinnedClient).selectBestBaseUrl(currentRecord.candidateAddresses)
            if (best != null && best != activeBaseUrl) {
                activeBaseUrl = best
                val updated = currentRecord.copy(lastWorkingBaseUrl = best)
                currentRecord = updated
                KeystoreServerVault(context.applicationContext).save(updated)
            }
        }
    }
    val imageLoader = remember(context, activeBaseUrl, pinnedClient) {
        createPinnedImageLoader(context.applicationContext, activeBaseUrl, pinnedClient)
    }
    var showCastSheet by remember { mutableStateOf(false) }
    val castController = rememberCastController()
    val latestMediaController by rememberUpdatedState(mediaController)
    val latestBaseUrl by rememberUpdatedState(activeBaseUrl)
    val latestApi by rememberUpdatedState(api)
    // one coordinator for the session: a network switch or a new api client
    // used to rebuild it, and the open video and mini bar vanished mid-play
    val coordinator = remember(castController) {
        PlaybackCoordinator(
            api = api,
            scope = scope,
            controllerProvider = { latestMediaController },
            castHandler = { item, upcoming ->
                if (castController.isConnected) {
                    val ok = castController.castItem(latestApi, latestBaseUrl, item, item.resumePositionMs, upcoming)
                    if (ok && !castController.isCastingMedia) showCastSheet = true
                    ok
                } else {
                    false
                }
            },
        )
    }
    SideEffect { coordinator.api = api }

    // Keep Chromecast Live Guide & queued playback advancing continuously even when the Cast sheet is closed
    LaunchedEffect(castController, coordinator) {
        castController.onMediaFinished = { coordinator.playNextVideo() }
        castController.onQueueItemChanged = { title, key -> coordinator.syncCastAdvancedTo(title, key) }
    }
    LaunchedEffect(castController.isConnected) {
        while (castController.isConnected) {
            castController.refreshRemoteState()
            delay(1_500L)
        }
    }

    val isTv = LocalIsTv.current
    var backStack by remember { mutableStateOf<List<PlayerDestination>>(listOf(PlayerDestination.Home)) }
    val current = backStack.last()
    var showProfileSheet by remember { mutableStateOf(false) }
    var openHomeEditor by remember { mutableStateOf(false) }
    // On TV start with a sleek collapsed icon rail (auto-expands on D-pad focus) and allow hiding it completely
    var sidebarCollapsed by remember(isTv) { mutableStateOf(isTv) }
    var tvSidebarHidden by remember(isTv) { mutableStateOf(false) }
    var sidebarHasFocus by remember { mutableStateOf(false) }
    var tvSidebarForcedOpen by remember { mutableStateOf(false) }
    val sidebarFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    var headerScrolled by remember { mutableStateOf(false) }
    var homeHasHero by remember { mutableStateOf(false) }
    var watchingOthersCount by remember { mutableIntStateOf(0) }
    var activeAvatarUrl by remember { mutableStateOf<String?>(null) }
    var contextMenuItem by remember { mutableStateOf<MediaContextItem?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    LaunchedEffect(tvSidebarForcedOpen) {
        if (tvSidebarForcedOpen) {
            delay(40L)
            runCatching { sidebarFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(api, currentRecord.profileId, profileEpoch) {
        val fallback = api.artwork.profileAvatar(currentRecord.profileId)
        val activeProf = (api.fetchActiveDeviceProfile() as? MusicApiResult.Success)?.data
        val fetched = activeProf?.avatarUrl
            ?: (api.fetchDeviceProfiles() as? MusicApiResult.Success)?.data
                ?.firstOrNull { it.id.toString() == currentRecord.profileId }
                ?.avatarUrl
        activeAvatarUrl = api.artwork.resolve(fetched) ?: fallback
    }

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

    var showExitConfirm by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val activityContext = androidx.compose.ui.platform.LocalContext.current

    // Guarantee TV sidebar collapses immediately whenever destination changes
    LaunchedEffect(current) {
        if (isTv) {
            tvSidebarForcedOpen = false
            sidebarHasFocus = false
        }
    }

    BackHandler(
        enabled = showExitConfirm ||
            drawerState.isOpen ||
            (coordinator.showVideoOverlay && !isTv) ||
            coordinator.showAudioSheet ||
            (isTv && (sidebarHasFocus || tvSidebarForcedOpen)) ||
            backStack.size > 1 ||
            (isTv && !coordinator.showVideoOverlay),
    ) {
        when {
            showExitConfirm -> showExitConfirm = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
            coordinator.showVideoOverlay && !isTv -> coordinator.minimizeVideo(coordinator.positionMs, coordinator.durationMs)
            coordinator.showAudioSheet -> coordinator.showAudioSheet = false
            isTv && (sidebarHasFocus || tvSidebarForcedOpen) -> {
                // Pressing Back while on the TV sidebar prompts to verify exiting the app
                showExitConfirm = true
            }
            backStack.size > 1 -> popBack()
            isTv -> {
                // Standard Android TV root Back: focus & glide open the navigation sidebar
                tvSidebarForcedOpen = true
                runCatching { sidebarFocusRequester.requestFocus() }
            }
        }
    }

    // Real-time MediaController listener + ON_RESUME rehydration so song transitions,
    // car Bluetooth AVRCP skips, background album playback, and Activity recreates NEVER desync UI!
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(mediaController, coordinator, lifecycleOwner) {
        val controller = mediaController
        val playerListener = if (controller != null) {
            object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    coordinator.syncAudioProgress()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    coordinator.syncAudioProgress()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    coordinator.syncAudioProgress()
                }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    coordinator.rehydrateFromController()
                }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    coordinator.syncAudioProgress()
                }
                override fun onRepeatModeChanged(repeatMode: Int) {
                    coordinator.syncAudioProgress()
                }
            }.also {
                controller.addListener(it)
                coordinator.rehydrateFromController()
            }
        } else {
            null
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coordinator.rehydrateFromController()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            if (controller != null && playerListener != null) {
                controller.removeListener(playerListener)
            }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(coordinator, mediaController, coordinator.activeItem, coordinator.showAudioSheet) {
        val active = coordinator.activeItem
        if (active != null && !active.isVideo) {
            val tickMs = if (coordinator.showAudioSheet) 250L else 1_000L
            while (true) {
                coordinator.syncAudioProgress()
                delay(tickMs)
            }
        }
    }
    var playerSettings by remember { mutableStateOf(net.ssync.player.PlayerSettings()) }

    LaunchedEffect(playerSettings.accentColor) {
        SoulSyncColors.applyAccent(playerSettings.accentColor)
    }

    LaunchedEffect(api) {
        (api.fetchPresence() as? MusicApiResult.Success)?.let { p ->
            watchingOthersCount = p.data.sessions.count { !it.you }
        }
        (api.fetchSettings() as? MusicApiResult.Success)?.let { s ->
            playerSettings = s.data
            PlayerNavPrefs.order.value = s.data.sidebarOrder
            PlayerNavPrefs.hidden.value = s.data.sidebarHidden.toSet()
        }
    }

    val hasFullBleedHero = (current is PlayerDestination.Home && homeHasHero) ||
        current is PlayerDestination.MovieDetail ||
        current is PlayerDestination.ShowDetail ||
        current is PlayerDestination.EpisodeDetail

    CompositionLocalProvider(
        LocalSoulSyncImageLoader provides imageLoader,
        LocalPlayerSettings provides playerSettings,
        LocalMediaContextMenu provides { contextMenuItem = it },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(SoulSyncColors.BgPrimary),
        ) {
            val usePermanentSidebar = maxWidth >= 700.dp
            val tvIsSpotlightHome = isTv && (
                current is PlayerDestination.Home ||
                    current is PlayerDestination.Calendar ||
                    current is PlayerDestination.Movies ||
                    current is PlayerDestination.Tv ||
                    current is PlayerDestination.Library ||
                    current is PlayerDestination.MovieDetail ||
                    current is PlayerDestination.ShowDetail ||
                    current is PlayerDestination.CollectionDetail ||
                    current is PlayerDestination.Music ||
                    current is PlayerDestination.ArtistDetail ||
                    current is PlayerDestination.AlbumDetail ||
                    current is PlayerDestination.Playlists ||
                    current is PlayerDestination.YouTube ||
                    current is PlayerDestination.Podcasts ||
                    current is PlayerDestination.Audiobooks ||
                    current is PlayerDestination.Watchlist ||
                    current is PlayerDestination.WatchingNow ||
                    current is PlayerDestination.Settings ||
                    current is PlayerDestination.Downloads ||
                    current is PlayerDestination.AdminUsers
                )
            val tvSidebarExpanded = isTv && (sidebarHasFocus || tvSidebarForcedOpen)
            val tvShowSidebarRail = isTv && (!tvSidebarHidden || tvSidebarExpanded)
            val tvContentStartPadding by animateDpAsState(
                targetValue = if (isTv && !tvSidebarHidden && !tvIsSpotlightHome) 68.dp else 0.dp,
                label = "tvContentStartPad",
            )

            val mainScaffold: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SoulSyncColors.BgPrimary)
                        .navigationBarsPadding(),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (usePermanentSidebar && !isTv) {
                                PlayerSidebar(
                                    current = current,
                                    collapsed = sidebarCollapsed,
                                    watchingCount = watchingOthersCount,
                                    isAdmin = isAdminUser,
                                    showCollapseToggle = true,
                                    collapseLabel = if (sidebarCollapsed) "Expand" else "Collapse",
                                    onNavigate = { navigateTo(it, clearToRoot = true) },
                                    onToggleCollapse = { sidebarCollapsed = !sidebarCollapsed },
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(start = tvContentStartPadding)
                                    .focusRequester(contentFocusRequester),
                            ) {
                                val minimizedTitle = coordinator.activeItem
                                    ?.takeIf { it.isVideo && coordinator.isVideoMinimized }
                                    ?.title
                                if (!tvIsSpotlightHome) {
                                    // Keep FloatingTopHeader first in focus order (with zIndex(3f) so it floats visually on top)
                                    // so pressing D-pad Down / Next from Search or Profile always enters PlayerContent cleanly.
                                    FloatingTopHeader(
                                        record = currentRecord,
                                        avatarUrl = activeAvatarUrl ?: api.artwork.profileAvatar(currentRecord.profileId),
                                        showBack = backStack.size > 1,
                                        showMenuButton = !usePermanentSidebar || (isTv && tvSidebarHidden),
                                        showCastButton = !isTv,
                                        minimizedVideoTitle = minimizedTitle,
                                        tvMiniAudioSlot = if (isTv && !castController.isCastingMedia && coordinator.activeItem != null && !coordinator.activeItem!!.isVideo) {
                                            {
                                                coordinator.activeItem?.let { audioItem ->
                                                    TvTopRightMiniPlayer(
                                                        coordinator = coordinator,
                                                        item = audioItem,
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        transparentOverHero = hasFullBleedHero && !isTv && !headerScrolled,
                                        castConnected = castController.isConnected,
                                        onBack = { popBack() },
                                        onOpenDrawer = {
                                            if (isTv) {
                                                tvSidebarForcedOpen = true
                                                runCatching { sidebarFocusRequester.requestFocus() }
                                            } else {
                                                scope.launch { drawerState.open() }
                                            }
                                        },
                                        onExpandMinimizedVideo = { coordinator.expandVideo() },
                                        onCastClick = { showCastSheet = true },
                                        onSearch = { navigateTo(PlayerDestination.Search) },
                                        onProfileClick = { showProfileSheet = true },
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (tvIsSpotlightHome || (hasFullBleedHero && !isTv)) Modifier
                                            else Modifier.statusBarsPadding().padding(top = if (isTv) 56.dp else 60.dp),
                                        ),
                                ) {
                                    key(profileEpoch) {
                                        AnimatedContent(
                                            targetState = current,
                                            transitionSpec = {
                                                (fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.985f))
                                                    .togetherWith(fadeOut(tween(140)))
                                            },
                                            label = "playerPageTransition",
                                        ) { targetDest ->
                                            PlayerContent(
                                                current = targetDest,
                                                record = currentRecord,
                                                api = api,
                                                pinnedHttpClient = pinnedClient,
                                                coordinator = coordinator,
                                                openHomeEditor = openHomeEditor,
                                                onConsumeHomeEditor = { openHomeEditor = false },
                                                onCustomizeHome = {
                                                    openHomeEditor = true
                                                    navigateTo(PlayerDestination.Home, clearToRoot = true)
                                                },
                                                onNavigate = { navigateTo(it) },
                                                onScrolledChanged = { headerScrolled = it },
                                                onHasHeroChanged = { homeHasHero = it },
                                                onDeviceRevoked = onDeviceRevoked,
                                                onUnpair = onUnpair,
                                                onSettingsChanged = {
                                                    playerSettings = it
                                                    PlayerNavPrefs.order.value = it.sidebarOrder
                                                    PlayerNavPrefs.hidden.value = it.sidebarHidden.toSet()
                                                },
                                            )
                                        }
                                    }
                                    // the full-screen tv pages have no shell header. home, calendar, the
                                    // movies / tv hubs and the title pages draw the mini player in their
                                    // own top bar; every other one (music, an album, playlists, youtube,
                                    // podcasts, audiobooks, watchlist, settings...) drew none, so music
                                    // started from them played with nothing on screen to show or stop it.
                                    val tvAudio = coordinator.activeItem?.takeIf { !it.isVideo }
                                    val drawsItsOwn = current is PlayerDestination.Home ||
                                        current is PlayerDestination.Calendar ||
                                        current is PlayerDestination.Movies ||
                                        current is PlayerDestination.Tv ||
                                        current is PlayerDestination.Library ||
                                        current is PlayerDestination.MovieDetail ||
                                        current is PlayerDestination.ShowDetail
                                    if (isTv && tvIsSpotlightHome && !drawsItsOwn && tvAudio != null &&
                                        !castController.isCastingMedia
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 16.dp, end = 24.dp)
                                                .zIndex(4f),
                                        ) {
                                            TvTopRightMiniPlayer(coordinator = coordinator, item = tvAudio)
                                        }
                                    }
                                }
                            }
                        }

                        // TV overlay auto-expanding rail (glides smoothly over content without layout reflow)
                        if (isTv) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = tvSidebarExpanded,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.52f)),
                                )
                            }

                            if (tvShowSidebarRail) Box(
                                // Right leaves the open sidebar for the page. focus search
                                // alone found nothing to the right of it (the page sits under
                                // the sidebar's dimmed overlay), so the only way out was to
                                // pick a place again.
                                modifier = Modifier.onPreviewKeyEvent { ev ->
                                    if (tvSidebarExpanded &&
                                        ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                        ev.key == androidx.compose.ui.input.key.Key.DirectionRight
                                    ) {
                                        tvSidebarForcedOpen = false
                                        sidebarHasFocus = false
                                        focusManager.clearFocus(force = true)
                                        scope.launch {
                                            delay(60L)
                                            runCatching { contentFocusRequester.requestFocus() }
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            ) {
                                PlayerSidebar(
                                    current = current,
                                    collapsed = !tvSidebarExpanded,
                                    watchingCount = watchingOthersCount,
                                    isAdmin = isAdminUser,
                                    showCollapseToggle = true,
                                    collapseLabel = if (tvSidebarHidden) "Pin Sidebar Rail" else "Hide Sidebar",
                                    isOverlay = tvSidebarExpanded,
                                    profileName = currentRecord.profileName,
                                    avatarUrl = activeAvatarUrl ?: api.artwork.profileAvatar(currentRecord.profileId),
                                    onProfileClick = {
                                        tvSidebarForcedOpen = false
                                        sidebarHasFocus = false
                                        focusManager.clearFocus(force = true)
                                        showProfileSheet = true
                                    },
                                    firstItemFocusRequester = sidebarFocusRequester,
                                    onFocusStateChanged = { hasFocus ->
                                        sidebarHasFocus = hasFocus
                                        if (!hasFocus) tvSidebarForcedOpen = false
                                    },
                                    onNavigate = { dest ->
                                        tvSidebarForcedOpen = false
                                        sidebarHasFocus = false
                                        focusManager.clearFocus(force = true)
                                        navigateTo(dest, clearToRoot = true)
                                        scope.launch {
                                            delay(90L)
                                            runCatching { contentFocusRequester.requestFocus() }
                                            delay(200L)
                                            if (!sidebarHasFocus) {
                                                runCatching { contentFocusRequester.requestFocus() }
                                            }
                                        }
                                    },
                                    onToggleCollapse = {
                                        tvSidebarHidden = !tvSidebarHidden
                                        tvSidebarForcedOpen = false
                                        sidebarHasFocus = false
                                        focusManager.clearFocus(force = true)
                                        scope.launch {
                                            delay(60L)
                                            runCatching { contentFocusRequester.requestFocus() }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // Persistent Live Cast Mini-Bar (when casting to TV)
                    if (castController.isCastingMedia) {
                        CastMiniBar(
                            castController = castController,
                            onExpand = { showCastSheet = true },
                        )
                    }

                    // Persistent Mini Transport Bar on Phone/Tablet (on TV, docked as a sleek top-right pill next to Profile)
                    val active = coordinator.activeItem
                    if (!isTv && !castController.isCastingMedia && active != null && !active.isVideo) {
                        MiniTransportBarHost(
                            coordinator = coordinator,
                            item = active,
                        )
                    }
                }
            }

            if (usePermanentSidebar) {
                mainScaffold()
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    scrimColor = Color.Black.copy(alpha = 0.68f),
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = SoulSyncColors.BgPrimary,
                            drawerContentColor = SoulSyncColors.TextPrimary,
                            modifier = Modifier.width(272.dp),
                        ) {
                            PlayerSidebar(
                                current = current,
                                collapsed = false,
                                watchingCount = watchingOthersCount,
                                isAdmin = isAdminUser,
                                showCollapseToggle = false,
                                onNavigate = { dest ->
                                    scope.launch { drawerState.close() }
                                    navigateTo(dest, clearToRoot = true)
                                },
                                onToggleCollapse = {},
                            )
                        }
                    },
                    content = mainScaffold,
                )
            }

            if (showExitConfirm) {
                TvCinemaExitConfirmDialog(
                    onStay = { showExitConfirm = false },
                    onExit = {
                        showExitConfirm = false
                        (activityContext as? android.app.Activity)?.finish()
                    },
                )
            }

            if (showProfileSheet) {
                ProfileMenuSheet(
                    record = currentRecord,
                    activeAvatarUrl = activeAvatarUrl,
                    api = api,
                    onProfileSwitched = { chosen ->
                        scope.launch {
                            val updated = currentRecord.copy(
                                profileId = chosen.id.toString(),
                                profileName = chosen.name,
                            )
                            currentRecord = updated
                            activeAvatarUrl = api.artwork.resolve(chosen.avatarUrl)
                                ?: api.artwork.profileAvatar(chosen.id.toString())
                            KeystoreServerVault(context.applicationContext).save(updated)
                            profileEpoch += 1
                            showProfileSheet = false
                            navigateTo(PlayerDestination.Home, clearToRoot = true)
                        }
                    },
                    onOpenSettings = {
                        showProfileSheet = false
                        navigateTo(PlayerDestination.Settings)
                    },
                    onCustomizeHome = {
                        showProfileSheet = false
                        openHomeEditor = true
                        navigateTo(PlayerDestination.Home, clearToRoot = true)
                    },
                    onUnpair = {
                        showProfileSheet = false
                        onUnpair()
                    },
                    onDismiss = { showProfileSheet = false },
                )
            }

            if (showCastSheet) {
                CastDeviceSheet(
                    castController = castController,
                    api = api,
                    baseUrl = activeBaseUrl,
                    activeItem = coordinator.activeItem,
                    currentPositionMs = coordinator.positionMs,
                    upcoming = coordinator.videoQueue.drop(1),
                    onPlayNext = { coordinator.playNextVideo() },
                    onCastStarted = {
                        if (coordinator.showVideoOverlay || coordinator.isVideoMinimized) {
                            coordinator.hideLocalVideoForCast()
                        } else if (coordinator.isPlaying) {
                            coordinator.togglePlayPause()
                        }
                    },
                    onDismiss = { showCastSheet = false },
                )
            }

            AnimatedVisibility(
                visible = coordinator.showAudioSheet,
                enter = fadeIn(tween(240)) + scaleIn(tween(260), initialScale = 0.97f),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize(),
            ) {
                AudioNowPlayingSheet(
                    coordinator = coordinator,
                    api = api,
                    castConnected = castController.isConnected,
                    onOpenCast = { showCastSheet = true },
                    onDismiss = { coordinator.showAudioSheet = false },
                )
            }

            val videoItem = coordinator.activeItem
            val showVideo = videoItem != null && videoItem.isVideo && (coordinator.showVideoOverlay || coordinator.isVideoMinimized)
            AnimatedVisibility(
                visible = showVideo,
                enter = fadeIn(tween(260)) + scaleIn(tween(280), initialScale = 0.96f),
                exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (videoItem != null && videoItem.isVideo) {
                    key(videoItem.streamUrl, videoItem.itemId) {
                        VideoPlayerOverlay(
                            item = videoItem,
                            api = api,
                            pinnedHttpClient = pinnedClient,
                            minimized = coordinator.isVideoMinimized,
                            castConnected = castController.isConnected,
                            onOpenCast = { showCastSheet = true },
                            onOpenChannel = { channelId ->
                                coordinator.minimizeVideo(coordinator.positionMs, coordinator.durationMs)
                                navigateTo(PlayerDestination.YouTubeChannel(channelId, videoItem.subtitle.ifBlank { "Channel" }))
                            },
                            onMinimize = { pos, dur -> coordinator.minimizeVideo(pos, dur) },
                            onExpand = { coordinator.expandVideo() },
                            onLeaveParty = { coordinator.detachWatchParty() },
                            nextVideoTitle = coordinator.videoQueue.getOrNull(1)?.title,
                            onVideoEnded = { coordinator.playNextVideo() },
                            onSurfChannel = if (videoItem.isLiveGuide && coordinator.liveChannelSurfer != null) {
                                { delta -> coordinator.surfLiveChannel(delta) }
                            } else {
                                null
                            },
                            onClose = { pos, dur -> coordinator.closeVideo(videoItem, pos, dur) },
                        )
                    }
                }
            }

            contextMenuItem?.let { item ->
                MediaContextMenuSheet(
                    item = item,
                    api = api,
                    coordinator = coordinator,
                    onNavigate = { navigateTo(it) },
                    onDismiss = { contextMenuItem = null },
                )
            }
        }
    }
}

@Composable
private fun ProfileAvatarCircle(
    avatarUrl: String?,
    name: String,
    size: Dp,
    fontSize: TextUnit,
    containerColor: Color = SoulSyncColors.Accent,
    initialColor: Color = Color.Black,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S",
            color = initialColor,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
        )
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(150)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

@Composable
private fun FloatingTopHeader(
    record: PairedServerRecord,
    avatarUrl: String?,
    showBack: Boolean,
    showMenuButton: Boolean,
    showCastButton: Boolean = true,
    minimizedVideoTitle: String? = null,
    tvMiniAudioSlot: (@Composable () -> Unit)? = null,
    transparentOverHero: Boolean,
    castConnected: Boolean,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onExpandMinimizedVideo: () -> Unit = {},
    onCastClick: () -> Unit,
    onSearch: () -> Unit,
    onProfileClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    val bgModifier = if (transparentOverHero) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(SoulSyncColors.BgPrimary.copy(alpha = if (isTv) 0.48f else 0.82f), Color.Transparent),
            ),
        )
    } else {
        Modifier.background(SoulSyncColors.BgPrimary.copy(alpha = 0.94f))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(3f)
            .then(bgModifier)
            .onPreviewKeyEvent { ev ->
                if (isTv && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    // Always guarantee D-pad Down from Search / Profile drops smoothly back into main page content
                    if (!focusManager.moveFocus(FocusDirection.Down)) {
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(58.dp)
                .padding(horizontal = 12.dp),
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
                Spacer(modifier = Modifier.width(8.dp))
            } else if (showMenuButton) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onOpenDrawer)
                        .dpadFocusable(RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open menu",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val logoBitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id = R.drawable.soulsync_mark)
                    Image(
                        bitmap = logoBitmap,
                        contentDescription = "SoulSync",
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Sleek Search pill right beside the logo mark
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(SoulSyncColors.PanelGlass)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onSearch)
                    .dpadFocusable(RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = SoulSyncColors.TextSoft,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = "Search",
                    color = SoulSyncColors.TextSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!minimizedVideoTitle.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SoulSyncColors.Accent.copy(alpha = 0.24f))
                        .border(1.dp, SoulSyncColors.AccentLight, RoundedCornerShape(999.dp))
                        .dpadFocusable(RoundedCornerShape(999.dp))
                        .clickable(onClick = onExpandMinimizedVideo)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Resume video",
                        tint = SoulSyncColors.AccentLight,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Resume: $minimizedVideoTitle",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(140.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (tvMiniAudioSlot != null) {
                tvMiniAudioSlot()
                Spacer(modifier = Modifier.width(10.dp))
            }

            if (showCastButton) {
                IconButton(
                    onClick = onCastClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (castConnected) SoulSyncColors.Accent.copy(alpha = 0.22f) else SoulSyncColors.PanelGlass)
                        .border(
                            1.dp,
                            if (castConnected) SoulSyncColors.Accent else Color.White.copy(alpha = 0.12f),
                            CircleShape,
                        )
                        .dpadFocusable(CircleShape),
                ) {
                    Icon(
                        imageVector = if (castConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                        contentDescription = "Cast",
                        tint = if (castConnected) SoulSyncColors.AccentLight else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
            }

            // Clean circular Profile avatar button -> opens Profile & Household Switcher Sheet
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, SoulSyncColors.LineStrong, CircleShape)
                    .clickable(onClick = onProfileClick)
                    .dpadFocusable(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ProfileAvatarCircle(
                    avatarUrl = avatarUrl,
                    name = record.profileName,
                    size = 34.dp,
                    fontSize = 14.sp,
                )
            }
        }
        if (!transparentOverHero) {
            HorizontalDivider(color = SoulSyncColors.Line, thickness = 1.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileMenuSheet(
    record: PairedServerRecord,
    activeAvatarUrl: String?,
    api: PlayerApiClient,
    onProfileSwitched: (DeviceProfileInfo) -> Unit,
    onOpenSettings: () -> Unit,
    onCustomizeHome: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<DeviceProfileInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingAuthProfile by remember { mutableStateOf<DeviceProfileInfo?>(null) }
    var credentialInput by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        loading = true
        (api.fetchSwitchProfiles() as? MusicApiResult.Success)?.let { profiles = it.data }
        loading = false
    }

    fun submitSwitch(target: DeviceProfileInfo, pin: String? = null, password: String? = null) {
        if (switching) return
        switching = true
        switchError = null
        scope.launch {
            when (val r = api.switchDeviceProfile(target.id, pin = pin, password = password)) {
                is MusicApiResult.Success -> {
                    pendingAuthProfile = null
                    credentialInput = ""
                    onProfileSwitched(r.data)
                }
                is MusicApiResult.Error -> switchError = r.message
                MusicApiResult.DeviceRevoked -> switchError = "Device session revoked."
            }
            switching = false
        }
    }

    AdaptiveTvSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF60C101E),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        listOf(SoulSyncColors.Accent.copy(alpha = 0.14f), Color.Transparent),
                        radius = 650f,
                    ),
                )
                .padding(horizontal = 24.dp)
                .padding(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // AAA Cinema Profile Hero Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                    .padding(14.dp),
            ) {
                val currentProfileAvatar = activeAvatarUrl
                    ?: profiles
                        .firstOrNull { it.id.toString() == record.profileId }
                        ?.avatarUrl
                        ?.let { api.artwork.resolve(it) }
                    ?: api.artwork.profileAvatar(record.profileId)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                        .padding(2.dp),
                ) {
                    ProfileAvatarCircle(
                        avatarUrl = currentProfileAvatar,
                        name = record.profileName,
                        size = 50.dp,
                        fontSize = 19.sp,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = record.profileName,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x2E34D399))
                                .border(0.75.dp, Color(0x6634D399), CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "ACTIVE",
                                color = Color(0xFF6EE7B7),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.7.sp,
                            )
                        }
                    }
                    Text(
                        text = "${record.serverName ?: "SoulSync"} · ${record.formattedFingerprint}",
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Switch profile section
            val others = profiles.filter { it.id.toString() != record.profileId }
            if (others.isNotEmpty()) {
                Text(
                    text = "SWITCH PROFILE",
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.1.sp,
                )
                others.forEach { p ->
                    var pFocused by remember(p.id) { mutableStateOf(false) }
                    val isPending = pendingAuthProfile?.id == p.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    pFocused -> Color.White
                                    isPending -> Color.White.copy(alpha = 0.14f)
                                    else -> Color.White.copy(alpha = 0.05f)
                                },
                            )
                            .border(
                                1.dp,
                                when {
                                    pFocused -> Color.White
                                    isPending -> Color.White.copy(alpha = 0.55f)
                                    else -> Color.White.copy(alpha = 0.10f)
                                },
                                RoundedCornerShape(14.dp),
                            )
                            .onFocusChanged { pFocused = it.isFocused || it.hasFocus }
                            .clickable(enabled = !switching) {
                                switchError = null
                                if (p.needs != null) {
                                    pendingAuthProfile = p
                                    credentialInput = ""
                                } else {
                                    submitSwitch(p)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProfileAvatarCircle(
                            avatarUrl = api.artwork.resolve(p.avatarUrl) ?: api.artwork.profileAvatar(p.id.toString()),
                            name = p.name,
                            size = 38.dp,
                            fontSize = 15.sp,
                            containerColor = Color.White.copy(alpha = 0.14f),
                            initialColor = Color.White,
                        )
                        Text(
                            text = p.name,
                            color = if (pFocused) Color(0xFF0A0C14) else Color.White,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.needs != null) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Requires ${p.needs}",
                                tint = if (pFocused) Color(0xFF475569) else Color.White.copy(alpha = 0.58f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                val authTarget = pendingAuthProfile
                if (authTarget != null) {
                    val isPassword = authTarget.needs == "password"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Enter ${if (isPassword) "password" else "PIN"} for ${authTarget.name}",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedTextField(
                            value = credentialInput,
                            onValueChange = { credentialInput = it },
                            placeholder = { Text(if (isPassword) "Password" else "PIN") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (isPassword) submitSwitch(authTarget, password = credentialInput)
                                else submitSwitch(authTarget, pin = credentialInput)
                            },
                            enabled = !switching && credentialInput.isNotBlank(),
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF0A0C14),
                            ),
                        ) {
                            Text(if (switching) "Switching…" else "Switch to ${authTarget.name}", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                if (switchError != null) {
                    Text(switchError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
            } else if (!loading && profiles.size <= 1) {
                Text(
                    text = "Signed in as the only profile available on this device.",
                    color = Color.White.copy(alpha = 0.54f),
                    fontSize = 12.sp,
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

            ProfileSheetAction(
                icon = Icons.Default.Tune,
                label = "Customize Home",
                onClick = onCustomizeHome,
            )
            ProfileSheetAction(
                icon = Icons.Default.Settings,
                label = "Player Settings",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun TvCinemaExitConfirmDialog(
    onStay: () -> Unit,
    onExit: () -> Unit,
) {
    val stayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { stayFocus.requestFocus() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onStay) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFA141828), Color(0xFA0B0E18))),
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SoulSyncAnimatedBrandMark(size = 46.dp)
            Text(
                text = "Exit SoulSync?",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "Are you sure you want to close the app?",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 13.5.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var stayFocused by remember { mutableStateOf(false) }
                var exitFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (stayFocused) Color.White else Color.White.copy(alpha = 0.14f))
                        .border(1.2.dp, if (stayFocused) Color.White else Color.White.copy(alpha = 0.28f), CircleShape)
                        .focusRequester(stayFocus)
                        .onFocusChanged { stayFocused = it.isFocused || it.hasFocus }
                        .clickable(onClick = onStay)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Stay",
                        color = if (stayFocused) Color(0xFF0A0C14) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (exitFocused) Color(0xFFEF4444) else Color(0x28EF4444))
                        .border(1.2.dp, if (exitFocused) Color(0xFFFCA5A5) else Color(0x66EF4444), CircleShape)
                        .onFocusChanged { exitFocused = it.isFocused || it.hasFocus }
                        .clickable(onClick = onExit)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Exit App",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color(0xFF0A0C14) else Color.White.copy(alpha = 0.80f),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = if (focused) Color(0xFF0A0C14) else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (focused) Color(0xFF475569) else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SoulSyncAnimatedBrandMark(
    size: Dp,
) {
    // on a TV the mark stands still. an animation that never ends makes the
    // whole screen draw again sixty times a second for as long as the app is
    // open (366 frames in six idle seconds on a Shield, each costing the GPU
    // 12 ms of its 16), so there was nothing left over when you pressed a key.
    if (LocalIsTv.current) {
        // its own picture, made at about twice the size it is drawn at: the 512 px mark squeezed
        // into 56 px on screen came out jagged
        Image(
            bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id = R.drawable.soulsync_mark_tv),
            contentDescription = "SoulSync",
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = Modifier.padding(4.dp).size(size),
        )
        return
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "sidebarBrandAnim")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "brandBreathe",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "brandAuraDrift",
    )
    val logoBitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id = R.drawable.soulsync_mark)
    Box(
        modifier = Modifier.size(size + 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft dual-lobe chromatic aura echoing the SoulSync cinema boot animation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        0.0f to SoulSyncColors.AccentLight.copy(alpha = 0.24f + breathe * 0.14f),
                        0.55f to Color(0xFF38BDF8).copy(alpha = 0.10f + drift * 0.08f),
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Image(
            bitmap = logoBitmap,
            contentDescription = "SoulSync",
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = 0.96f + breathe * 0.07f
                    scaleX = s
                    scaleY = s
                    rotationZ = (drift - 0.5f) * 2.4f
                },
        )
    }
}

/** the profile's own sidebar order and hidden places (player settings), observable by the sidebar */
internal object PlayerNavPrefs {
    val order = mutableStateOf<List<String>>(emptyList())
    val hidden = mutableStateOf<Set<String>>(emptySet())
}

/** one place in a sidebar section, by the id the web player uses for it */
private class SidebarPlace(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val open: () -> Unit,
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerSidebar(
    current: PlayerDestination,
    collapsed: Boolean,
    watchingCount: Int,
    isAdmin: Boolean,
    showCollapseToggle: Boolean,
    collapseLabel: String = "Collapse",
    isOverlay: Boolean = false,
    profileName: String? = null,
    avatarUrl: String? = null,
    onProfileClick: (() -> Unit)? = null,
    firstItemFocusRequester: FocusRequester? = null,
    onFocusStateChanged: ((Boolean) -> Unit)? = null,
    onNavigate: (PlayerDestination) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val sidebarScrollState = rememberScrollState()
    val activeItemFocusRequester = remember { FocusRequester() }
    var wasSidebarFocused by remember { mutableStateOf(false) }

    val isHomeSel = current is PlayerDestination.Home
    val isSearchSel = current is PlayerDestination.Search || current is PlayerDestination.SearchQuery
    LaunchedEffect(isHomeSel, wasSidebarFocused, collapsed) {
        if (isTv && !collapsed && isHomeSel && wasSidebarFocused) {
            sidebarScrollState.animateScrollTo(0)
        }
    }

    // When TV collapsed, render Plex-style floating minimal glass pill centered on the left edge!
    // Pressing Left onto it immediately expands into the floating rounded drawer & focuses the active page.
    if (isTv && collapsed) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier
                    .width(46.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xC40A0C16))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .onFocusChanged { state ->
                        if (state.isFocused || state.hasFocus) {
                            onFocusStateChanged?.invoke(true)
                        }
                    }
                    .then(firstItemFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .clickable { onFocusStateChanged?.invoke(true) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SoulSyncAnimatedBrandMark(size = 24.dp)
                if (profileName != null) {
                    ProfileAvatarCircle(
                        avatarUrl = avatarUrl,
                        name = profileName,
                        size = 26.dp,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isSearchSel) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(18.dp),
                )
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home",
                    tint = if (isHomeSel) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(18.dp),
                )
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        return
    }

    // Auto-focus active page on frame 0 when expanding from the TV floating pill!
    LaunchedEffect(isTv, collapsed) {
        if (isTv && !collapsed) {
            runCatching { activeItemFocusRequester.requestFocus() }
        }
    }
    val isGuideSel = current is PlayerDestination.Guide
    val isCalendarSel = current is PlayerDestination.Calendar
    val isWatchlistSel = current is PlayerDestination.Watchlist
    val isWatchingNowSel = current is PlayerDestination.WatchingNow
    val isMoviesSel = current is PlayerDestination.Movies ||
        current is PlayerDestination.MovieDetail ||
        (current is PlayerDestination.CollectionDetail && current.collection.mediaType == "movie")
    val isTvSel = current is PlayerDestination.Tv ||
        current is PlayerDestination.ShowDetail ||
        current is PlayerDestination.EpisodeDetail ||
        (current is PlayerDestination.CollectionDetail && current.collection.mediaType != "movie")
    val isYouTubeSel = current is PlayerDestination.YouTube || current is PlayerDestination.YouTubeChannel
    val isMusicSel = current is PlayerDestination.Music || current is PlayerDestination.AlbumDetail || current is PlayerDestination.ArtistDetail
    val isPlaylistsSel = current is PlayerDestination.Playlists || current is PlayerDestination.PlaylistDetail
    val isPodcastsSel = current is PlayerDestination.Podcasts || current is PlayerDestination.PodcastDetail
    val isAudiobooksSel = current is PlayerDestination.Audiobooks || current is PlayerDestination.AudiobookDetail
    val isAdminOrDownloadsSel = if (isAdmin) {
        current is PlayerDestination.AdminUsers || current is PlayerDestination.Downloads
    } else {
        current is PlayerDestination.Downloads
    }
    val isSettingsSel = current is PlayerDestination.Settings
    val anySidebarSelected = isHomeSel || isSearchSel || isGuideSel || isCalendarSel || isWatchlistSel ||
        isWatchingNowSel || isMoviesSel || isTvSel || isYouTubeSel || isMusicSel ||
        isPlaylistsSel || isPodcastsSel || isAudiobooksSel || isAdminOrDownloadsSel || isSettingsSel

    fun activeMod(selected: Boolean, extra: Modifier = Modifier): Modifier =
        if (selected) extra.focusRequester(activeItemFocusRequester) else extra

    val animatedWidth by animateDpAsState(
        targetValue = if (collapsed) 68.dp else if (isTv) 264.dp else 252.dp,
        animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "sidebarWidth",
    )
    val tvFloatingDrawer = isTv && isOverlay
    val drawerShape = if (tvFloatingDrawer) RoundedCornerShape(22.dp) else RoundedCornerShape(0.dp)
    Row(
        modifier = Modifier
            .then(if (tvFloatingDrawer) Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp) else Modifier)
            .width(animatedWidth)
            .fillMaxHeight()
            .clip(drawerShape)
            .background(
                if (tvFloatingDrawer) {
                    Brush.verticalGradient(
                        listOf(Color(0xF60A0C15), Color(0xF60D101E)),
                    )
                } else if (isOverlay) {
                    Brush.horizontalGradient(
                        listOf(Color(0xFA090B14), Color(0xF2101424)),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A0C16), Color(0xFF0D101D)),
                    )
                },
            )
            .then(
                if (tvFloatingDrawer) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), drawerShape)
                else Modifier,
            )
            .then(
                if (isTv) {
                    Modifier
                        .focusProperties {
                            enter = { activeItemFocusRequester }
                        }
                        .focusGroup()
                } else {
                    Modifier
                },
            )
            .onFocusChanged { state ->
                val nowFocused = state.hasFocus
                wasSidebarFocused = nowFocused
                onFocusStateChanged?.invoke(nowFocused)
            },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .statusBarsPadding()
                .verticalScroll(sidebarScrollState)
                .padding(horizontal = if (isTv) 8.dp else 10.dp, vertical = if (isTv) 8.dp else 16.dp),
            horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            // Animated SoulSync cinema brand header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (collapsed) 0.dp else 6.dp, vertical = if (isTv) 2.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                SoulSyncAnimatedBrandMark(size = if (isTv) 28.dp else 32.dp)
                if (!collapsed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "SoulSync",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            letterSpacing = (-0.2).sp,
                        )
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFF43F5E), SoulSyncColors.AccentLight, Color(0xFF38BDF8)),
                                    ),
                                ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isTv) 10.dp else 14.dp))

            if (isTv && !collapsed && profileName != null && onProfileClick != null) {
                var profileFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (profileFocused) Color.White else Color.White.copy(alpha = 0.05f))
                        .onFocusChanged { profileFocused = it.isFocused || it.hasFocus }
                        .clickable(onClick = onProfileClick)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileAvatarCircle(
                        avatarUrl = avatarUrl,
                        name = profileName,
                        size = 26.dp,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = profileName,
                        color = if (profileFocused) Color(0xFF0B0D14) else Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (profileFocused) Color(0xFF0B0D14) else SoulSyncColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                SidebarItem(
                    icon = Icons.Default.Search,
                    label = "Search",
                    selected = isSearchSel,
                    collapsed = collapsed,
                    itemModifier = activeMod(isSearchSel),
                ) { onNavigate(PlayerDestination.Search) }
            }

            SidebarItem(
                icon = Icons.Default.Home,
                label = "Home",
                selected = isHomeSel,
                collapsed = collapsed,
                itemModifier = activeMod(
                    selected = isHomeSel || !anySidebarSelected,
                    extra = firstItemFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                ),
            ) { onNavigate(PlayerDestination.Home) }
            val leftOut = PlayerNavPrefs.hidden.value
            if ("/player/guide" !in leftOut) SidebarItem(Icons.Default.LiveTv, "Live Guide", isGuideSel, collapsed, itemModifier = activeMod(isGuideSel)) { onNavigate(PlayerDestination.Guide) }
            if ("/player/calendar" !in leftOut) SidebarItem(Icons.Default.CalendarMonth, "Calendar", isCalendarSel, collapsed, itemModifier = activeMod(isCalendarSel)) { onNavigate(PlayerDestination.Calendar) }
            if ("/player/watchlist" !in leftOut) SidebarItem(Icons.Default.Bookmark, "Watchlist", isWatchlistSel, collapsed, itemModifier = activeMod(isWatchlistSel)) { onNavigate(PlayerDestination.Watchlist) }
            if ("/player/watching" !in leftOut) SidebarItem(
                icon = Icons.Default.Group,
                label = "Watching now",
                selected = isWatchingNowSel,
                collapsed = collapsed,
                badgeCount = watchingCount,
                itemModifier = activeMod(isWatchingNowSel),
            ) { onNavigate(PlayerDestination.WatchingNow) }

            // the two sections below are drawn in the profile's own order, with the places it
            // left out gone (player settings, the same ones the web player's sidebar follows)
            val navOrder = PlayerNavPrefs.order.value
            val navHidden = PlayerNavPrefs.hidden.value
            val ownPageLibraries = HomeWarmCache.ownPageLibraries.value
            // a library an admin gave its own page (Anime, Kids, 4K): an entry after the page it is a kind of
            fun libraryPlaces(kind: String) = ownPageLibraries.filter { it.kind == kind }.map { lib ->
                SidebarPlace(
                    "lib:${lib.id}",
                    if (kind == "movie") Icons.Default.Movie else Icons.Default.Tv,
                    lib.name,
                    current is PlayerDestination.Library && current.library.id == lib.id,
                ) { onNavigate(PlayerDestination.Library(lib)) }
            }
            val watchPlaces = listOf(SidebarPlace("/player/movies", Icons.Default.Movie, "Movies", isMoviesSel) { onNavigate(PlayerDestination.Movies) }) +
                libraryPlaces("movie") +
                listOf(SidebarPlace("/player/tv", Icons.Default.Tv, "TV Shows", isTvSel) { onNavigate(PlayerDestination.Tv) }) +
                libraryPlaces("show") +
                listOf(SidebarPlace("/player/youtube", Icons.Default.SmartDisplay, "YouTube", isYouTubeSel) { onNavigate(PlayerDestination.YouTube) })
            val listenPlaces = listOf(
                SidebarPlace("/player/music", Icons.Default.MusicNote, "Music", isMusicSel) { onNavigate(PlayerDestination.Music) },
                SidebarPlace("/player/music/playlists", Icons.AutoMirrored.Filled.QueueMusic, "Playlists", isPlaylistsSel) { onNavigate(PlayerDestination.Playlists) },
                SidebarPlace("/player/podcasts", Icons.Default.Podcasts, "Podcasts", isPodcastsSel) { onNavigate(PlayerDestination.Podcasts) },
                SidebarPlace("/player/audiobooks", Icons.Default.Headphones, "Audiobooks", isAudiobooksSel) { onNavigate(PlayerDestination.Audiobooks) },
            )
            for ((title, places) in listOf("WATCH" to watchPlaces, "LISTEN" to listenPlaces)) {
                val byId = places.associateBy { it.id }
                val shown = net.ssync.player.NavOrder.arrange(places.map { it.id }, navOrder, navHidden).mapNotNull { byId[it] }
                if (shown.isEmpty()) continue
                SidebarSectionBreak(title, collapsed)
                shown.forEach { place ->
                    SidebarItem(place.icon, place.label, place.selected, collapsed, itemModifier = activeMod(place.selected), onClick = place.open)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(horizontal = if (collapsed) 10.dp else 4.dp, vertical = 8.dp),
            )

            if (isAdmin) {
                SidebarItem(
                    Icons.Default.AdminPanelSettings,
                    "Users & Downloads",
                    isAdminOrDownloadsSel,
                    collapsed,
                    itemModifier = activeMod(isAdminOrDownloadsSel),
                ) { onNavigate(PlayerDestination.AdminUsers) }
            } else {
                SidebarItem(
                    Icons.Default.Download,
                    "Downloads",
                    isAdminOrDownloadsSel,
                    collapsed,
                    itemModifier = activeMod(isAdminOrDownloadsSel),
                ) { onNavigate(PlayerDestination.Downloads) }
            }
            SidebarItem(Icons.Default.Settings, "Player Settings", isSettingsSel, collapsed, itemModifier = activeMod(isSettingsSel)) { onNavigate(PlayerDestination.Settings) }
            if (showCollapseToggle) {
                SidebarItem(
                    icon = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    label = collapseLabel,
                    selected = false,
                    collapsed = collapsed,
                    onClick = onToggleCollapse,
                )
            }
        }
        if (!tvFloatingDrawer) {
            // Subtle luminous right-edge separator
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(
                        if (isOverlay) SoulSyncColors.AccentLight.copy(alpha = 0.28f)
                        else Color.White.copy(alpha = 0.08f),
                    ),
            )
        }
    }
}

@Composable
private fun SidebarSectionBreak(text: String, collapsed: Boolean) {
    if (collapsed) {
        Box(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .width(22.dp)
                .height(1.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
        )
    } else {
        Text(
            text = text,
            color = SoulSyncColors.TextSecondary.copy(alpha = 0.78f),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 5.dp),
        )
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    collapsed: Boolean,
    badgeCount: Int = 0,
    itemModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isTv = LocalIsTv.current
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        isTv && focused -> Color(0xFF0B0D14)
        selected || focused -> Color.White
        else -> SoulSyncColors.TextSoft
    }
    val iconTint = when {
        isTv && focused -> Color(0xFF0B0D14)
        focused -> Color.White
        selected -> SoulSyncColors.AccentLight
        else -> SoulSyncColors.TextSoft
    }
    val itemShape = if (isTv) RoundedCornerShape(999.dp) else RoundedCornerShape(12.dp)
    val rowBg = when {
        isTv && focused -> Brush.horizontalGradient(listOf(Color.White, Color.White))
        focused -> Brush.horizontalGradient(
            listOf(SoulSyncColors.Accent.copy(alpha = 0.52f), SoulSyncColors.AccentLight.copy(alpha = 0.24f)),
        )
        selected -> Brush.horizontalGradient(
            listOf(SoulSyncColors.Accent.copy(alpha = 0.24f), SoulSyncColors.Accent.copy(alpha = 0.06f)),
        )
        else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val borderColor = if (!isTv && focused) SoulSyncColors.AccentLight.copy(alpha = 0.72f) else Color.Transparent
    Row(
        modifier = itemModifier
            .fillMaxWidth()
            .clip(itemShape)
            .background(rowBg)
            .border(1.dp, borderColor, itemShape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(
                start = if (collapsed) 0.dp else 8.dp,
                end = if (collapsed) 0.dp else 12.dp,
                top = if (isTv) 7.dp else 10.dp,
                bottom = if (isTv) 7.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        // Signature glowing left accent bar for the active destination
        if (!collapsed) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(if (selected) SoulSyncColors.AccentLight else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(9.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(if (isTv) 19.dp else 20.dp))
            if (collapsed && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981)),
                )
            }
        }
        if (!collapsed) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.22f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeCount.toString(),
                        color = Color(0xFF6EE7B7),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerContent(
    current: PlayerDestination,
    record: PairedServerRecord,
    api: PlayerApiClient,
    pinnedHttpClient: okhttp3.OkHttpClient,
    coordinator: PlaybackCoordinator,
    openHomeEditor: Boolean,
    onConsumeHomeEditor: () -> Unit,
    onCustomizeHome: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit,
    onScrolledChanged: (Boolean) -> Unit,
    onHasHeroChanged: (Boolean) -> Unit,
    onDeviceRevoked: () -> Unit,
    onUnpair: () -> Unit,
    onSettingsChanged: (net.ssync.player.PlayerSettings) -> Unit = {},
) {
    when (current) {
        PlayerDestination.Home -> HomeScreen(
            api = api,
            pinnedHttpClient = pinnedHttpClient,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
            onScrolledChanged = onScrolledChanged,
            onHasHeroChanged = onHasHeroChanged,
            openEditorInitially = openHomeEditor,
            onEditorConsumed = onConsumeHomeEditor,
        )
        PlayerDestination.Guide -> GuideScreen(api, coordinator, onDeviceRevoked, onNavigate)
        PlayerDestination.Calendar -> CalendarScreen(api, onNavigate, onDeviceRevoked, coordinator = coordinator)
        PlayerDestination.Downloads -> DownloadsScreen(api, coordinator)
        PlayerDestination.AdminUsers -> AdminUsersScreen(api, coordinator, onDeviceRevoked)
        PlayerDestination.Movies -> MoviesScreen(api, { onNavigate(PlayerDestination.MovieDetail(it)) }, onDeviceRevoked, coordinator = coordinator, onNavigate = onNavigate)
        is PlayerDestination.Library -> if (current.library.kind == "movie") {
            MoviesScreen(api, { onNavigate(PlayerDestination.MovieDetail(it)) }, onDeviceRevoked, coordinator = coordinator, onNavigate = onNavigate, library = current.library)
        } else {
            TvScreen(api, { onNavigate(PlayerDestination.ShowDetail(it)) }, onDeviceRevoked, coordinator = coordinator, onNavigate = onNavigate, library = current.library)
        }
        is PlayerDestination.MovieDetail -> MovieDetailScreen(current.movieId, api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.CollectionDetail -> CollectionDetailScreen(current.collection, api, onNavigate, onDeviceRevoked)
        PlayerDestination.Tv -> TvScreen(api, { onNavigate(PlayerDestination.ShowDetail(it)) }, onDeviceRevoked, coordinator = coordinator, onNavigate = onNavigate)
        is PlayerDestination.ShowDetail -> ShowDetailScreen(
            showId = current.showId,
            api = api,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
            initialSeason = current.initialSeason,
            highlightEpisode = current.highlightEpisode,
        )
        is PlayerDestination.EpisodeDetail -> EpisodeDetailScreen(current.showId, current.episodeId, api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.PersonDetail -> PersonDetailScreen(current.tmdbId, current.name, api, onNavigate, onDeviceRevoked)
        PlayerDestination.Music -> MusicScreen(api, coordinator, onNavigate, onDeviceRevoked)
        PlayerDestination.Playlists -> MusicScreen(api, coordinator, onNavigate, onDeviceRevoked, startOnPlaylists = true)
        is PlayerDestination.ArtistDetail -> CinemaArtistDetailScreen(
            artistId = current.artistId,
            api = api,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
        )
        is PlayerDestination.AlbumDetail -> CinemaAlbumDetailScreen(
            albumId = current.albumId,
            api = api,
            coordinator = coordinator,
            onDeviceRevoked = onDeviceRevoked,
            onNavigate = onNavigate,
            highlightTrackId = current.highlightTrackId,
        )
        is PlayerDestination.PlaylistDetail -> PlaylistDetailScreen(current.playlistId, api, coordinator, onDeviceRevoked)
        PlayerDestination.YouTube -> YouTubeScreen(api, coordinator, { id, title -> onNavigate(PlayerDestination.YouTubeChannel(id, title)) }, onDeviceRevoked)
        is PlayerDestination.YouTubeChannel -> YouTubeChannelScreen(current.channelId, current.title, api, coordinator, onDeviceRevoked)
        PlayerDestination.Podcasts -> PodcastsScreen(api, { onNavigate(PlayerDestination.PodcastDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.PodcastDetail -> PodcastDetailScreen(current.sub, api, coordinator, onDeviceRevoked)
        PlayerDestination.Audiobooks -> AudiobooksScreen(api, { onNavigate(PlayerDestination.AudiobookDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.AudiobookDetail -> AudiobookDetailScreen(current.book, api, coordinator)
        PlayerDestination.Watchlist -> WatchlistScreen(api, onNavigate, onDeviceRevoked)
        PlayerDestination.WatchingNow -> WatchingNowScreen(api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.StartWatchParty -> WatchingNowScreen(
            api = api,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
            initialSeed = MobilePartySeed(
                itemType = current.itemType,
                itemId = current.itemId,
                title = current.title,
                artwork = current.artwork,
                backdrop = current.backdrop,
            ),
        )
        PlayerDestination.Search -> SearchScreen(api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.SearchQuery -> SearchScreen(api, coordinator, onNavigate, onDeviceRevoked, initialQuery = current.query)
        PlayerDestination.Settings -> SettingsScreen(
            record = record,
            api = api,
            onCustomizeHome = onCustomizeHome,
            onUnpair = onUnpair,
            onSettingsChanged = onSettingsChanged,
        )
    }
}

/** "3:07" */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * What is playing, in the corner of a TV page: the cover, the title and
 * artist, how far in it is, and play / next / stop. One card of a fixed
 * width. (It used to size itself around a progress strip that asked for a
 * fraction of all the room there was, so it grew a little wider every
 * second the song played.)
 */
@Composable
internal fun TvTopRightMiniPlayer(
    coordinator: PlaybackCoordinator,
    item: ActivePlayable,
) {
    val isPlaying = coordinator.isPlaying
    val duration = coordinator.durationMs
    val position = coordinator.positionMs
    val frac = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val accent = SoulSyncColors.AccentLight
    val shape = RoundedCornerShape(14.dp)

    Surface(
        shape = shape,
        color = Color(0xF2121420),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 10.dp,
        modifier = Modifier.width(396.dp).height(52.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // the progress line is drawn on the card, along its foot: it takes no room
                .drawBehind {
                    val h = 2.dp.toPx()
                    drawRect(Color.White.copy(alpha = 0.08f), Offset(0f, size.height - h), Size(size.width, h))
                    drawRect(accent, Offset(0f, size.height - h), Size(size.width * frac, h))
                },
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 7.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the cover and the words open the full player
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .dpadFocusable(RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { coordinator.showAudioSheet = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SoulSyncArtwork(
                        url = item.artworkUrl,
                        contentDescription = item.title,
                        fallbackIcon = Icons.Default.MusicNote,
                        maxPx = 192,
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOf(
                                item.subtitle.takeIf { it.isNotBlank() },
                                if (duration > 0L) "${clock(position)} / ${clock(duration)}" else null,
                            ).filterNotNull().joinToString("  ·  "),
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                TvMiniButton(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (isPlaying) "Pause" else "Play",
                    strong = true,
                ) { coordinator.togglePlayPause() }
                TvMiniButton(Icons.Default.SkipNext, "Next") { coordinator.skipNext() }
                TvMiniButton(Icons.Default.Close, "Stop music", quiet = true) { coordinator.stopPlayback() }
            }
        }
    }
}

@Composable
private fun TvMiniButton(
    icon: ImageVector,
    label: String,
    strong: Boolean = false,
    quiet: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(if (strong) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .dpadFocusable(CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (quiet) SoulSyncColors.TextSoft else Color.White,
            modifier = Modifier.size(if (strong) 20.dp else 18.dp),
        )
    }
}

@Composable
private fun MiniTransportBarHost(
    coordinator: PlaybackCoordinator,
    item: ActivePlayable,
) {
    val frac = if (coordinator.durationMs > 0L) {
        (coordinator.positionMs.toFloat() / coordinator.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    MiniTransportBar(
        item = item,
        isPlaying = coordinator.isPlaying,
        progressFraction = frac,
        onExpand = { coordinator.showAudioSheet = true },
        onTogglePlayPause = { coordinator.togglePlayPause() },
        onNext = { coordinator.skipNext() },
        onStop = { coordinator.stopPlayback() },
    )
}

@Composable
private fun MiniTransportBar(
    item: ActivePlayable,
    isPlaying: Boolean,
    progressFraction: Float = 0f,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xEE151724),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .dpadFocusable(RoundedCornerShape(16.dp))
            .clickable(onClick = onExpand),
    ) {
        Column {
            // Iconic Plexamp top hairline progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(SoulSyncColors.AccentLight),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
                    SoulSyncArtwork(url = item.artworkUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = SoulSyncColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .dpadFocusable(CircleShape),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(38.dp).dpadFocusable()) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

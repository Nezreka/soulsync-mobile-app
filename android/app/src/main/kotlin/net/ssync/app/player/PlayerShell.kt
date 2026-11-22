package net.ssync.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val coordinator = remember(api) {
        PlaybackCoordinator(
            api = api,
            scope = scope,
            controllerProvider = { mediaController },
        )
    }

    var backStack by remember { mutableStateOf<List<PlayerDestination>>(listOf(PlayerDestination.Home)) }
    val current = backStack.last()
    var showProfileSheet by remember { mutableStateOf(false) }
    var openHomeEditor by remember { mutableStateOf(false) }
    var sidebarCollapsed by remember { mutableStateOf(false) }
    var headerScrolled by remember { mutableStateOf(false) }
    var homeHasHero by remember { mutableStateOf(false) }
    var watchingOthersCount by remember { mutableIntStateOf(0) }
    var activeAvatarUrl by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

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

    BackHandler(
        enabled = drawerState.isOpen ||
            coordinator.showVideoOverlay ||
            coordinator.showAudioSheet ||
            backStack.size > 1,
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
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

    val hasFullBleedHero = (current is PlayerDestination.Home && homeHasHero) ||
        current is PlayerDestination.MovieDetail ||
        current is PlayerDestination.ShowDetail ||
        current is PlayerDestination.EpisodeDetail

    CompositionLocalProvider(LocalSoulSyncImageLoader provides imageLoader) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(SoulSyncColors.BgPrimary),
        ) {
            val usePermanentSidebar = maxWidth >= 700.dp

            val mainScaffold: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SoulSyncColors.BgPrimary)
                        .navigationBarsPadding(),
                ) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (usePermanentSidebar) {
                            PlayerSidebar(
                                current = current,
                                collapsed = sidebarCollapsed,
                                watchingCount = watchingOthersCount,
                                showCollapseToggle = true,
                                onNavigate = { navigateTo(it, clearToRoot = true) },
                                onToggleCollapse = { sidebarCollapsed = !sidebarCollapsed },
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (hasFullBleedHero) Modifier
                                        else Modifier.statusBarsPadding().padding(top = 60.dp),
                                    ),
                            ) {
                                key(profileEpoch) {
                                    PlayerContent(
                                        current = current,
                                        record = currentRecord,
                                        api = api,
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
                                    )
                                }
                            }

                            FloatingTopHeader(
                                record = currentRecord,
                                avatarUrl = activeAvatarUrl ?: api.artwork.profileAvatar(currentRecord.profileId),
                                showBack = backStack.size > 1,
                                showMenuButton = !usePermanentSidebar,
                                transparentOverHero = hasFullBleedHero && !headerScrolled,
                                onBack = { popBack() },
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onSearch = { navigateTo(PlayerDestination.Search) },
                                onProfileClick = { showProfileSheet = true },
                            )
                        }
                    }

                    // Persistent Mini Transport Bar (only when audio is active)
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

            if (showProfileSheet) {
                ProfileMenuSheet(
                    record = currentRecord,
                    activeAvatarUrl = activeAvatarUrl,
                    api = api,
                    onSwitchProfile = { chosen ->
                        scope.launch {
                            val res = api.pickDeviceProfile(chosen.id)
                            if (res is MusicApiResult.Success) {
                                val updated = currentRecord.copy(
                                    profileId = chosen.id.toString(),
                                    profileName = chosen.name,
                                )
                                currentRecord = updated
                                KeystoreServerVault(context.applicationContext).save(updated)
                                profileEpoch += 1
                                showProfileSheet = false
                                navigateTo(PlayerDestination.Home, clearToRoot = true)
                            }
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
                    api = api,
                    pinnedHttpClient = pinnedClient,
                    onOpenChannel = { channelId ->
                        coordinator.closeVideo(coordinator.positionMs, coordinator.durationMs)
                        navigateTo(PlayerDestination.YouTubeChannel(channelId, videoItem.subtitle.ifBlank { "Channel" }))
                    },
                    onClose = { pos, dur -> coordinator.closeVideo(pos, dur) },
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
    transparentOverHero: Boolean,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onProfileClick: () -> Unit,
) {
    val bgModifier = if (transparentOverHero) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(SoulSyncColors.BgPrimary.copy(alpha = 0.82f), Color.Transparent),
            ),
        )
    } else {
        Modifier.background(SoulSyncColors.BgPrimary.copy(alpha = 0.94f))
    }

    Column(modifier = Modifier.fillMaxWidth().then(bgModifier)) {
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
                    Image(
                        painter = painterResource(id = R.drawable.soulsync_mark),
                        contentDescription = "SoulSync",
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "SoulSync",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = (-0.3).sp,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onSearch,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SoulSyncColors.PanelGlass)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .dpadFocusable(CircleShape),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(19.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Profile chip -> opens Profile & Household Switcher Sheet
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(SoulSyncColors.PanelGlass)
                    .border(1.dp, SoulSyncColors.LineStrong, RoundedCornerShape(999.dp))
                    .clickable(onClick = onProfileClick)
                    .dpadFocusable(RoundedCornerShape(999.dp))
                    .padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatarCircle(
                    avatarUrl = avatarUrl,
                    name = record.profileName,
                    size = 24.dp,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = record.profileName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Switch profile",
                    tint = SoulSyncColors.TextSoft,
                    modifier = Modifier.size(16.dp),
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
    onSwitchProfile: (DeviceProfileInfo) -> Unit,
    onOpenSettings: () -> Unit,
    onCustomizeHome: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    var profiles by remember { mutableStateOf<List<DeviceProfileInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        loading = true
        (api.fetchDeviceProfiles() as? MusicApiResult.Success)?.let { profiles = it.data }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SoulSyncColors.Surface,
        contentColor = SoulSyncColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Current profile header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val currentProfileAvatar = activeAvatarUrl
                    ?: profiles
                        .firstOrNull { it.id.toString() == record.profileId }
                        ?.avatarUrl
                        ?.let { api.artwork.resolve(it) }
                    ?: api.artwork.profileAvatar(record.profileId)
                ProfileAvatarCircle(
                    avatarUrl = currentProfileAvatar,
                    name = record.profileName,
                    size = 44.dp,
                    fontSize = 18.sp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.profileName,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${record.serverName ?: "SoulSync"} · ${record.formattedFingerprint}",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            // Switch profile section
            val others = profiles.filter { it.id.toString() != record.profileId }
            if (others.isNotEmpty()) {
                HorizontalDivider(color = SoulSyncColors.Line)
                Text(
                    text = "SWITCH PROFILE",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                others.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
                            .clickable { onSwitchProfile(p) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProfileAvatarCircle(
                            avatarUrl = api.artwork.resolve(p.avatarUrl) ?: api.artwork.profileAvatar(p.id.toString()),
                            name = p.name,
                            size = 32.dp,
                            fontSize = 14.sp,
                            containerColor = SoulSyncColors.Accent.copy(alpha = 0.25f),
                            initialColor = SoulSyncColors.AccentLight,
                        )
                        Text(
                            text = p.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.id.toString() == record.profileId) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = SoulSyncColors.AccentLight)
                        }
                    }
                }
            } else if (!loading && profiles.size <= 1) {
                Text(
                    text = "Signed in as the only profile available on this device.",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            HorizontalDivider(color = SoulSyncColors.Line)

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
private fun ProfileSheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = SoulSyncColors.TextSoft, modifier = Modifier.size(20.dp))
        Text(text = label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PlayerSidebar(
    current: PlayerDestination,
    collapsed: Boolean,
    watchingCount: Int,
    showCollapseToggle: Boolean,
    onNavigate: (PlayerDestination) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(if (collapsed) 72.dp else 248.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x0EFFFFFF), Color(0x05FFFFFF)),
                ),
            )
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.soulsync_mark),
                contentDescription = "SoulSync",
                modifier = Modifier.size(30.dp),
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
        SidebarItem(Icons.Default.LiveTv, "Live Guide", current is PlayerDestination.Guide, collapsed) { onNavigate(PlayerDestination.Guide) }
        SidebarItem(Icons.Default.Bookmark, "Watchlist", current is PlayerDestination.Watchlist, collapsed) { onNavigate(PlayerDestination.Watchlist) }
        SidebarItem(
            icon = Icons.Default.Group,
            label = if (watchingCount > 0) "Watching ($watchingCount)" else "Watching now",
            selected = current is PlayerDestination.WatchingNow,
            collapsed = collapsed,
        ) { onNavigate(PlayerDestination.WatchingNow) }

        if (!collapsed) SidebarGroupLabel("WATCH") else Spacer(Modifier.height(10.dp))
        SidebarItem(Icons.Default.Movie, "Movies", current is PlayerDestination.Movies || current is PlayerDestination.MovieDetail, collapsed) { onNavigate(PlayerDestination.Movies) }
        SidebarItem(Icons.Default.Tv, "TV Shows", current is PlayerDestination.Tv || current is PlayerDestination.ShowDetail || current is PlayerDestination.EpisodeDetail, collapsed) { onNavigate(PlayerDestination.Tv) }
        SidebarItem(Icons.Default.SmartDisplay, "YouTube", current is PlayerDestination.YouTube || current is PlayerDestination.YouTubeChannel, collapsed) { onNavigate(PlayerDestination.YouTube) }

        if (!collapsed) SidebarGroupLabel("LISTEN") else Spacer(Modifier.height(10.dp))
        SidebarItem(Icons.Default.MusicNote, "Music", current is PlayerDestination.Music || current is PlayerDestination.AlbumDetail || current is PlayerDestination.ArtistDetail || current is PlayerDestination.PlaylistDetail, collapsed) { onNavigate(PlayerDestination.Music) }
        SidebarItem(Icons.Default.Podcasts, "Podcasts", current is PlayerDestination.Podcasts || current is PlayerDestination.PodcastDetail, collapsed) { onNavigate(PlayerDestination.Podcasts) }
        SidebarItem(Icons.Default.Headphones, "Audiobooks", current is PlayerDestination.Audiobooks || current is PlayerDestination.AudiobookDetail, collapsed) { onNavigate(PlayerDestination.Audiobooks) }

        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider(color = SoulSyncColors.Line, modifier = Modifier.padding(vertical = 10.dp))

        SidebarItem(Icons.Default.Settings, "Player Settings", current is PlayerDestination.Settings, collapsed) { onNavigate(PlayerDestination.Settings) }
        if (showCollapseToggle) {
            SidebarItem(
                icon = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                label = "Collapse",
                selected = false,
                collapsed = collapsed,
                onClick = onToggleCollapse,
            )
        }
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
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        if (!collapsed) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = if (selected) Color.White else SoulSyncColors.TextSoft,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
    openHomeEditor: Boolean,
    onConsumeHomeEditor: () -> Unit,
    onCustomizeHome: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit,
    onScrolledChanged: (Boolean) -> Unit,
    onHasHeroChanged: (Boolean) -> Unit,
    onDeviceRevoked: () -> Unit,
    onUnpair: () -> Unit,
) {
    when (current) {
        PlayerDestination.Home -> HomeScreen(
            api = api,
            coordinator = coordinator,
            onNavigate = onNavigate,
            onDeviceRevoked = onDeviceRevoked,
            onScrolledChanged = onScrolledChanged,
            onHasHeroChanged = onHasHeroChanged,
            openEditorInitially = openHomeEditor,
            onEditorConsumed = onConsumeHomeEditor,
        )
        PlayerDestination.Guide -> GuideScreen(api, coordinator, onDeviceRevoked, onNavigate)
        PlayerDestination.Movies -> MoviesScreen(api, { onNavigate(PlayerDestination.MovieDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.MovieDetail -> MovieDetailScreen(current.movieId, api, coordinator, onNavigate, onDeviceRevoked)
        PlayerDestination.Tv -> TvScreen(api, { onNavigate(PlayerDestination.ShowDetail(it)) }, onDeviceRevoked)
        is PlayerDestination.ShowDetail -> ShowDetailScreen(current.showId, api, coordinator, onNavigate, onDeviceRevoked)
        is PlayerDestination.EpisodeDetail -> EpisodeDetailScreen(current.showId, current.episodeId, api, coordinator, onNavigate, onDeviceRevoked)
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
            onCustomizeHome = onCustomizeHome,
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
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) {
                SoulSyncArtwork(url = item.artworkUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize())
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
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
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.dpadFocusable()) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
            }
        }
    }
}

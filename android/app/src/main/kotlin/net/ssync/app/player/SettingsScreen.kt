package net.ssync.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerSettings
import net.ssync.storage.PairedServerRecord
import kotlin.math.abs
import kotlin.math.roundToInt

private val ASK_AFTER_OPTIONS = listOf(0, 2, 3, 4, 5, 8)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    record: PairedServerRecord,
    api: PlayerApiClient,
    onCustomizeHome: () -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        PlayerDevicePrefs.init(context)
    }

    val devicePrefs by PlayerDevicePrefs.state.collectAsState()
    var profileSettings by remember { mutableStateOf(PlayerSettings()) }
    var loadedProfile by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showSavedBadge by remember { mutableStateOf(false) }
    var savedPulse by remember { mutableStateOf(0) }
    var confirmUnpair by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        when (val res = api.fetchSettings()) {
            is MusicApiResult.Success -> {
                profileSettings = res.data
                loadedProfile = true
            }
            else -> loadedProfile = true
        }
    }

    LaunchedEffect(savedPulse) {
        if (savedPulse > 0) {
            showSavedBadge = true
            delay(1800)
            showSavedBadge = false
        }
    }

    fun flashSaved() {
        savedPulse += 1
    }

    fun saveProfile(next: PlayerSettings) {
        profileSettings = next
        saveError = null
        scope.launch {
            when (val res = api.saveSettings(next)) {
                is MusicApiResult.Success -> {
                    profileSettings = res.data
                    flashSaved()
                }
                else -> {
                    saveError = "Couldn't save that change — try again in a moment."
                }
            }
        }
    }

    fun updateDevice(transform: (DeviceDisplayPrefs) -> DeviceDisplayPrefs) {
        PlayerDevicePrefs.update(transform)
        flashSaved()
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header + animated Saved pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Player settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Profile preferences sync across screens. Display preferences stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoulSyncColors.TextSecondary,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                AnimatedVisibility(
                    visible = showSavedBadge,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SoulSyncColors.Accent.copy(alpha = 0.16f))
                            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = SoulSyncColors.AccentLight,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Saved",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // 1. Playback & Profile (Synced across all screens)
        item {
            SettingsCard(
                icon = Icons.Default.PlayArrow,
                title = "Playback & profile",
                scopeBadge = "ALL SCREENS",
                subtitle = "Synced with your SoulSync profile.",
            ) {
                SettingsToggleRow(
                    label = "Share what I'm playing",
                    help = "Show your live session on Watching now so others can join you.",
                    checked = profileSettings.shareActivity,
                    enabled = loadedProfile,
                    onCheckedChange = { saveProfile(profileSettings.copy(shareActivity = it)) },
                )
                SettingsDivider()
                SettingsToggleRow(
                    label = "Autoplay next episode",
                    help = "Automatically start the next episode when one finishes.",
                    checked = profileSettings.autoplayNext,
                    enabled = loadedProfile,
                    onCheckedChange = { saveProfile(profileSettings.copy(autoplayNext = it)) },
                )
                SettingsDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Still watching check",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Pause and ask after consecutive episodes with no activity.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    SegmentedPillRow(
                        options = ASK_AFTER_OPTIONS.map { n ->
                            n to if (n == 0) "Never" else "$n eps"
                        },
                        selected = profileSettings.stillWatchingAfter,
                        enabled = loadedProfile,
                        onSelect = { saveProfile(profileSettings.copy(stillWatchingAfter = it)) },
                    )
                }
                if (saveError != null) {
                    SettingsDivider()
                    Text(
                        text = saveError!!,
                        color = SoulSyncColors.Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // 2. Subtitles (This screen) with live cinema preview
        item {
            SettingsCard(
                icon = Icons.Default.Subtitles,
                title = "Subtitles",
                scopeBadge = "THIS SCREEN",
                subtitle = "Preview updates live as you adjust.",
            ) {
                // Live cinema subtitle preview strip
                val previewSize = when (devicePrefs.subtitleSize) {
                    "small" -> 13.sp
                    "large" -> 19.sp
                    else -> 16.sp
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(104.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF141B2D).copy(alpha = devicePrefs.brightness.coerceAtLeast(0.35f)),
                                    Color(0xFF0A0D16).copy(alpha = devicePrefs.brightness.coerceAtLeast(0.35f)),
                                    Color(0xFF10211B).copy(alpha = devicePrefs.brightness.coerceAtLeast(0.35f)),
                                ),
                            ),
                        )
                        .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        text = "The night is darkest just before the dawn.",
                        color = Color.White,
                        fontSize = previewSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (devicePrefs.subtitleBox) Color.Black.copy(alpha = 0.75f)
                                else Color.Transparent,
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Subtitle size",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SegmentedPillRow(
                        options = PlayerDevicePrefs.SUBTITLE_SIZES,
                        selected = devicePrefs.subtitleSize,
                        onSelect = { size -> updateDevice { it.copy(subtitleSize = size) } },
                    )
                }
                SettingsDivider()
                SettingsToggleRow(
                    label = "Dark backing box",
                    help = "Improves contrast over bright scenes.",
                    checked = devicePrefs.subtitleBox,
                    onCheckedChange = { box -> updateDevice { it.copy(subtitleBox = box) } },
                )
            }
        }

        // 3. Video & Display (This screen)
        item {
            SettingsCard(
                icon = Icons.Default.Tv,
                title = "Video & display",
                scopeBadge = "THIS SCREEN",
                subtitle = "Tailored for this phone, tablet, or TV.",
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Video quality",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Lower plays smoother on slow connections. Original plays directly whenever possible.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayerDevicePrefs.QUALITIES.forEach { (key, info) ->
                            val selected = devicePrefs.videoQuality == key
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) SoulSyncColors.Accent.copy(alpha = 0.16f)
                                        else Color.White.copy(alpha = 0.035f),
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) SoulSyncColors.Accent.copy(alpha = 0.6f)
                                        else SoulSyncColors.Line,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .dpadFocusable(RoundedCornerShape(10.dp))
                                    .clickable { updateDevice { it.copy(videoQuality = key) } }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = info.first,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = info.second,
                                    color = if (selected) SoulSyncColors.TextSoft else SoulSyncColors.TextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                SettingsDivider()

                val activeFitDetail = PlayerDevicePrefs.FITS
                    .firstOrNull { it.first == devicePrefs.pictureFit }?.third.orEmpty()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Picture shape",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (activeFitDetail.isNotBlank()) {
                            Text(
                                text = activeFitDetail,
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    SegmentedPillRow(
                        options = PlayerDevicePrefs.FITS.map { it.first to it.second },
                        selected = devicePrefs.pictureFit,
                        onSelect = { fit -> updateDevice { it.copy(pictureFit = fit) } },
                    )
                }

                SettingsDivider()

                val activeBrightness = PlayerDevicePrefs.BRIGHTNESS_STEPS
                    .minByOrNull { abs(it - devicePrefs.brightness) } ?: 1.0f
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Screen brightness",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Dims the video picture for watching in the dark.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    SegmentedPillRow(
                        options = PlayerDevicePrefs.BRIGHTNESS_STEPS.map { step ->
                            step to if (step >= 0.99f) "Full" else "${(step * 100).roundToInt()}%"
                        },
                        selected = activeBrightness,
                        onSelect = { b -> updateDevice { it.copy(brightness = b) } },
                    )
                }

                SettingsDivider()

                SettingsToggleRow(
                    label = "Background trailers",
                    help = "Automatically play muted trailers behind movie and show detail pages.",
                    checked = devicePrefs.backgroundTrailers,
                    onCheckedChange = { on -> updateDevice { it.copy(backgroundTrailers = on) } },
                )
            }
        }

        // 4. Music & Home (This screen)
        item {
            SettingsCard(
                icon = Icons.Default.MusicNote,
                title = "Music & home",
                scopeBadge = "THIS SCREEN",
                subtitle = "Visual ambiance and your home dashboard.",
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Music visuals",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Animated backdrop shown in full-screen Now Playing.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayerDevicePrefs.MUSIC_VISUALS.forEach { (id, label) ->
                            val selected = devicePrefs.musicVisual == id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (selected) SoulSyncColors.Accent.copy(alpha = 0.18f)
                                        else Color.White.copy(alpha = 0.04f),
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) SoulSyncColors.Accent.copy(alpha = 0.6f)
                                        else SoulSyncColors.Line,
                                        RoundedCornerShape(999.dp),
                                    )
                                    .dpadFocusable(RoundedCornerShape(999.dp))
                                    .clickable { updateDevice { it.copy(musicVisual = id) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.White else SoulSyncColors.TextSoft,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Home dashboard layout",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Show, hide, reorder, and restyle your home rails.",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SoulSyncColors.Accent.copy(alpha = 0.16f))
                            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .dpadFocusable(RoundedCornerShape(999.dp))
                            .clickable(onClick = onCustomizeHome)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = SoulSyncColors.AccentLight,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Customize",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = SoulSyncColors.AccentLight,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // 5. Connected Server & Device Security
        item {
            SettingsCard(
                icon = Icons.Default.Lock,
                title = "Connected server",
                scopeBadge = "CERT-PINNED",
                subtitle = "Direct HTTPS · zero cloud media relay.",
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ServerMetaRow("Server", record.serverName ?: "SoulSync")
                    ServerMetaRow("Profile", record.profileName)
                    ServerMetaRow("Fingerprint", record.formattedFingerprint, accent = true)
                }
                SettingsDivider()
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = { confirmUnpair = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoulSyncColors.Danger.copy(alpha = 0.18f),
                            contentColor = Color(0xFFFCA5A5),
                        ),
                        border = BorderStroke(1.dp, SoulSyncColors.Danger.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .dpadFocusable(RoundedCornerShape(10.dp)),
                    ) {
                        Text("Unpair This Device", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = SoulSyncColors.Surface,
            title = { Text("Unpair this device?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This clears your pinned certificate and device token from Android Keystore. You'll need a new pairing code to reconnect.",
                    color = SoulSyncColors.TextSoft,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                ) {
                    Text("Unpair", color = SoulSyncColors.Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) {
                    Text("Cancel", color = SoulSyncColors.TextSoft)
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    scopeBadge: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.055f),
                        Color.White.copy(alpha = 0.02f),
                    ),
                ),
            )
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SoulSyncColors.Accent.copy(alpha = 0.14f))
                    .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = SoulSyncColors.AccentLight,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = scopeBadge,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, SoulSyncColors.Line, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        HorizontalDivider(color = SoulSyncColors.Line)
        content()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

@Composable
private fun SettingsToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = help,
                color = SoulSyncColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SoulSyncColors.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.18f),
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SegmentedPillRow(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (on) SoulSyncColors.Accent.copy(alpha = 0.55f) else Color.Transparent,
                        RoundedCornerShape(7.dp),
                    )
                    .dpadFocusable(RoundedCornerShape(7.dp))
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (on) Color.White else SoulSyncColors.TextSoft,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ServerMetaRow(label: String, value: String, accent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = SoulSyncColors.TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            color = if (accent) SoulSyncColors.AccentLight else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

package net.ssync.app.player

import net.ssync.pairing.ui.dpadFocusable

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.AdminUsersOverview
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import java.io.File

private fun formatDownloadBytes(bytes: Long): String = if (bytes <= 0L) "—" else net.ssync.player.OfflineFormat.bytes(bytes)

@Composable
fun AdminUsersScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var activeTab by remember { mutableStateOf("admin") } // "admin" | "mine"
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<AdminUsersOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedUserId by remember { mutableStateOf<Int?>(null) }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(api, selectedUserId, statusFilter, refreshTick) {
        loading = true
        when (val res = api.fetchAdminUsers(profileId = selectedUserId, status = statusFilter)) {
            is MusicApiResult.Success -> {
                overview = res.data
                errorMsg = null
            }
            is MusicApiResult.Error -> errorMsg = res.message
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
        }
        loading = false
    }

    val isTv = net.ssync.pairing.ui.LocalIsTv.current
    val sidePadStart = if (isTv) 78.dp else 16.dp
    val sidePadEnd = if (isTv) 32.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(SoulSyncColors.Accent.copy(alpha = 0.12f), Color(0xFF0A0C16)),
                ),
            ),
    ) {
        // Unified Cinema Hub Header + Pill Tabs: My Downloads | Users & Devices
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = sidePadStart, end = sidePadEnd, top = if (isTv) 20.dp else 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(22.dp))
            Text("Users & Downloads", color = Color.White, fontSize = if (isTv) 24.sp else 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
            Spacer(modifier = Modifier.width(6.dp))
            TvCinemaPillTab(
                label = "Users & Devices",
                selected = activeTab == "admin",
                onClick = { activeTab = "admin" },
            )
            TvCinemaPillTab(
                label = "My Downloads",
                selected = activeTab == "mine",
                onClick = { activeTab = "mine" },
            )
        }

        if (activeTab == "mine") {
            DownloadsScreen(api = api, coordinator = coordinator)
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePadStart, end = sidePadEnd, top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item {
            Text(
                "Manage household download permissions and monitor offline storage across paired devices.",
                color = SoulSyncColors.TextSecondary,
                fontSize = 12.5.sp,
            )
        }

        if (loading && overview == null) {
            item { LoadingBox() }
        } else if (errorMsg != null) {
            item {
                Text(
                    text = errorMsg.orEmpty(),
                    color = Color(0xFFFDA4AF),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            // nothing loaded (a refused first load): nothing to show, not a crash
            val data = overview ?: return@LazyColumn
            // Summary Row
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val cards = listOf(
                        "Users" to "${data.users.size}",
                        "On devices" to "${data.totals.activeDownloads}",
                        "Storage" to formatDownloadBytes(data.totals.activeBytes),
                        "Removed" to "${data.totals.removedDownloads}",
                    )
                    items(cards) { (lbl, value) ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.045f),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                            modifier = Modifier.width(126.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(lbl, color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
                                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            // Profiles Section
            item {
                Text(
                    text = "Profiles",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            items(data.users, key = { "u-${it.id}" }) { u ->
                val selected = selectedUserId == u.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) SoulSyncColors.Accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, if (selected) SoulSyncColors.Accent else SoulSyncColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { selectedUserId = if (selected) null else u.id },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SoulSyncColors.Accent.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = u.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(u.name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                if (u.isAdmin) {
                                    Text(
                                        text = "ADMIN",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(SoulSyncColors.Accent.copy(alpha = 0.24f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "${u.activeDownloads} on device · ${formatDownloadBytes(u.activeBytes)} · ${u.removedDownloads} removed",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                            )
                        }
                        if (!u.isAdmin) {
                            androidx.compose.material3.Switch(
                                checked = u.allowDownloads,
                                onCheckedChange = { allow ->
                                    scope.launch {
                                        api.setAdminUserDownloadPerm(u.id, allow)
                                        refreshTick++
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Activity Filter Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Download Activity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "All", "downloaded" to "On device", "removed" to "Removed").forEach { (st, lbl) ->
                            val on = statusFilter == st
                            Text(
                                text = lbl,
                                color = if (on) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f))
                                    .clickable { statusFilter = st }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            items(data.downloads, key = { "dl-${it.id}" }) { dl ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.035f),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${dl.title} (${dl.itemType.uppercase()})",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${dl.profileName ?: "User ${dl.profileId}"} · ${dl.deviceName} · ${formatDownloadBytes(dl.sizeBytes)}",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val active = dl.status == "downloaded"
                        Text(
                            text = if (active) "On device" else "Removed",
                            color = if (active) Color(0xFF6EE7B7) else Color(0xFFFDA4AF),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) Color(0xFF10B981).copy(alpha = 0.18f) else Color(0xFFF43F5E).copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

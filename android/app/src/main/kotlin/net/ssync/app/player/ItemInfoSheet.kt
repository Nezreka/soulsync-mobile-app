package net.ssync.app.player

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.ItemInfo
import net.ssync.player.ItemInfoApiClient
import net.ssync.player.ItemInfoRow
import net.ssync.player.ItemInfoTypes
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

private sealed interface InfoLoad {
    data object Loading : InfoLoad
    data class Ready(val info: ItemInfo) : InfoLoad
    data class Failed(val message: String) : InfoLoad
}

/**
 * the Info sheet: metadata and file details for one item, as headed sections
 * of label and value rows. a bottom sheet on a phone, a side panel on a tv
 * (d-pad scrolls it, Back closes it)
 */
@Composable
fun ItemInfoSheet(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    fallbackTitle: String = "",
    fallbackSubtitle: String = "",
    fallbackArtwork: String? = null,
    onDismiss: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val client = remember(api) { ItemInfoApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(itemType, itemId) { mutableStateOf<InfoLoad>(InfoLoad.Loading) }
    LaunchedEffect(itemType, itemId, attempt) {
        load = InfoLoad.Loading
        load = when (val r = client.fetch(itemType, itemId)) {
            is MusicApiResult.Success -> InfoLoad.Ready(r.data)
            is MusicApiResult.Error -> InfoLoad.Failed(r.message)
            MusicApiResult.DeviceRevoked -> InfoLoad.Failed("This device was signed out. Pair it again to see info.")
        }
    }
    val info = (load as? InfoLoad.Ready)?.info
    val title = info?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val subtitle = info?.subtitle?.takeIf { it.isNotBlank() } ?: fallbackSubtitle
    val artwork = info?.artwork?.let { api.artwork.resolve(it) } ?: fallbackArtwork

    AdaptiveTvSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131520),
        contentColor = Color.White,
        panelWidth = 520.dp,
    ) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isTv) Modifier.fillMaxSize() else Modifier.heightIn(max = 640.dp))
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = if (isTv) 4.dp else 14.dp),
        ) {
            InfoHeader(title = title, subtitle = subtitle, artwork = artwork)
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (val l = load) {
                    InfoLoad.Loading -> InfoMessage("Loading info…", busy = true)
                    is InfoLoad.Failed -> {
                        InfoMessage("Couldn't load the info. ${l.message}")
                        TextButton(
                            onClick = { attempt++ },
                            modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                        ) { Text("Try again", color = SoulSyncColors.AccentLight) }
                    }
                    is InfoLoad.Ready -> {
                        if (l.info.sections.isEmpty()) {
                            InfoMessage("The server has no details for this one yet.")
                        } else {
                            val first = remember { FocusRequester() }
                            l.info.sections.forEachIndexed { si, section ->
                                if (section.title.isNotBlank()) {
                                    Text(
                                        text = section.title.uppercase(),
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(top = if (si == 0) 2.dp else 12.dp, bottom = 2.dp, start = 4.dp),
                                    )
                                }
                                section.rows.forEachIndexed { ri, row ->
                                    InfoRow(
                                        row = row,
                                        isTv = isTv,
                                        modifier = if (si == 0 && ri == 0) Modifier.focusRequester(first) else Modifier,
                                    )
                                }
                            }
                            if (isTv) {
                                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun InfoHeader(title: String, subtitle: String, artwork: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1D2030))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork.isNullOrBlank()) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(22.dp))
            } else {
                SoulSyncArtwork(url = artwork, contentDescription = title, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.ifBlank { "Info" },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.ifBlank { "Info" },
                color = SoulSyncColors.TextSecondary,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoMessage(text: String, busy: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SoulSyncColors.AccentLight)
        Text(text, color = SoulSyncColors.TextSoft, fontSize = 13.5.sp)
    }
}

@Composable
private fun InfoRow(row: ItemInfoRow, isTv: Boolean, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(row) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    val doCopy = {
        clipboard.setText(AnnotatedString(row.value))
        copied = true
    }
    val shape = RoundedCornerShape(10.dp)
    // on a tv every row takes focus so the d-pad walks (and scrolls) the list
    val interactive = when {
        row.copy -> Modifier.clickable(onClick = doCopy).dpadFocusable(shape)
        isTv -> Modifier.clickable(onClick = {}).dpadFocusable(shape)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.035f))
            .then(interactive)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = row.label,
            color = SoulSyncColors.TextSecondary,
            fontSize = 12.5.sp,
            modifier = Modifier.width(if (isTv) 140.dp else 112.dp),
        )
        Text(
            text = row.value,
            color = SoulSyncColors.TextPrimary,
            fontSize = if (row.mono) 12.sp else 13.5.sp,
            fontFamily = if (row.mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        if (row.copy) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy ${row.label}",
                tint = if (copied) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** a round Info icon that opens the sheet itself; nothing when the kind has no info */
@Composable
fun ItemInfoIconButton(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    title: String = "",
    subtitle: String = "",
    artworkUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    tint: Color = SoulSyncColors.TextSecondary,
    background: Color? = null,
) {
    if (!ItemInfoTypes.supports(itemType) || itemId.isBlank()) return
    var open by remember(itemType, itemId) { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (background != null) Modifier.background(background) else Modifier)
            .dpadFocusable(CircleShape),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = "Info", tint = tint, modifier = Modifier.size(iconSize))
    }
    if (open) {
        ItemInfoSheet(
            api = api,
            itemType = itemType,
            itemId = itemId,
            fallbackTitle = title,
            fallbackSubtitle = subtitle,
            fallbackArtwork = artworkUrl,
            onDismiss = { open = false },
        )
    }
}

/** an "Info" pill for detail pages, next to Report issue */
@Composable
fun ItemInfoPillButton(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    title: String = "",
    subtitle: String = "",
    artworkUrl: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (!ItemInfoTypes.supports(itemType) || itemId.isBlank()) return
    var open by remember(itemType, itemId) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        shape = CircleShape,
        border = BorderStroke(1.dp, SoulSyncColors.Line),
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp) else PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.height(if (compact) 38.dp else 40.dp).dpadFocusable(RoundedCornerShape(999.dp)),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = SoulSyncColors.TextSoft, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Info", color = SoulSyncColors.TextSoft, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Medium)
    }
    if (open) {
        ItemInfoSheet(
            api = api,
            itemType = itemType,
            itemId = itemId,
            fallbackTitle = title,
            fallbackSubtitle = subtitle,
            fallbackArtwork = artworkUrl,
            onDismiss = { open = false },
        )
    }
}

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.dpadFocusable()) {
                Text(
                    text = actionLabel,
                    color = SoulSyncColors.AccentLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun MediaRail(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SectionHeader(title = title, actionLabel = actionLabel, onAction = onAction)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Full-bleed cinematic Hero matching SoulSync `/player`'s premier hero banner:
 * bleeds edge-to-edge underneath the floating translucent header with multi-stop
 * ink vignettes, frosted metadata pills, and high-contrast pill CTAs.
 */
@Composable
fun HeroSpotlight(
    title: String,
    subtitle: String,
    backdropUrl: String?,
    inWatchlist: Boolean = false,
    onPlay: () -> Unit,
    onToggleWatchlist: (() -> Unit)? = null,
) {
    val chips = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(370.dp)
            .background(SoulSyncColors.BgPrimary),
    ) {
        SoulSyncArtwork(
            url = backdropUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
        )
        // Top vignette for transparent header legibility + bottom ink fade into canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to SoulSyncColors.BgPrimary.copy(alpha = 0.72f),
                        0.28f to Color.Transparent,
                        0.58f to SoulSyncColors.BgPrimary.copy(alpha = 0.55f),
                        0.85f to SoulSyncColors.BgPrimary.copy(alpha = 0.92f),
                        1.0f to SoulSyncColors.BgPrimary,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.take(4).forEach { chip ->
                        Box(
                            modifier = Modifier
                                .background(SoulSyncColors.PanelGlass, RoundedCornerShape(999.dp))
                                .border(1.dp, SoulSyncColors.LineStrong, RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = chip,
                                color = SoulSyncColors.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (onToggleWatchlist != null) {
                    OutlinedButton(
                        onClick = onToggleWatchlist,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SoulSyncColors.PanelGlass,
                            contentColor = SoulSyncColors.TextPrimary,
                        ),
                        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                    ) {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                            contentDescription = null,
                            tint = if (inWatchlist) SoulSyncColors.AccentLight else SoulSyncColors.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (inWatchlist) "In Watchlist" else "Watchlist",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    badge: String? = null,
    progress: Float? = null,
    width: Dp = 122.dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SoulSyncColors.PanelSolid),
            border = BorderStroke(1.dp, SoulSyncColors.Line),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(Modifier.fillMaxSize()) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.Movie,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!badge.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(SoulSyncColors.BgPrimary.copy(alpha = 0.84f), RoundedCornerShape(6.dp))
                            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(text = "★ $badge", color = SoulSyncColors.Warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = SoulSyncColors.AccentLight,
                        trackColor = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = SoulSyncColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SquareMediaCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    width: Dp = 132.dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SoulSyncColors.PanelSolid),
            border = BorderStroke(1.dp, SoulSyncColors.Line),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = title,
                fallbackIcon = fallbackIcon,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = SoulSyncColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun WideMediaCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    progress: Float? = null,
    width: Dp = 228.dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SoulSyncColors.PanelSolid),
            border = BorderStroke(1.dp, SoulSyncColors.Line),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1.0f to SoulSyncColors.BgPrimary.copy(alpha = 0.7f),
                            ),
                        ),
                )
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = SoulSyncColors.AccentLight,
                        trackColor = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = SoulSyncColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SoulSyncColors.Accent)
    }
}

@Composable
fun EmptyBox(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

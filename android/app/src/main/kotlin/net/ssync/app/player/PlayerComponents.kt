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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
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
    eyebrow: String? = null,
    logoUrl: String? = null,
    inWatchlist: Boolean = false,
    onPlay: () -> Unit,
    onDetails: (() -> Unit)? = null,
    onToggleWatchlist: (() -> Unit)? = null,
    backgroundVideo: (@Composable () -> Unit)? = null,
) {
    val chips = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
    val imageLoader = LocalSoulSyncImageLoader.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp)
            .background(SoulSyncColors.BgPrimary),
    ) {
        SoulSyncArtwork(
            url = backdropUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
        )
        backgroundVideo?.invoke()
        // Top vignette for transparent header legibility + bottom ink fade into canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to SoulSyncColors.BgPrimary.copy(alpha = 0.86f),
                        0.26f to SoulSyncColors.BgPrimary.copy(alpha = 0.16f),
                        0.52f to SoulSyncColors.BgPrimary.copy(alpha = 0.35f),
                        0.82f to SoulSyncColors.BgPrimary.copy(alpha = 0.92f),
                        1.0f to SoulSyncColors.BgPrimary,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            if (!eyebrow.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .background(SoulSyncColors.Accent.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = eyebrow.uppercase(),
                        color = SoulSyncColors.AccentLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!logoUrl.isNullOrBlank() && imageLoader != null) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxWidth(0.74f)
                        .height(68.dp),
                    error = {
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
                    },
                )
            } else {
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
            }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (onDetails != null) {
                    OutlinedButton(
                        onClick = onDetails,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SoulSyncColors.PanelGlass,
                            contentColor = SoulSyncColors.TextPrimary,
                        ),
                        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp)),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Details", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                if (onToggleWatchlist != null) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (inWatchlist) SoulSyncColors.Accent.copy(alpha = 0.22f)
                                else SoulSyncColors.PanelGlass,
                            )
                            .border(
                                1.dp,
                                if (inWatchlist) SoulSyncColors.AccentLight else SoulSyncColors.LineStrong,
                                CircleShape,
                            )
                            .dpadFocusable(CircleShape)
                            .clickable(onClick = onToggleWatchlist),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                            contentDescription = if (inWatchlist) "In Watchlist" else "Add to Watchlist",
                            tint = if (inWatchlist) SoulSyncColors.AccentLight else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

val LocalPlayerSettings = androidx.compose.runtime.staticCompositionLocalOf {
    net.ssync.player.PlayerSettings()
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
    val cardPrefs = LocalPlayerSettings.current
    val effectiveBadge = androidx.compose.runtime.remember(badge, cardPrefs) {
        val raw = badge?.trim().orEmpty()
        when {
            raw.isEmpty() -> null
            raw.matches(Regex("^★?\\s*\\d+(\\.\\d+)?$")) && !cardPrefs.showMovieRatings -> null
            raw.matches(Regex("^\\d+\\s*eps?$", RegexOption.IGNORE_CASE)) && !cardPrefs.showTvEpisodeCount -> null
            else -> raw
        }
    }
    val effectiveSubtitle = androidx.compose.runtime.remember(subtitle, cardPrefs) {
        val raw = subtitle?.trim().orEmpty()
        when {
            raw.isEmpty() -> null
            !cardPrefs.showTvEpisodeCount && raw.matches(Regex("^\\d+\\s*episodes?$", RegexOption.IGNORE_CASE)) -> null
            !cardPrefs.showYearOnCards && raw.matches(Regex("^\\d{4}$")) -> null
            else -> raw
        }
    }

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
                if (!effectiveBadge.isNullOrBlank()) {
                    val isRating = effectiveBadge.matches(Regex("^★?\\s*\\d+(\\.\\d+)?$"))
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(SoulSyncColors.BgPrimary.copy(alpha = 0.84f), RoundedCornerShape(6.dp))
                            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isRating && !effectiveBadge.startsWith("★")) "★ $effectiveBadge" else effectiveBadge,
                            color = if (isRating) SoulSyncColors.Warning else SoulSyncColors.AccentLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
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
        if (!effectiveSubtitle.isNullOrBlank()) {
            Text(
                text = effectiveSubtitle,
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
fun RankedPosterCard(
    rank: Int,
    title: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = rank.toString(),
            color = SoulSyncColors.TextPrimary.copy(alpha = 0.22f),
            fontSize = 68.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-4).sp,
            modifier = Modifier.padding(end = 2.dp),
        )
        PosterCard(
            title = title,
            artworkUrl = artworkUrl,
            width = 116.dp,
            onClick = onClick,
        )
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

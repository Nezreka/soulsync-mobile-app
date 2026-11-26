package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadCardFocusable
import net.ssync.pairing.ui.dpadFocusable

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val isTv = LocalIsTv.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTv) 24.dp else 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = if (isTv) 17.sp else 18.sp,
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
    val isTv = LocalIsTv.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = if (isTv) 10.dp else 8.dp)) {
        SectionHeader(title = title, actionLabel = actionLabel, onAction = onAction)
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = if (isTv) 24.dp else 18.dp,
                vertical = if (isTv) 10.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
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
    playButtonModifier: Modifier = Modifier,
) {
    val isTv = LocalIsTv.current
    val chips = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
    val imageLoader = LocalSoulSyncImageLoader.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 272.dp else 430.dp)
            .background(SoulSyncColors.BgPrimary),
    ) {
        SoulSyncArtwork(
            url = backdropUrl,
            contentDescription = title,
            maxPx = 1920,
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
                .padding(
                    start = if (isTv) 24.dp else 18.dp,
                    end = if (isTv) 24.dp else 18.dp,
                    top = if (isTv) 14.dp else 20.dp,
                    bottom = if (isTv) 16.dp else 20.dp,
                ),
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

            if (!logoUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .size(1080)
                        .precision(coil.size.Precision.INEXACT)
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
                var playFocused by remember { mutableStateOf(false) }
                val playScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (playFocused) 1.05f else 1f,
                    animationSpec = androidx.compose.animation.core.tween(180),
                    label = "heroPlayScale",
                )
                val playBg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (playFocused) SoulSyncColors.Accent else Color.White,
                    animationSpec = androidx.compose.animation.core.tween(160),
                    label = "heroPlayBg",
                )
                val playFg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (playFocused) Color.White else Color.Black,
                    animationSpec = androidx.compose.animation.core.tween(160),
                    label = "heroPlayFg",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = playButtonModifier
                        .height(40.dp)
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                        }
                        .clip(RoundedCornerShape(999.dp))
                        .background(playBg)
                        .onFocusChanged { playFocused = it.isFocused }
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 20.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = playFg,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Play", color = playFg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (onDetails != null) {
                    var detailsFocused by remember { mutableStateOf(false) }
                    val detailsScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (detailsFocused) 1.05f else 1f,
                        animationSpec = androidx.compose.animation.core.tween(180),
                        label = "heroDetailsScale",
                    )
                    val detailsBg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (detailsFocused) Color.White else SoulSyncColors.PanelGlass,
                        animationSpec = androidx.compose.animation.core.tween(160),
                        label = "heroDetailsBg",
                    )
                    val detailsFg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (detailsFocused) Color.Black else SoulSyncColors.TextPrimary,
                        animationSpec = androidx.compose.animation.core.tween(160),
                        label = "heroDetailsFg",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .height(40.dp)
                            .graphicsLayer {
                                scaleX = detailsScale
                                scaleY = detailsScale
                            }
                            .clip(RoundedCornerShape(999.dp))
                            .background(detailsBg)
                            .then(
                                if (detailsFocused) Modifier
                                else Modifier.border(1.dp, SoulSyncColors.LineStrong, RoundedCornerShape(999.dp)),
                            )
                            .onFocusChanged { detailsFocused = it.isFocused }
                            .clickable(onClick = onDetails)
                            .padding(horizontal = 16.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = detailsFg,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Details", color = detailsFg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                if (onToggleWatchlist != null) {
                    var watchlistFocused by remember { mutableStateOf(false) }
                    val watchlistScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (watchlistFocused) 1.07f else 1f,
                        animationSpec = androidx.compose.animation.core.tween(180),
                        label = "heroWatchlistScale",
                    )
                    val watchlistBg by androidx.compose.animation.animateColorAsState(
                        targetValue = when {
                            watchlistFocused && inWatchlist -> SoulSyncColors.Accent
                            watchlistFocused -> Color.White
                            inWatchlist -> SoulSyncColors.Accent.copy(alpha = 0.22f)
                            else -> SoulSyncColors.PanelGlass
                        },
                        animationSpec = androidx.compose.animation.core.tween(160),
                        label = "heroWatchlistBg",
                    )
                    val watchlistFg by androidx.compose.animation.animateColorAsState(
                        targetValue = when {
                            watchlistFocused && inWatchlist -> Color.White
                            watchlistFocused -> Color.Black
                            inWatchlist -> SoulSyncColors.AccentLight
                            else -> Color.White
                        },
                        animationSpec = androidx.compose.animation.core.tween(160),
                        label = "heroWatchlistFg",
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = watchlistScale
                                scaleY = watchlistScale
                            }
                            .clip(CircleShape)
                            .background(watchlistBg)
                            .then(
                                if (watchlistFocused) Modifier
                                else Modifier.border(
                                    1.dp,
                                    if (inWatchlist) SoulSyncColors.AccentLight else SoulSyncColors.LineStrong,
                                    CircleShape,
                                ),
                            )
                            .onFocusChanged { watchlistFocused = it.isFocused }
                            .clickable(onClick = onToggleWatchlist),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                            contentDescription = if (inWatchlist) "In Watchlist" else "Add to Watchlist",
                            tint = watchlistFg,
                            modifier = Modifier.size(18.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    badge: String? = null,
    progress: Float? = null,
    inLibrary: Boolean? = true,
    width: Dp = 122.dp,
    contextItem: MediaContextItem? = null,
    /** a small label top left, like "4K" when another copy is 4K */
    chip: String? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
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
            .dpadCardFocusable(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
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
                if (!chip.isNullOrBlank()) {
                    TitleChip(chip, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                if (inLibrary != null) {
                    LibraryCornerBadge(
                        inLibrary = inLibrary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
                if (!effectiveBadge.isNullOrBlank()) {
                    val isRating = effectiveBadge.matches(Regex("^★?\\s*\\d+(\\.\\d+)?$"))
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 6.dp,
                                bottom = if (progress != null && progress > 0f) 8.dp else 6.dp,
                            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SquareMediaCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    width: Dp = 132.dp,
    contextItem: MediaContextItem? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(width)
            .dpadCardFocusable(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WideMediaCard(
    title: String,
    subtitle: String? = null,
    artworkUrl: String?,
    fallbackArtworkUrl: String? = null,
    secondaryFallbackArtworkUrl: String? = null,
    progress: Float? = null,
    width: Dp = 228.dp,
    contextItem: MediaContextItem? = null,
    chip: String? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(width)
            .dpadCardFocusable(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
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
                    fallbackUrl = fallbackArtworkUrl,
                    secondaryFallbackUrl = secondaryFallbackArtworkUrl,
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
                if (!chip.isNullOrBlank()) {
                    TitleChip(chip, Modifier.align(Alignment.TopStart).padding(6.dp))
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

/** a small label on a card's picture: "4K" */
@Composable
fun TitleChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun LibraryCornerBadge(
    inLibrary: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(21.dp)
            .clip(CircleShape)
            .background(if (inLibrary) Color(0xFF22C55E) else Color(0xFFF59E0B))
            .border(1.5.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (inLibrary) Icons.Default.Check else Icons.Default.Add,
            contentDescription = if (inLibrary) "In your library" else "Not in library",
            tint = if (inLibrary) Color.White else Color(0xFF14110A),
            modifier = Modifier.size(if (inLibrary) 13.dp else 14.dp),
        )
    }
}

@Composable
fun RankedPosterCard(
    rank: Int,
    title: String,
    artworkUrl: String?,
    contextItem: MediaContextItem? = null,
    chip: String? = null,
    onClick: () -> Unit,
) {
    Row(
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
            contextItem = contextItem,
            chip = chip,
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

/**
 * Adaptive modal surface:
 * - On Android TV (`LocalIsTv.current == true`): renders an Apple TV / Plex-style right-docked
 *   frosted-glass cinema side panel that glides in smoothly with Back dismissal (never a stretched
 *   mobile bottom sheet with a touch drag handle).
 * - On Phone / Tablet: renders the standard Material3 `ModalBottomSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveTvSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color = SoulSyncColors.Surface,
    contentColor: Color = Color.White,
    panelWidth: Dp = 440.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val isTv = LocalIsTv.current
    if (isTv) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible,
                    enter = androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(200),
                    ) + androidx.compose.animation.slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(240),
                        initialOffsetX = { it / 2 },
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(160),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        containerColor.copy(alpha = 0.97f),
                                        Color(0xFA0B0F1A),
                                    ),
                                ),
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                            )
                            .padding(vertical = 22.dp, horizontal = 8.dp),
                        content = content,
                    )
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = containerColor,
            contentColor = contentColor,
            content = content,
        )
    }
}

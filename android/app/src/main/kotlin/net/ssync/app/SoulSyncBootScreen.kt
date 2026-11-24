package net.ssync.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full-screen SoulSync animated boot splash (1:1 parity with Desktop /player):
 * pure black canvas, 2.8s gentle breathing scale + ambient glow, and a 115°
 * metallic glass sheen masked strictly to the SoulSync emblem's alpha channel.
 */
@Composable
fun SoulSyncBootOverlay(
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    var minElapsed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1050L)
        minElapsed = true
    }
    LaunchedEffect(ready, minElapsed) {
        if (ready && minElapsed) {
            visible = false
        }
    }
    LaunchedEffect(Unit) {
        delay(2500L)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(durationMillis = 360)),
        modifier = modifier,
    ) {
        val transition = rememberInfiniteTransition(label = "soulsync_boot")
        val breathe by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe",
        )
        val sheenProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = EaseInOut),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sheen",
        )

        val scale = 1f + 0.03f * breathe
        val glowAlpha = 0.24f * breathe
        val sheenAlpha = when {
            sheenProgress < 0.12f -> sheenProgress / 0.12f
            sheenProgress > 0.88f -> (1f - sheenProgress) / 0.12f
            else -> 1f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Soft ambient halo matching Desktop drop-shadow(0 0 18px rgba(130, 180, 255, 0.24))
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF82B4FF).copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // Emblem + 115° metallic sheen masked strictly to logo alpha via SrcAtop
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val w = size.width
                        val h = size.height
                        val travel = (1.6f - 3.2f * sheenProgress) * w
                        val start = Offset(travel, 0f)
                        val end = Offset(travel + w * 0.72f, h * 0.92f)
                        drawRect(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.36f to Color.Transparent,
                                    0.50f to Color.White.copy(alpha = 0.52f * sheenAlpha),
                                    0.64f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                ),
                                start = start,
                                end = end,
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.soulsync_mark),
                    contentDescription = "SoulSync",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

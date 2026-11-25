package net.ssync.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Cinema OLED SoulSync boot splash (1:1 parity with Desktop /player & Chromecast):
 * - Dual-lobe drifting chromatic aura (warm magenta/amber + cool cyan/violet)
 * - Smooth cinema reveal (scale 0.90 -> 1.0, fade-in, subtle upward glide)
 * - Weightless 3.2s breathe + micro-float (-5dp) + micro-tilt (±0.45°)
 * - 115° dual-band prism specular sheen masked strictly to the infinity ribbon alpha
 * - Wide-tracked SOULSYNC wordmark + chromatic hairline
 */
@Composable
fun SoulSyncBootOverlay(
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    var minElapsed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }

    val reveal = remember { Animatable(0f) }
    val wordReveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        )
    }
    LaunchedEffect(Unit) {
        delay(220L)
        wordReveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 620, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        )
    }
    LaunchedEffect(Unit) {
        delay(2200L)
        minElapsed = true
    }
    LaunchedEffect(ready, minElapsed) {
        if (ready && minElapsed) {
            visible = false
        }
    }
    LaunchedEffect(Unit) {
        delay(3600L)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(durationMillis = 420)),
        modifier = modifier,
    ) {
        val transition = rememberInfiniteTransition(label = "soulsync_boot_cinema")
        val breathe by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe",
        )
        val sheenProgress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = EaseInOut),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sheen",
        )
        val auraDrift by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 5600, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "aura",
        )

        val revealVal = reveal.value
        val scale = (0.90f + 0.10f * revealVal) * (1f + 0.035f * breathe)
        val floatY = (1f - revealVal) * 14f - (5f * breathe)
        val tiltDeg = -0.45f + 0.90f * breathe

        val sheenAlpha = when {
            sheenProgress < 0.14f -> sheenProgress / 0.14f
            sheenProgress > 0.84f -> (1f - sheenProgress) / 0.16f
            else -> 0.95f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Warm magenta/amber upper-left OLED aura lobe
            Box(
                modifier = Modifier
                    .size(340.dp)
                    .offset(x = (-44 + 22 * auraDrift).dp, y = (-36 + 18 * auraDrift).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEC4899).copy(alpha = (0.18f + 0.08f * breathe) * revealVal),
                                Color(0xFFFB923C).copy(alpha = (0.11f + 0.06f * breathe) * revealVal),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // Cool cyan/violet lower-right OLED aura lobe
            Box(
                modifier = Modifier
                    .size(340.dp)
                    .offset(x = (44 - 22 * auraDrift).dp, y = (36 - 18 * auraDrift).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF22D3EE).copy(alpha = (0.18f + 0.08f * (1f - breathe)) * revealVal),
                                Color(0xFF7C3AED).copy(alpha = (0.12f + 0.06f * breathe) * revealVal),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Infinity emblem + 115° dual-band prism specular sheen masked to logo alpha
                Box(
                    modifier = Modifier
                        .size(216.dp)
                        .graphicsLayer {
                            alpha = revealVal
                            scaleX = scale
                            scaleY = scale
                            translationY = floatY
                            rotationZ = tiltDeg
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            val w = size.width
                            val h = size.height
                            val travel = (1.65f - 3.3f * sheenProgress) * w
                            val start = Offset(travel, 0f)
                            val end = Offset(travel + w * 0.72f, h * 0.92f)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.30f to Color.Transparent,
                                        0.44f to Color(0xFFFFE0B4).copy(alpha = 0.24f * sheenAlpha),
                                        0.50f to Color.White.copy(alpha = 0.68f * sheenAlpha),
                                        0.56f to Color(0xFFA5F3FC).copy(alpha = 0.26f * sheenAlpha),
                                        0.70f to Color.Transparent,
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

                // Subtle wide-tracked wordmark + chromatic hairline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.graphicsLayer {
                        alpha = wordReveal.value
                        translationY = (1f - wordReveal.value) * 10f
                    },
                ) {
                    Text(
                        text = "SOULSYNC",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 5.5.sp,
                    )
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0xFFEC4899).copy(alpha = 0.8f),
                                        Color(0xFF22D3EE).copy(alpha = 0.85f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

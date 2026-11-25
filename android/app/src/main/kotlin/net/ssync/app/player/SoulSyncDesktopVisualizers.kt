package net.ssync.app.player

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.AudioFrameMetrics
import net.ssync.player.AudioSpectrumBus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Exact 1:1 port of the 17 desktop SoulSync theater visualizers from
 * `webui/src/lib/visualizer/themes.ts` & `painters.ts`, driven in real time at 60fps by
 * live ExoPlayer PCM -> 64-band log FFT + oscilloscope + spectral-flux beat detection via [AudioSpectrumBus].
 */
enum class DesktopVisualizerTheme(val id: String, val label: String, val blurb: String) {
    BARSCOPE("barscope", "Barscope", "WMP Bars, resurrected"),
    SCOPE("scope", "Oscilloscope", "WMP Scope phosphor glow"),
    WAVES("waves", "Waves", "WMP Waves ribbons"),
    SPIKES("spikes", "Spikes", "WMP Spikes radial burst"),
    AURORA("aurora", "Aurora", "Northern-lights drift"),
    VINYL("vinyl", "Vinyl", "Spinning wax, tonearm & grooves"),
    TUNNEL("tunnel", "Tunnel", "Spectrum wormhole"),
    PLASMA("plasma", "Plasma", "WMP Plasma blobs"),
    STARDUST("stardust", "Stardust", "Beat-reactive particles"),
    BATTERY("battery", "Battery", "WMP Battery sparks rising"),
    DOTPLANE("dotplane", "Dot Plane", "WMP dot grid pulsing"),
    ALCHEMY("alchemy", "Alchemy", "WMP morphing blobs"),
    FOUNTAIN("fountain", "Fountain", "Particle fountain, beat-fed"),
    KALEIDO("kaleido", "Kaleidoscope", "Mirrored spectrum bloom"),
    WARP("warp", "Warp", "Starfield at light speed"),
    NEBULA("nebula", "Nebula", "Drifting deep-space clouds"),
    BLOOM("bloom", "Bloom", "Beat rings expanding out"),
}

private class VizParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    var sz: Float,
    var z: Float = 1f,
    var angle: Float = 0f,
)

private class VizRing(
    var r: Float,
    var rot: Float,
    var petals: Int,
    var accent: Boolean,
)

private class DesktopVizRuntime {
    val freq = FloatArray(AudioSpectrumBus.BAND_COUNT)
    val wave = FloatArray(AudioSpectrumBus.WAVE_COUNT)
    val peaks = FloatArray(56)
    var scopeGain = 1f
    var vinylAngle = 0f
    var metrics = AudioFrameMetrics(0.22f, 0f, false, 0.2f, 0.2f, 0.2f, false)
    val particles = ArrayList<VizParticle>(140)
    val rings = ArrayList<VizRing>(24)
    val rng = Random(42)
}

@OptIn(UnstableApi::class)
@Composable
fun DesktopSoulSyncVisualizer(
    artworkUrl: String?,
    theme: DesktopVisualizerTheme,
    isPlaying: Boolean,
    onCycleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rt = remember { DesktopVizRuntime() }
    var frameTick by remember { mutableLongStateOf(0L) }
    var timeSec by remember { mutableFloatStateOf(0f) }

    // Continuous 60fps render loop — increments frameTick so Canvas ALWAYS redraws live audio
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
                lastNanos = now
                timeSec += dt
                val m = AudioSpectrumBus.sampleFrame(
                    isPlaying = isPlaying,
                    timeSec = timeSec,
                    dtSec = dt,
                    outFreq = rt.freq,
                    outWave = rt.wave,
                )
                rt.metrics = m
                if (isPlaying) {
                    rt.vinylAngle = (rt.vinylAngle + dt * (54f + m.energy * 18f)) % 360f
                }
                // Update falling WMP peak caps
                for (i in rt.peaks.indices) {
                    val b = (i * AudioSpectrumBus.BAND_COUNT) / rt.peaks.size
                    rt.peaks[i] = max(rt.freq[b], rt.peaks[i] - 0.55f * dt)
                }
                // Spawn beat-reactive rings for Bloom
                if (m.onset || (rt.rings.isEmpty() && m.energy > 0.12f)) {
                    rt.rings.add(VizRing(0.04f, rt.rng.nextFloat() * 6.28f, 6 + rt.rng.nextInt(5), rt.rng.nextBoolean()))
                    if (rt.rings.size > 18) rt.rings.removeAt(0)
                }
                var ri = rt.rings.size - 1
                while (ri >= 0) {
                    val rg = rt.rings[ri]
                    rg.r += dt * (0.38f + m.energy * 0.45f)
                    rg.rot += dt * 0.35f
                    if (rg.r > 1.25f) rt.rings.removeAt(ri)
                    ri--
                }
                // Update shared particle pool (Stardust, Battery, Fountain, Warp)
                if (rt.particles.size < 90) {
                    repeat(90 - rt.particles.size) {
                        rt.particles.add(
                            VizParticle(
                                x = rt.rng.nextFloat(),
                                y = rt.rng.nextFloat(),
                                vx = (rt.rng.nextFloat() - 0.5f) * 0.25f,
                                vy = -0.15f - rt.rng.nextFloat() * 0.45f,
                                life = rt.rng.nextFloat(),
                                sz = 1.5f + rt.rng.nextFloat() * 3f,
                                z = 0.08f + rt.rng.nextFloat() * 0.92f,
                                angle = rt.rng.nextFloat() * 6.2832f,
                            ),
                        )
                    }
                }
                val speedMul = 0.55f + m.energy * 1.6f + m.beat * 1.4f
                for (p in rt.particles) {
                    p.life -= dt * (0.28f + m.energy * 0.35f)
                    p.x += p.vx * dt * speedMul
                    p.y += p.vy * dt * speedMul
                    p.z -= dt * (0.35f + m.energy * 0.8f + m.beat * 0.6f)
                    if (p.life <= 0f || p.y < -0.05f || p.y > 1.1f || p.x < -0.05f || p.x > 1.05f) {
                        p.x = rt.rng.nextFloat()
                        p.y = 0.98f
                        p.vx = (rt.rng.nextFloat() - 0.5f) * (0.3f + m.treble * 0.4f)
                        p.vy = -(0.28f + rt.rng.nextFloat() * 0.6f + m.bass * 0.5f)
                        p.life = 0.7f + rt.rng.nextFloat() * 0.3f
                    }
                    if (p.z <= 0.02f) {
                        p.z = 1f
                        p.angle = rt.rng.nextFloat() * 6.2832f
                    }
                }
                frameTick++
            }
        }
    }

    val pal1 = SoulSyncColors.Accent
    val pal2 = SoulSyncColors.AccentLight
    val pal3 = Color(0xFF38BDF8)

    Box(
        modifier = modifier.background(Color(0xFF07080F)),
    ) {
        // Subtle dimmed blurred artwork backdrop (except pure dark Oscilloscope & Vinyl)
        if (theme != DesktopVisualizerTheme.SCOPE) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = null,
                maxPx = 180,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(26.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xC4070810)),
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read frameTick so Compose invalidates draw on every single 60fps frame
            @Suppress("UNUSED_VARIABLE")
            val tick = frameTick
            val m = rt.metrics
            val t = timeSec
            when (theme) {
                DesktopVisualizerTheme.BARSCOPE -> drawBarscope(rt, m, pal1, pal2)
                DesktopVisualizerTheme.SCOPE -> drawOscilloscope(rt, m, pal1, pal2)
                DesktopVisualizerTheme.WAVES -> drawWaves(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.SPIKES -> drawSpikes(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.AURORA -> drawAurora(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.VINYL -> drawVinylGrooves(m, rt.vinylAngle, pal1, pal2)
                DesktopVisualizerTheme.TUNNEL -> drawTunnel(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.PLASMA -> drawPlasma(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.STARDUST -> drawStardust(rt, m, pal1, pal2, pal3)
                DesktopVisualizerTheme.BATTERY -> drawBattery(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.DOTPLANE -> drawDotPlane(rt, m, t, pal1, pal2)
                DesktopVisualizerTheme.ALCHEMY -> drawAlchemy(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.FOUNTAIN -> drawFountain(rt, m, pal1, pal2, pal3)
                DesktopVisualizerTheme.KALEIDO -> drawKaleidoscope(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.WARP -> drawWarp(rt, m, pal1, pal2, pal3)
                DesktopVisualizerTheme.NEBULA -> drawNebula(rt, m, t, pal1, pal2, pal3)
                DesktopVisualizerTheme.BLOOM -> drawBloom(rt, m, pal1, pal2, pal3)
            }
        }

        // For Vinyl mode, spin the real album cover as the center wax label!
        if (theme == DesktopVisualizerTheme.VINYL) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.36f)
                    .aspectRatio(1f)
                    .graphicsLayer { rotationZ = rt.vinylAngle }
                    .shadow(16.dp, CircleShape)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = null,
                    maxPx = 540,
                    modifier = Modifier.fillMaxSize(),
                )
                // Spindle hole
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0A0B10))
                        .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                )
            }
        }

        // Live FFT indicator + Theme Switcher Pill (bottom-right)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                .dpadFocusable(CircleShape)
                .clickable(onClick = onCycleTheme)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "Next visualizer",
                tint = SoulSyncColors.AccentLight,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = theme.label,
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// =========================================================================
// 17 DESKTOP SOULSYNC PAINTERS (1:1 PORT FROM painters.ts)
// =========================================================================

private fun DrawScope.drawBarscope(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color) {
    val n = rt.peaks.size
    val w = size.width
    val h = size.height
    val cy = h * 0.52f
    val maxH = h * 0.42f
    val bw = w / n

    drawRect(
        brush = Brush.verticalGradient(
            0f to pal1.copy(alpha = 0f),
            0.52f to pal1.copy(alpha = 0.12f + m.energy * 0.12f),
            1f to pal1.copy(alpha = 0f),
        ),
    )

    for (i in 0 until n) {
        val b = (i * AudioSpectrumBus.BAND_COUNT) / n
        val v = rt.freq[b].coerceIn(0f, 1f)
        val bh = max(3f, v * maxH)
        val x = i * bw + bw * 0.18f
        val ww = max(2f, bw * 0.64f)

        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to pal2.copy(alpha = 0.96f),
                0.45f to pal1.copy(alpha = 0.88f),
                1f to pal1.copy(alpha = 0.16f),
                startY = cy - bh,
                endY = cy + bh,
            ),
            topLeft = Offset(x, cy - bh),
            size = Size(ww, bh * 2f),
            cornerRadius = CornerRadius(min(ww * 0.4f, 4f)),
        )

        // Falling WMP peak caps — flash bright white on beat
        val py = rt.peaks[i] * maxH
        val capAlpha = (0.28f + rt.peaks[i] * 0.45f + m.beat * 0.27f).coerceIn(0f, 1f)
        drawRect(Color.White.copy(alpha = capAlpha), Offset(x, cy - py - 3f), Size(ww, 2.5f))
        drawRect(Color.White.copy(alpha = capAlpha * 0.7f), Offset(x, cy + py + 0.5f), Size(ww, 2.5f))
    }
    drawRect(Color.White.copy(alpha = 0.14f + m.beat * 0.18f), Offset(0f, cy - 0.5f), Size(w, 1f))
}

private fun DrawScope.drawOscilloscope(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color) {
    val w = size.width
    val h = size.height
    val cy = h * 0.5f
    drawRect(Color(0xFF030806))

    // Scope graticule
    val gridCol = Color.White.copy(alpha = 0.06f)
    for (g in 1..4) {
        val y = h * (g / 5f)
        drawLine(gridCol, Offset(0f, y), Offset(w, y), 1f)
    }
    drawLine(gridCol, Offset(w / 2f, 0f), Offset(w / 2f, h), 1f)

    // Corner ticks
    val tickCol = Color.White.copy(alpha = 0.18f)
    val tk = 18f
    drawLine(tickCol, Offset(12f, 12f), Offset(12f + tk, 12f), 1.5f)
    drawLine(tickCol, Offset(12f, 12f), Offset(12f, 12f + tk), 1.5f)
    drawLine(tickCol, Offset(w - 12f, 12f), Offset(w - 12f - tk, 12f), 1.5f)
    drawLine(tickCol, Offset(w - 12f, 12f), Offset(w - 12f, 12f + tk), 1.5f)
    drawLine(tickCol, Offset(12f, h - 12f), Offset(12f + tk, h - 12f), 1.5f)
    drawLine(tickCol, Offset(12f, h - 12f), Offset(12f, h - 12f - tk), 1.5f)
    drawLine(tickCol, Offset(w - 12f, h - 12f), Offset(w - 12f - tk, h - 12f), 1.5f)
    drawLine(tickCol, Offset(w - 12f, h - 12f), Offset(w - 12f, h - 12f - tk), 1.5f)

    // Auto-gain so quiet & loud tracks fill the scope cleanly
    var peak = 0f
    for (v in rt.wave) peak = max(peak, abs(v))
    val tgt = min((0.75f + m.energy * 0.25f) / max(peak * 2f, 0.14f), 5.5f)
    rt.scopeGain += (tgt - rt.scopeGain) * 0.14f
    val amp = h * 0.34f * rt.scopeGain

    val path = Path()
    val n = rt.wave.size
    for (i in 0 until n) {
        val x = (i.toFloat() / (n - 1)) * w
        val y = (cy - rt.wave[i] * amp).coerceIn(10f, h - 10f)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // 3-pass phosphor afterglow
    drawPath(path, pal1.copy(alpha = 0.22f + m.beat * 0.12f), style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, pal2.copy(alpha = 0.65f), style = Stroke(5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = 0.92f), style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawWaves(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    val cy = h * 0.5f
    val colors = listOf(pal1, pal2, pal3)
    colors.forEachIndexed { idx, col ->
        val path = Path()
        val steps = 64
        for (i in 0 until steps) {
            val u = i.toFloat() / (steps - 1)
            val env = sin(u * PI).toFloat()
            val band = rt.freq[(i + idx * 7) % AudioSpectrumBus.BAND_COUNT]
            val waveVal = rt.wave[(i * 4) % AudioSpectrumBus.WAVE_COUNT]
            val yOff = (sin(u * 9f + t * (1.8f + idx * 0.5f)) * 0.35f + waveVal * 0.65f + (band - 0.3f) * 0.8f) * h * 0.32f * env
            val x = u * w
            val y = cy + yOff * (1f - idx * 0.14f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, col.copy(alpha = 0.26f), style = Stroke(14f, cap = StrokeCap.Round))
        drawPath(path, col.copy(alpha = 0.90f), style = Stroke(3.5f + m.beat * 1.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawSpikes(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val minD = min(size.width, size.height)
    val baseR = minD * (0.18f + m.beat * 0.04f)
    val maxSpoke = minD * 0.29f
    val n = AudioSpectrumBus.BAND_COUNT
    drawCircle(
        brush = Brush.radialGradient(listOf(pal1.copy(alpha = 0.35f + m.beat * 0.25f), Color.Transparent), Offset(cx, cy), baseR + maxSpoke),
        radius = baseR + maxSpoke,
        center = Offset(cx, cy),
    )
    for (i in 0 until n) {
        val ang = (i.toFloat() / n) * (2.0 * PI) + t * 0.28
        val v = rt.freq[i].coerceIn(0.05f, 1f)
        val r1 = baseR + v * maxSpoke * (1f + m.beat * 0.25f)
        val ca = cos(ang).toFloat()
        val sa = sin(ang).toFloat()
        val col = when (i % 3) {
            0 -> pal3
            1 -> pal2
            else -> pal1
        }
        drawLine(
            color = col.copy(alpha = 0.45f + v * 0.55f),
            start = Offset(cx + ca * baseR, cy + sa * baseR),
            end = Offset(cx + ca * r1, cy + sa * r1),
            strokeWidth = max(2.5f, minD * 0.01f),
            cap = StrokeCap.Round,
        )
    }
    drawCircle(Color.White.copy(alpha = 0.22f + m.beat * 0.3f), baseR, Offset(cx, cy), style = Stroke(2f))
}

private fun DrawScope.drawAurora(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    val bands = listOf(Triple(pal1, 0.34f, 0f), Triple(pal3, 0.50f, 1.8f), Triple(pal2, 0.66f, 3.4f))
    bands.forEachIndexed { idx, (col, yFrac, ph) ->
        val path = Path()
        val steps = 40
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val f = rt.freq[(i * AudioSpectrumBus.BAND_COUNT / (steps + 1)).coerceIn(0, 63)]
            val y = h * yFrac + sin(u * 5.5f + t * (0.7f + idx * 0.25f) + ph) * h * 0.12f - f * h * 0.22f
            if (i == 0) path.moveTo(u * w, y) else path.lineTo(u * w, y)
        }
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                0f to col.copy(alpha = 0.42f + m.energy * 0.25f),
                0.7f to col.copy(alpha = 0.08f),
                1f to Color.Transparent,
            ),
        )
    }
}

private fun DrawScope.drawVinylGrooves(m: AudioFrameMetrics, angleDeg: Float, pal1: Color, pal2: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.44f
    // Ambient beat halo behind record
    drawCircle(
        brush = Brush.radialGradient(listOf(pal1.copy(alpha = 0.32f + m.beat * 0.28f), Color.Transparent), Offset(cx, cy), maxR * 1.18f),
        radius = maxR * 1.18f,
        center = Offset(cx, cy),
    )
    // Wax platter
    drawCircle(Color(0xFF0E1016), maxR, Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.16f), maxR, Offset(cx, cy), style = Stroke(2f))
    // Concentric micro-grooves pulsing subtly with bass
    for (g in 1..14) {
        val r = maxR * (0.46f + g * 0.036f)
        drawCircle(
            Color.White.copy(alpha = 0.045f + (if (g % 4 == 0) m.energy * 0.08f else 0f)),
            r,
            Offset(cx, cy),
            style = Stroke(1f),
        )
    }
    // Rotating specular light sheen
    val rad = angleDeg * (PI / 180.0)
    val sx = cx + cos(rad).toFloat() * maxR * 0.72f
    val sy = cy + sin(rad).toFloat() * maxR * 0.72f
    drawLine(pal2.copy(alpha = 0.18f), Offset(cx, cy), Offset(sx, sy), maxR * 0.18f, cap = StrokeCap.Round)
    // Tonearm
    val pivot = Offset(size.width * 0.86f, size.height * 0.16f)
    val stylus = Offset(cx + maxR * 0.62f, cy + maxR * 0.22f)
    drawLine(Color.White.copy(alpha = 0.55f), pivot, stylus, 5f, cap = StrokeCap.Round)
    drawCircle(Color.White.copy(alpha = 0.75f), 10f, pivot)
    drawCircle(pal2, 5f, stylus)
}

private fun DrawScope.drawTunnel(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.52f
    val ringCount = 14
    for (i in 0 until ringCount) {
        val prog = ((i.toFloat() / ringCount) + t * (0.28f + m.energy * 0.22f)) % 1f
        val r = prog.pow(1.6f) * maxR
        val band = rt.freq[(i * 4) % AudioSpectrumBus.BAND_COUNT]
        val col = if (i % 3 == 0) pal3 else if (i % 2 == 0) pal2 else pal1
        drawCircle(
            color = col.copy(alpha = ((1f - prog) * 0.2f + prog * 0.7f) * (0.45f + band * 0.55f)),
            radius = r * (1f + band * 0.12f),
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f + prog * 4f + m.beat * 1.5f),
        )
    }
}

private fun DrawScope.drawPlasma(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    val blobs = listOf(
        Triple(pal1, 0.32f + 0.24f * sin(t * 0.9f), 0.42f + 0.22f * cos(t * 0.7f)),
        Triple(pal2, 0.68f + 0.20f * cos(t * 1.1f), 0.56f + 0.24f * sin(t * 0.8f)),
        Triple(pal3, 0.50f + 0.25f * sin(t * 0.6f + 2f), 0.35f + 0.25f * cos(t * 1.2f)),
    )
    blobs.forEachIndexed { idx, (col, fx, fy) ->
        val band = when (idx) {
            0 -> m.bass
            1 -> m.mid
            else -> m.treble
        }
        val r = min(w, h) * (0.26f + band * 0.28f + m.beat * 0.08f)
        val c = Offset(w * fx, h * fy)
        drawCircle(
            brush = Brush.radialGradient(listOf(col.copy(alpha = 0.68f), col.copy(alpha = 0.14f), Color.Transparent), c, r),
            radius = r,
            center = c,
        )
    }
}

private fun DrawScope.drawStardust(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    rt.particles.forEachIndexed { idx, p ->
        val col = if (idx % 3 == 0) pal3 else if (idx % 2 == 0) pal2 else Color.White
        val r = p.sz * (1f + m.beat * 0.8f + rt.freq[idx % 64] * 1.2f)
        drawCircle(col.copy(alpha = (p.life * 0.85f).coerceIn(0f, 1f)), r, Offset(p.x * w, p.y * h))
    }
}

private fun DrawScope.drawBattery(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    // Rising embers
    rt.particles.forEachIndexed { idx, p ->
        val col = if (idx % 2 == 0) pal2 else pal3
        drawCircle(col.copy(alpha = (p.life * 0.9f).coerceIn(0f, 1f)), p.sz * (1f + m.energy), Offset(p.x * w, p.y * h))
    }
    // Electric lightning arcs across center on mid/beat energy
    val arcCount = if (m.beat > 0.3f) 3 else 1
    for (a in 0 until arcCount) {
        val path = Path()
        val segs = 16
        for (i in 0..segs) {
            val u = i.toFloat() / segs
            val jitter = sin(u * 23f + t * 14f + a * 3f) * h * (0.08f + m.treble * 0.18f)
            val x = u * w
            val y = h * (0.35f + a * 0.15f) + jitter
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, pal3.copy(alpha = 0.35f + m.beat * 0.35f), style = Stroke(8f, cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(2f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawDotPlane(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color) {
    val w = size.width
    val h = size.height
    val cols = 16
    val rows = 10
    for (r in 0 until rows) {
        val depth = (r + 1f) / rows
        val yBase = h * (0.30f + depth * depth * 0.62f)
        val spread = 0.38f + depth * 0.62f
        for (c in 0 until cols) {
            val u = (c.toFloat() / (cols - 1)) - 0.5f
            val x = w * 0.5f + u * w * spread
            val band = rt.freq[(c * 4 + r) % AudioSpectrumBus.BAND_COUNT]
            val lift = (band * 56f + sin(t * 2f + c * 0.5f + r * 0.6f) * 8f) * depth
            val rad = (2.2f + band * 5.5f + m.beat * 2f) * depth
            drawCircle(
                color = if (band > 0.55f) pal2.copy(alpha = 0.9f) else pal1.copy(alpha = 0.35f + depth * 0.5f),
                radius = rad,
                center = Offset(x, yBase - lift),
            )
        }
    }
}

private fun DrawScope.drawAlchemy(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = min(size.width, size.height) * 0.25f
    listOf(pal1 to 0f, pal2 to 2.1f, pal3 to 4.2f).forEachIndexed { idx, (col, ph) ->
        val path = Path()
        val pts = 48
        for (i in 0..pts) {
            val a = (i.toFloat() / pts) * (2.0 * PI)
            val b = rt.freq[(i + idx * 12) % AudioSpectrumBus.BAND_COUNT]
            val r = baseR * (0.82f + 0.32f * sin(a * 3.0 + t * 1.4 + ph).toFloat() + b * 0.55f + m.beat * 0.15f)
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, col.copy(alpha = 0.24f))
        drawPath(path, col.copy(alpha = 0.82f), style = Stroke(3f))
    }
}

private fun DrawScope.drawFountain(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val baseY = size.height * 0.88f
    val h = size.height
    rt.particles.forEachIndexed { idx, p ->
        val age = (1f - p.life).coerceIn(0f, 1f)
        val spread = ((idx % 21) - 10) / 10f
        val x = cx + spread * size.width * 0.42f * age
        val peakH = h * (0.45f + m.bass * 0.38f + m.beat * 0.15f)
        val y = baseY - (4f * age * (1f - age)) * peakH
        val col = if (idx % 3 == 0) pal3 else if (idx % 2 == 0) pal2 else pal1
        drawCircle(col.copy(alpha = (1f - age * 0.8f).coerceIn(0f, 1f)), p.sz * (1f + m.energy * 0.8f), Offset(x, y))
    }
}

private fun DrawScope.drawKaleidoscope(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.44f
    val folds = 8
    for (f in 0 until folds) {
        val baseAng = (f.toFloat() / folds) * (2.0 * PI) + t * 0.35
        for (i in 0 until 12) {
            val b = rt.freq[(i * 5) % AudioSpectrumBus.BAND_COUNT]
            val r = (i + 1f) / 12f * maxR * (0.75f + b * 0.45f)
            val ang = baseAng + sin(t + i * 0.4f) * 0.22
            val x = cx + cos(ang).toFloat() * r
            val y = cy + sin(ang).toFloat() * r
            val col = if (i % 3 == 0) pal3 else if (i % 2 == 0) pal2 else pal1
            drawCircle(col.copy(alpha = 0.38f + b * 0.6f), 3f + b * 11f + m.beat * 4f, Offset(x, y))
        }
    }
}

private fun DrawScope.drawWarp(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.62f
    rt.particles.forEachIndexed { idx, p ->
        val r0 = (1f - p.z) * maxR
        val r1 = min(maxR * 1.1f, r0 + (12f + m.energy * 36f + m.beat * 28f) * (1f - p.z))
        val ca = cos(p.angle)
        val sa = sin(p.angle)
        val col = if (idx % 3 == 0) pal3 else if (idx % 2 == 0) pal2 else Color.White
        drawLine(
            color = col.copy(alpha = (1f - p.z).coerceIn(0.1f, 1f)),
            start = Offset(cx + ca * r0, cy + sa * r0),
            end = Offset(cx + ca * r1, cy + sa * r1),
            strokeWidth = 1.5f + (1f - p.z) * 2.5f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawNebula(rt: DesktopVizRuntime, m: AudioFrameMetrics, t: Float, pal1: Color, pal2: Color, pal3: Color) {
    val w = size.width
    val h = size.height
    for (i in 0 until 6) {
        val x = w * (0.2f + 0.6f * (0.5f + 0.5f * sin(t * (0.25f + i * 0.08f) + i * 1.7f)))
        val y = h * (0.2f + 0.6f * (0.5f + 0.5f * cos(t * (0.22f + i * 0.07f) + i * 2.3f)))
        val b = rt.freq[(i * 10) % AudioSpectrumBus.BAND_COUNT]
        val r = min(w, h) * (0.25f + b * 0.26f)
        val col = if (i % 3 == 0) pal3 else if (i % 2 == 0) pal2 else pal1
        drawCircle(
            brush = Brush.radialGradient(listOf(col.copy(alpha = 0.34f + m.energy * 0.22f), Color.Transparent), Offset(x, y), r),
            radius = r,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawBloom(rt: DesktopVizRuntime, m: AudioFrameMetrics, pal1: Color, pal2: Color, pal3: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(size.width, size.height) * 0.52f
    for (rg in rt.rings) {
        val alpha = (1f - rg.r / 1.25f).coerceIn(0f, 1f)
        val col = if (rg.accent) pal3 else pal2
        val path = Path()
        val steps = 48
        for (i in 0..steps) {
            val a = (i.toFloat() / steps) * (2.0 * PI) + rg.rot
            val petal = 1f + 0.14f * sin(a * rg.petals).toFloat()
            val r = rg.r * maxR * petal
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, col.copy(alpha = alpha * 0.85f), style = Stroke(width = 2.5f + m.beat * 3f))
        drawPath(path, pal1.copy(alpha = alpha * 0.16f))
    }
}

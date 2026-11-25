package net.ssync.pairing.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors

/**
 * True when running on Android TV / Google TV / NVIDIA Shield TV (Leanback).
 * Provided at the app root in MainActivity so every screen can adapt layout,
 * sidebar auto-collapse, HUD focus, and remote key handling cleanly.
 */
val LocalIsTv = compositionLocalOf { false }

fun Context.isAndroidTv(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return isTvMode || hasLeanback
}

/**
 * Universal Android TV focus indication installed via `LocalIndication` in MainActivity.
 *
 * Guarantees that EVERY SINGLE clickable control in the entire app — across all 30+ screens,
 * dialogs, bottom sheets, calendar items, watchlist cards, music tracks, and settings rows —
 * automatically displays an unmistakable 10-foot pure-white focus ring + subtle wash when
 * focused by a TV remote, making it impossible to lose your cursor.
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class TvFocusIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    private var isFocused = false
    private var isPressed = false
    private val focusAlpha = Animatable(0f)
    private var fadeJob: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            var focusCount = 0
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount++
                    is FocusInteraction.Unfocus -> focusCount = (focusCount - 1).coerceAtLeast(0)
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = (pressCount - 1).coerceAtLeast(0)
                }
                val newFocused = focusCount > 0
                val newPressed = pressCount > 0
                if (newFocused != isFocused || newPressed != isPressed) {
                    isFocused = newFocused
                    isPressed = newPressed
                    fadeJob?.cancel()
                    fadeJob = coroutineScope.launch {
                        focusAlpha.animateTo(
                            targetValue = if (newFocused || newPressed) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = if (newFocused || newPressed) 140 else 180,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                    invalidateDraw()
                }
            }
        }
    }

    override fun onDetach() {
        fadeJob?.cancel()
        fadeJob = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = focusAlpha.value
        if (a <= 0.01f) return

        val minDim = minOf(size.width, size.height)
        val radiusPx = when {
            minDim <= 56.dp.toPx() -> minDim / 2f
            else -> 12.dp.toPx()
        }
        val corner = CornerRadius(radiusPx, radiusPx)
        val inset = 1.dp.toPx()
        val rectSize = Size(
            (size.width - inset * 2f).coerceAtLeast(0f),
            (size.height - inset * 2f).coerceAtLeast(0f),
        )
        val topLeft = Offset(inset, inset)

        // Refined Apple TV+ / Netflix surface color shift + crisp hairline rim (no chunky black halo box)
        val fillColor = if (isPressed) {
            SoulSyncColors.Accent.copy(alpha = 0.34f * a)
        } else {
            SoulSyncColors.AccentLight.copy(alpha = 0.14f * a)
        }
        drawRoundRect(
            color = fillColor,
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f * a),
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = (if (isPressed) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.88f)).copy(alpha = a),
            topLeft = topLeft,
            size = rectSize,
            cornerRadius = corner,
            style = Stroke(width = 1.75.dp.toPx()),
        )
    }
}

/**
 * Zero-recomposition Android TV / D-pad spatial focus enhancement for already-clickable controls.
 *
 * Implemented as a pure `Modifier.Node` (no `Modifier.composed`, zero allocations on scroll,
 * zero recomposition on D-pad movement): spring scale lift runs entirely inside `placeWithLayer`
 * on the GraphicsLayer / RenderThread for locked 60fps Disney+/Netflix-grade smoothness.
 */
fun Modifier.dpadFocusable(
    shape: Shape = RoundedCornerShape(10.dp),
    scaleOnFocus: Boolean = false,
    onLightSurface: Boolean = false,
): Modifier = this then DpadFocusElement(
    shape = shape,
    scaleOnFocus = scaleOnFocus,
    onLightSurface = onLightSurface,
)

private data class DpadFocusElement(
    val shape: Shape,
    val scaleOnFocus: Boolean,
    val onLightSurface: Boolean,
) : ModifierNodeElement<DpadFocusNode>() {
    override fun create(): DpadFocusNode = DpadFocusNode(
        shape = shape,
        scaleOnFocus = scaleOnFocus,
        onLightSurface = onLightSurface,
    )

    override fun update(node: DpadFocusNode) {
        node.shape = shape
        node.scaleOnFocus = scaleOnFocus
        node.onLightSurface = onLightSurface
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dpadFocusable"
        properties["scaleOnFocus"] = scaleOnFocus
        properties["onLightSurface"] = onLightSurface
    }
}

/**
 * Global Disney+/Netflix-grade TV smooth pivot scrolling spec installed via
 * `LocalBringIntoViewSpec` in MainActivity.
 *
 * - Glides focused items smoothly toward a steady 14% leading pivot with damped
 *   `FastOutSlowInEasing` (zero rigid edge-snapping).
 * - Applies a 14px deadzone so horizontal browsing across a row NEVER causes
 *   micro vertical screen wobble.
 */
@OptIn(ExperimentalFoundationApi::class)
object TvSmoothBringIntoViewSpec : BringIntoViewSpec {
    @Deprecated("Overrides deprecated BringIntoViewSpec.scrollAnimationSpec")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 280,
        easing = FastOutSlowInEasing,
    )

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        if (containerSize <= 0f) return 0f
        if (size >= containerSize) {
            return if (offset < 0f && offset + size > containerSize) 0f else offset
        }
        // Safe-zone smooth glide:
        // - If the focused item is already comfortably inside the viewport safe zone,
        //   return 0f (NEVER yank the Hero off-screen or wobble rows vertically!).
        // - Only glide smoothly when the focused item approaches or crosses the 12% edge margin.
        val margin = (containerSize * 0.12f).coerceAtMost((containerSize - size) * 0.5f)
        val delta = when {
            offset < margin -> offset - margin
            offset + size > containerSize - margin -> (offset + size) - (containerSize - margin)
            else -> 0f
        }
        return if (kotlin.math.abs(delta) < 12f) 0f else delta
    }
}

private class DpadFocusNode(
    var shape: Shape,
    var scaleOnFocus: Boolean,
    var onLightSurface: Boolean,
) : Modifier.Node(),
    FocusEventModifierNode,
    LayoutModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {

    private var isFocused = false
    private val scaleAnim = Animatable(1f)
    private val focusAlphaAnim = Animatable(0f)
    private var animJob: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        val nowFocused = focusState.isFocused || focusState.hasFocus
        if (nowFocused == isFocused) return
        isFocused = nowFocused
        invalidatePlacement()
        invalidateDraw()
        animJob?.cancel()
        animJob = coroutineScope.launch {
            launch {
                focusAlphaAnim.animateTo(
                    targetValue = if (nowFocused) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (nowFocused) 150 else 180,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            if (scaleOnFocus) {
                // Subtle, silky 1.028x cinema lift with zero overshoot so cards never
                // jump row bounds or shift screen height while browsing.
                scaleAnim.animateTo(
                    targetValue = if (nowFocused) 1.028f else 1f,
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }

    override fun onDetach() {
        animJob?.cancel()
        animJob = null
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val z = if (isFocused) 4f else 0f
            if (scaleOnFocus) {
                placeable.placeWithLayer(0, 0, zIndex = z) {
                    val s = scaleAnim.value
                    scaleX = s
                    scaleY = s
                }
            } else {
                placeable.place(0, 0, zIndex = z)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = focusAlphaAnim.value
        if (a <= 0.01f) return
        val isTv = currentValueOf(LocalIsTv)
        val outline = shape.createOutline(size, layoutDirection, this)
        if (onLightSurface) {
            // Solid-white primary CTA: smooth SoulSync accent fill shift (zero floating outer ring)
            drawOutline(
                outline = outline,
                color = SoulSyncColors.Accent.copy(alpha = 0.28f * a),
            )
        } else if (isTv) {
            if (scaleOnFocus) {
                // Media cards (posters, episodes, albums): single clean 2dp luminous rim + smooth lift
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = 0.92f * a),
                    style = Stroke(width = 2.dp.toPx()),
                )
            } else {
                // Buttons, pills, chips, rows: pure native surface color shift (zero extra outline stroke / zero double border)
                drawOutline(
                    outline = outline,
                    color = SoulSyncColors.Accent.copy(alpha = 0.34f * a),
                )
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = 0.10f * a),
                )
            }
        } else {
            drawOutline(
                outline = outline,
                color = SoulSyncColors.Accent.copy(alpha = 0.22f * a),
            )
        }
    }
}

/**
 * Convenience focus modifier for media cards (posters, episode thumbs, square covers):
 * adds a smooth 10-foot TV scale lift when focused by D-pad.
 */
fun Modifier.dpadCardFocusable(
    shape: Shape = RoundedCornerShape(10.dp),
): Modifier = dpadFocusable(shape = shape, scaleOnFocus = true, onLightSurface = false)

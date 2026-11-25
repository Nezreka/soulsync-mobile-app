package net.ssync.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * The card a TV page's spotlight shows is the one focus has RESTED on, not
 * every card it passed over.
 *
 * Each focused card used to become the spotlight at once: a new full-screen
 * backdrop decoded and cross-faded, the ambient colour re-animated for half a
 * second, the title, logo and overview swapped and the whole page recomposed,
 * on every press of the d-pad. Holding a direction along a row did all of
 * that five times a second, which is what made moving around feel heavy on
 * a Shield. Focus itself (the white border, the scale) still follows the
 * press at once; only the spotlight waits for focus to stop.
 *
 * The returned function takes the newly focused card and causes no
 * recomposition until it settles.
 */
@Composable
internal fun <T> rememberSettledFocus(restMs: Long = 170L, onSettled: (T) -> Unit): (T) -> Unit {
    val flow = remember {
        MutableSharedFlow<T>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val latest = rememberUpdatedState(onSettled)
    LaunchedEffect(flow) {
        flow.collectLatest { value ->
            delay(restMs)
            latest.value(value)
        }
    }
    return remember(flow) { { value: T -> flow.tryEmit(value); Unit } }
}

package net.ssync.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * true while this screen is started (on screen). the polling loops (home
 * refresh, presence, a watch party room) checked nothing and ran all night
 * in the background on a phone's battery and data.
 */
@Composable
fun rememberOnScreen(): State<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = lifecycle.currentStateFlow.collectAsState()
    return object : State<Boolean> {
        override val value: Boolean get() = state.value.isAtLeast(Lifecycle.State.STARTED)
    }
}

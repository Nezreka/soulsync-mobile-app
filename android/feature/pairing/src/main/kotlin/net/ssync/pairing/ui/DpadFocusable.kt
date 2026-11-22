package net.ssync.pairing.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import net.ssync.pairing.theme.SoulSyncColors

/**
 * Android TV / D-pad spatial focus hook.
 * Draws a high-contrast SoulSync accent focus ring whenever the control has D-pad focus.
 */
fun Modifier.dpadFocusable(
    shape: Shape = RoundedCornerShape(10.dp),
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { isFocused = it.isFocused }
        .focusable()
        .then(
            if (isFocused) {
                Modifier.border(width = 2.dp, color = SoulSyncColors.AccentLight, shape = shape)
            } else {
                Modifier
            },
        )
}

package net.ssync.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ssync.connect.PairingLink
import net.ssync.connect.PairingLinkAction
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable

/** a scanned pairing link on a phone that's already paired */
data class LinkPrompt(val link: PairingLink, val action: PairingLinkAction)

/**
 * same server: open the player (the code is never used) or pair again.
 * another server: switch or cancel. the app keeps one server, and the old
 * pairing stays until the new one is done.
 */
@Composable
fun PairingLinkPromptDialog(
    prompt: LinkPrompt,
    currentServer: String,
    onKeep: () -> Unit,
    onPair: () -> Unit,
) {
    val same = prompt.action == PairingLinkAction.AlreadyPaired
    val newServer = prompt.link.formattedFingerprint?.let { "the server $it" } ?: "the server in this QR code"
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = SoulSyncColors.Surface,
        title = {
            Text(
                if (same) "Already connected" else "Switch servers?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                if (same) {
                    "This phone is already connected to $currentServer. Pair again only if you want a fresh " +
                        "pairing, say for another profile."
                } else {
                    "This phone is connected to $currentServer. Switch to $newServer? " +
                        "$currentServer stays paired until the new one is done."
                },
                color = SoulSyncColors.TextSoft,
            )
        },
        confirmButton = {
            TextButton(onClick = if (same) onKeep else onPair, modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp))) {
                Text(if (same) "Open the player" else "Switch", color = SoulSyncColors.AccentLight, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = if (same) onPair else onKeep, modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp))) {
                Text(if (same) "Pair again" else "Cancel", color = SoulSyncColors.TextSoft)
            }
        },
    )
}

/** a link that couldn't be read, on a phone that's already paired */
@Composable
fun PairingLinkProblemDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoulSyncColors.Surface,
        title = { Text("That link didn't work", color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text(message, color = SoulSyncColors.TextSoft) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.dpadFocusable(RoundedCornerShape(999.dp))) {
                Text("OK", color = SoulSyncColors.AccentLight, fontWeight = FontWeight.Bold)
            }
        },
    )
}

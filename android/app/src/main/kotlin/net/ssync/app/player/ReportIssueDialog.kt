package net.ssync.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

private data class IssuePreset(val key: String, val label: String, val videoOnly: Boolean = false)

private val ISSUE_PRESETS = listOf(
    IssuePreset("playback", "Won't play / corrupted"),
    IssuePreset("audio", "Audio / sync issue"),
    IssuePreset("subtitles", "Subtitles missing / sync", videoOnly = true),
    IssuePreset("quality", "Poor quality"),
    IssuePreset("metadata", "Wrong media / metadata"),
    IssuePreset("other", "Other"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportIssueDialog(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    title: String,
    subtitle: String = "",
    positionSec: Int = 0,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isVideo = itemType in setOf("movie", "episode", "youtube", "music_video", "commercial")
    val presets = remember(isVideo) { ISSUE_PRESETS.filter { isVideo || !it.videoOnly } }
    var selectedCategory by remember { mutableStateOf("playback") }
    var note by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = Color(0xFF161822),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFD0D6E4),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Report an issue", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = title,
                    fontSize = 13.sp,
                    color = SoulSyncColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            if (done) {
                Text(
                    text = "Issue submitted — thanks for flagging it!",
                    color = Color(0xFF8FF0B3),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "WHAT'S WRONG?",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SoulSyncColors.TextSoft,
                        letterSpacing = 0.8.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { preset ->
                            val active = selectedCategory == preset.key
                            Text(
                                text = preset.label,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) Color.White else SoulSyncColors.TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (active) SoulSyncColors.Accent.copy(alpha = 0.28f)
                                        else Color.White.copy(alpha = 0.06f),
                                    )
                                    .border(
                                        1.dp,
                                        if (active) SoulSyncColors.Accent else Color.White.copy(alpha = 0.14f),
                                        RoundedCornerShape(999.dp),
                                    )
                                    .clickable(enabled = !submitting) { selectedCategory = preset.key }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Details (optional)", fontSize = 12.sp) },
                        placeholder = { Text("Timestamp, language, or what went wrong…", fontSize = 12.sp) },
                        singleLine = false,
                        maxLines = 3,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!errorMsg.isNullOrBlank()) {
                        Text(errorMsg!!, color = Color(0xFFFF7B7B), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!done) {
                Button(
                    enabled = !submitting,
                    colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent),
                    onClick = {
                        submitting = true
                        errorMsg = null
                        scope.launch {
                            val res = api.reportIssue(
                                itemType = itemType,
                                itemId = itemId,
                                title = title,
                                subtitle = subtitle,
                                category = selectedCategory,
                                note = note.trim(),
                                positionSec = positionSec.coerceAtLeast(0),
                            )
                            submitting = false
                            if (res is MusicApiResult.Success) {
                                done = true
                                delay(900L)
                                onDismiss()
                            } else {
                                errorMsg = "Couldn't submit issue. Try again."
                            }
                        }
                    },
                ) {
                    Text(if (submitting) "Submitting…" else "Submit issue", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            if (!done) {
                TextButton(enabled = !submitting, onClick = onDismiss) {
                    Text("Cancel", color = SoulSyncColors.TextSecondary)
                }
            }
        },
    )
}

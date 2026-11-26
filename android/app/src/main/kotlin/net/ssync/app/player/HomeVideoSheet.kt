package net.ssync.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.HomeAlbum
import net.ssync.player.HomeFrame
import net.ssync.player.HomeVideo
import net.ssync.player.HomeVideoDetail
import net.ssync.player.HomeVideoText
import net.ssync.player.HomeVideosApiClient
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * a clip's sheet (long-press on a phone): play, download, and for an admin
 * the edit form from the approved mockup: name, date filmed, picture, notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeVideoSheet(
    video: HomeVideo,
    home: HomeVideosApiClient,
    api: PlayerApiClient,
    isAdmin: Boolean,
    onPlay: (HomeVideo) -> Unit,
    /** the saved clip, and whether its date moved */
    onSaved: (HomeVideo, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(video.id) { mutableStateOf<HomeVideoDetail?>(null) }
    var loadError by remember(video.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(video.id) {
        when (val r = home.detail(video.id)) {
            is MusicApiResult.Success -> detail = r.data
            is MusicApiResult.Error -> loadError = r.message
            MusicApiResult.DeviceRevoked -> loadError = "This device was signed out."
        }
    }
    // the list's copy until the full one comes (it has the notes and the frames)
    val v = detail?.video?.let { d ->
        d.copy(
            thumbUrl = d.thumbUrl ?: video.thumbUrl,
            album = d.album.ifBlank { video.album },
            albumKey = d.albumKey.ifBlank { video.albumKey },
        )
    } ?: video
    val title = HomeVideoText.title(v)
    val thumb = homeThumb(home, v)
    // the server says who may edit; the admin role until it has
    val canEdit = detail?.video?.canEdit ?: isAdmin

    // the form, filled once the full clip is here
    var name by remember(detail) { mutableStateOf(v.title) }
    var nameAuto by remember(detail) { mutableStateOf(false) }
    val startAt = remember(detail) { HomeVideoText.filmed(v.filmedAt) }
    var at by remember(detail) { mutableStateOf(startAt) }
    var dateAuto by remember(detail) { mutableStateOf(false) }
    var notes by remember(detail) { mutableStateOf(v.notes) }
    val frames: List<HomeFrame> = remember(detail) {
        detail?.frames?.takeIf { it.isNotEmpty() }?.take(4)?.map { it.copy(url = home.absolute(it.url) ?: it.url) }
            ?: if (v.durationS > 0) listOf(0.1, 0.3, 0.5, 0.7).map { HomeFrame(v.durationS * it, home.frameUrl(v.id, v.durationS * it)) } else emptyList()
    }
    // the picture in use: the person's frame, else the automatic one (the first candidate)
    val startFrame = remember(detail, frames) {
        val t = v.thumbAtS
        if (t == null) 0 else frames.indices.minByOrNull { kotlin.math.abs(frames[it].atS - t) }?.takeIf { kotlin.math.abs(frames[it].atS - t) < 1.0 }
    }
    var frame by remember(detail, frames) { mutableStateOf(startFrame) }
    var busy by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<LocalDate?>(null) }

    val changes: Map<String, JsonElement> = buildMap {
        when {
            nameAuto -> put("title", JsonNull)
            name.trim() != v.title.trim() && name.isNotBlank() -> put("title", JsonPrimitive(name.trim()))
        }
        when {
            dateAuto -> put("filmed_at", JsonNull)
            at != null && at != startAt -> put("filmed_at", JsonPrimitive(HomeVideoText.isoLocal(at!!)))
        }
        val f = frame
        if (f != null && f != startFrame) frames.getOrNull(f)?.let { put("thumb_at_s", JsonPrimitive(Math.round(it.atS * 10) / 10.0)) }
        if (notes.trim() != v.notes.trim()) put("notes", if (notes.isBlank()) JsonNull else JsonPrimitive(notes.trim()))
    }

    fun save() {
        if (busy || changes.isEmpty()) return
        busy = true
        saveError = null
        val sent = changes
        scope.launch {
            when (val r = home.update(v.id, sent)) {
                is MusicApiResult.Success -> {
                    val fresh = r.data ?: (home.detail(v.id) as? MusicApiResult.Success)?.data?.video
                    val saved = (fresh ?: v.copy(
                        title = if (nameAuto) v.title else name.trim().ifBlank { v.title },
                        filmedAt = at?.let { HomeVideoText.isoLocal(it) } ?: v.filmedAt,
                        notes = notes.trim(),
                    )).let { it.copy(album = it.album.ifBlank { v.album }, albumKey = it.albumKey.ifBlank { v.albumKey }) }
                    // the server's thumb_url carries ?v= for the frame it shows, so a new pick is a new url;
                    // one still being made comes back null and the page's watcher picks it up
                    busy = false
                    onSaved(saved, "filmed_at" in sent)
                    android.widget.Toast.makeText(context, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                is MusicApiResult.Error -> { busy = false; saveError = r.message }
                MusicApiResult.DeviceRevoked -> { busy = false; saveError = "This device was signed out." }
            }
        }
    }

    AdaptiveTvSheet(onDismissRequest = onDismiss, containerColor = HomeColors.Raise) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // what it is
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HomeThumb(v, home, Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(HomeVideoText.date(v), HomeVideoText.duration(v.durationS)).filter { it.isNotBlank() }.joinToString(" · "),
                        color = HomeColors.Muted, fontSize = 12.5.sp,
                    )
                    if (v.album.isNotBlank()) {
                        Text(HomeVideoText.albumName(HomeAlbum(v.albumKey, v.album)), color = HomeColors.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(if (v.missing) HomeColors.Raise2 else Color.White)
                        .clickable(enabled = !v.missing) { onPlay(v) }.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (v.missing) HomeColors.Muted else SoulSyncColors.BgPrimary, modifier = Modifier.size(18.dp))
                    Text(
                        when {
                            v.missing -> "File missing"
                            v.progressFraction != null -> "Resume"
                            else -> "Play"
                        },
                        color = if (v.missing) HomeColors.Muted else SoulSyncColors.BgPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                    )
                }
                if (!v.missing) {
                    OfflineDownloadButton(
                        api = api,
                        itemType = "home",
                        itemId = v.id,
                        title = title,
                        subtitle = HomeVideoText.date(v),
                        artworkUrl = thumb,
                        isVideo = true,
                        groupType = if (v.albumKey.isNotBlank()) "home_album" else "",
                        groupId = v.albumKey,
                        groupTitle = HomeVideoText.albumName(HomeAlbum(v.albumKey, v.album)),
                    )
                }
            }
            loadError?.let { Text(it, color = DlColors.Bad, fontSize = 12.5.sp) }

            if (!canEdit) {
                if (v.notes.isNotBlank()) {
                    Field("Notes") { Text(v.notes, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp) }
                }
                Spacer(Modifier.size(8.dp))
                return@Column
            }

            Text("Edit video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            if (detail == null && loadError == null) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SoulSyncColors.AccentLight, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                return@Column
            }

            Field(
                "Name",
                hint = when {
                    nameAuto -> "The name from the file comes back when you save."
                    v.fileName.isNotBlank() -> "Was “${v.fileName}”. Your name is kept, even after a rescan."
                    else -> "Your name is kept, even after a rescan."
                },
                reset = if (v.userTitle && !nameAuto) "Use the file name" else null,
                onReset = { nameAuto = true; name = "" },
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameAuto = false },
                    singleLine = true,
                    placeholder = { Text(if (nameAuto) v.autoTitle.ifBlank { "From the file name" } else "A name for this video", color = HomeColors.Muted) },
                    shape = RoundedCornerShape(10.dp),
                    colors = homeFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Field(
                "Date filmed",
                hint = if (dateAuto) "The video's own date comes back when you save." else HomeVideoText.dateHint(v.filmedSource),
                reset = if (v.userFilmedAt && !dateAuto) "Use the video's date" else null,
                onReset = { dateAuto = true; at = startAt },
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SoulSyncColors.BgPrimary)
                        .border(1.dp, HomeColors.Line2, RoundedCornerShape(10.dp))
                        .clickable { pickDate = true }.padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (dateAuto) "Automatic" else HomeVideoText.dateTime(at),
                        color = Color.White, fontSize = 14.5.sp, modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Change the date", tint = HomeColors.Muted, modifier = Modifier.size(18.dp))
                }
            }

            if (frames.isNotEmpty()) {
                Field("Picture", hint = "Pick a frame from the video.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        frames.forEachIndexed { i, f ->
                            val on = frame == i
                            Box(
                                Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                                    .border(2.dp, if (on) SoulSyncColors.AccentLight else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { frame = i },
                            ) {
                                SoulSyncArtwork(url = f.url, contentDescription = "Frame at ${HomeVideoText.duration(f.atS).ifBlank { "0:00" }}", fallbackIcon = Icons.Default.Videocam, maxPx = 320, modifier = Modifier.fillMaxSize())
                                if (on) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).clip(CircleShape).background(SoulSyncColors.AccentLight),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Default.Check, contentDescription = null, tint = SoulSyncColors.OnAccent, modifier = Modifier.size(11.dp)) }
                                }
                            }
                        }
                    }
                }
            }

            Field("Notes") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    minLines = 3,
                    placeholder = { Text("Who's in it, where it was…", color = HomeColors.Muted) },
                    shape = RoundedCornerShape(10.dp),
                    colors = homeFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            saveError?.let { Text(it, color = DlColors.Bad, fontSize = 12.5.sp) }
            val can = changes.isNotEmpty() && !busy
            Box(
                Modifier.fillMaxWidth().clip(CircleShape)
                    .background(if (can) SoulSyncColors.AccentLight else HomeColors.Raise2)
                    .clickable(enabled = can) { save() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = SoulSyncColors.OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(if (changes.isEmpty()) "Nothing to save" else "Save", color = if (can) SoulSyncColors.OnAccent else HomeColors.Muted, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.size(6.dp))
        }
    }

    if (pickDate) {
        val initial = (at ?: LocalDateTime.now()).toLocalDate()
        val ds = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = ds.selectedDateMillis
                    pickDate = false
                    if (ms != null) pickTime = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = ds) }
    }
    pickTime?.let { day ->
        val ts = rememberTimePickerState(initialHour = at?.hour ?: 12, initialMinute = at?.minute ?: 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            title = { Text("What time?") },
            text = { TimePicker(state = ts) },
            confirmButton = {
                TextButton(onClick = {
                    at = day.atTime(ts.hour, ts.minute, at?.second ?: 0)
                    dateAuto = false
                    pickTime = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    hint: String? = null,
    reset: String? = null,
    onReset: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = HomeColors.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (reset != null) {
                Text(
                    reset,
                    color = SoulSyncColors.AccentLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onReset).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        content()
        if (hint != null) Text(hint, color = HomeColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

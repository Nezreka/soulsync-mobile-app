package net.ssync.app.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import org.json.JSONArray

/**
 * the search screen's parts, the same as the web player's search: a pill
 * field that's ready to type in, and before typing, recent searches and the
 * sections this profile has, a tap away.
 */

/** the last few searches on this device (the web keeps its own, per browser) */
object SearchHistory {
    private const val PREFS = "ss_player_search"
    private const val KEY = "recent"
    private const val MAX = 8

    fun read(context: Context): List<String> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun remember(context: Context, query: String): List<String> {
        val q = query.trim()
        if (q.length < 2) return read(context)
        val next = (listOf(q) + read(context).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        write(context, next)
        return next
    }

    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(list).toString()).apply()
    }
}

/** one place a search can go instead: a section of the player */
data class BrowseTarget(val label: String, val icon: ImageVector, val destination: PlayerDestination)

fun browseTargets(hasVideo: Boolean, hasMusic: Boolean, hasPodcasts: Boolean, hasBooks: Boolean): List<BrowseTarget> =
    buildList {
        if (hasVideo) {
            add(BrowseTarget("Movies", Icons.Default.Movie, PlayerDestination.Movies))
            add(BrowseTarget("TV", Icons.Default.Tv, PlayerDestination.Tv))
            add(BrowseTarget("YouTube", Icons.Default.SmartDisplay, PlayerDestination.YouTube))
        }
        if (hasMusic) add(BrowseTarget("Music", Icons.Default.MusicNote, PlayerDestination.Music))
        if (hasPodcasts) add(BrowseTarget("Podcasts", Icons.Default.Podcasts, PlayerDestination.Podcasts))
        if (hasBooks) add(BrowseTarget("Audiobooks", Icons.Default.MenuBook, PlayerDestination.Audiobooks))
    }

/** the search field: a filled pill, the keyboard up as the screen opens */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focus.requestFocus() }
    }
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(SoulSyncColors.PanelSolid)
            .border(1.dp, SoulSyncColors.LineStrong, CircleShape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text("Search everything", color = SoulSyncColors.TextSecondary, fontSize = 16.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = SoulSyncColors.TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(SoulSyncColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = SoulSyncColors.TextSoft, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

/** before typing: recent searches, then the sections as tiles */
@Composable
fun SearchStart(
    recent: List<String>,
    onPickRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    browse: List<BrowseTarget>,
    onBrowse: (PlayerDestination) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (recent.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGroupLabel("Recent searches", Modifier.weight(1f))
                Text(
                    "Clear",
                    color = SoulSyncColors.TextSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).clickable { onClearRecent() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            recent.forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .dpadFocusable(RoundedCornerShape(10.dp))
                        .clickable { onPickRecent(r) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(r, color = SoulSyncColors.TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (browse.isNotEmpty()) {
            SearchGroupLabel("Browse")
            browse.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { t -> BrowseTile(t, Modifier.weight(1f)) { onBrowse(t.destination) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SearchGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = SoulSyncColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.1.sp,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun BrowseTile(target: BrowseTarget, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))))
            .border(1.dp, SoulSyncColors.Line, shape)
            .dpadFocusable(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(SoulSyncColors.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(target.icon, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(target.label, color = SoulSyncColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

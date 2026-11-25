package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.HomeLayout
import net.ssync.player.LayoutCatalog
import net.ssync.player.LayoutRow

private val ROW_STYLES = listOf("standard", "large", "wide", "ranked")

/** a style's name, the same words the web editor uses */
private fun styleLabel(style: String): String = when (style) {
    "standard" -> "Cards"
    "large" -> "Large cards"
    "wide" -> "Wide stills"
    "ranked" -> "Top 10"
    else -> style.replaceFirstChar { it.uppercase() }
}

/**
 * 1:1 port of SoulSync `/player` Home Layout Editor:
 * - Toggle Shared Layout across devices vs per-device
 * - Toggle Billboard Hero
 * - Reorder rows up/down, toggle on/off, switch row style (standard / large / wide / ranked Top 10)
 * - Add custom genre rows (`genre:movie:<genre>` / `genre:show:<genre>`)
 * - Reset to default
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeEditorSheet(
    initialLayout: HomeLayout,
    initialShared: Boolean,
    availableMovieGenres: List<String>,
    onSave: (HomeLayout, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    /** the server's rows for this page: their names, hints and the styles each can take */
    catalog: LayoutCatalog = LayoutCatalog(),
    title: String = "Customize Home",
    /** only Home has a billboard to switch */
    showBillboard: Boolean = true,
    availableShowGenres: List<String> = emptyList(),
) {
    val defs = remember(catalog) { catalog.rows.associateBy { it.id } }
    var draft by remember(initialLayout) { mutableStateOf(initialLayout) }
    var shared by remember(initialShared) { mutableStateOf(initialShared) }

    AdaptiveTvSheet(
        onDismissRequest = onDismiss,
        containerColor = SoulSyncColors.PanelSolid,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onReset,
                    border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(draft, shared) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Shared layout & Billboard toggles
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Shared layout across devices", style = MaterialTheme.typography.titleMedium)
                    Text("Use the same rows on every screen of this profile", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp)
                }
                Switch(checked = shared, onCheckedChange = { shared = it }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),)
            }
            if (showBillboard) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hero Billboard", style = MaterialTheme.typography.titleMedium)
                    Text("Auto-rotating spotlight at top of Home", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp)
                }
                Switch(
                    checked = draft.billboard,
                    onCheckedChange = { draft = draft.copy(billboard = it) },
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                )
            }

            // Add dynamic genre row chips
            val existingIds = draft.rows.map { it.id }.toSet()
            // every genre the library has, movies then shows, that isn't a row already
            val addableGenres = (
                availableMovieGenres.map { "genre:movie:$it" to "$it movies" } +
                    availableShowGenres.map { "genre:show:$it" to "$it shows" }
                ).filter { it.first !in existingIds }
            if (addableGenres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Add genre row", color = SoulSyncColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(addableGenres) { (genreId, genreLabel) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                // at the top, where the web editor puts it: a row added is a row wanted
                                draft = draft.copy(rows = listOf(LayoutRow(genreId, "standard", true)) + draft.rows)
                            },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp)) },
                            label = { Text(genreLabel) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SoulSyncColors.Card,
                                labelColor = SoulSyncColors.TextPrimary,
                            ),
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(draft.rows, key = { _, r -> r.id }) { idx, row ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = defs[row.id]?.label ?: labelForRowId(row.id),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    val hint = defs[row.id]?.hint.orEmpty()
                                    if (hint.isNotBlank()) {
                                        Text(hint, color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
                                    }
                                }
                                if (row.id.startsWith("genre:")) {
                                    IconButton(
                                        onClick = { draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != idx }) },
                                        modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove row", modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val list = draft.rows.toMutableList()
                                            val tmp = list[idx - 1]
                                            list[idx - 1] = list[idx]
                                            list[idx] = tmp
                                            draft = draft.copy(rows = list)
                                        }
                                    },
                                    enabled = idx > 0,
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (idx < draft.rows.lastIndex) {
                                            val list = draft.rows.toMutableList()
                                            val tmp = list[idx + 1]
                                            list[idx + 1] = list[idx]
                                            list[idx] = tmp
                                            draft = draft.copy(rows = list)
                                        }
                                    },
                                    enabled = idx < draft.rows.lastIndex,
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                                }
                                Switch(
                                    checked = row.on,
                                    onCheckedChange = { checked ->
                                        val list = draft.rows.toMutableList()
                                        list[idx] = row.copy(on = checked)
                                        draft = draft.copy(rows = list)
                                    },
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                                )
                            }
                            // only the styles this row can take (the server puts any other back)
                            val rowStyles = defs[row.id]?.styles?.takeIf { it.isNotEmpty() }
                                ?: if (row.id.startsWith("genre:") || defs.isEmpty()) ROW_STYLES else listOf(row.style)
                            if (rowStyles.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowStyles.forEach { st ->
                                    FilterChip(
                                        selected = row.style == st,
                                        onClick = {
                                            val list = draft.rows.toMutableList()
                                            list[idx] = row.copy(style = st)
                                            draft = draft.copy(rows = list)
                                        },
                                        label = {
                                            Text(
                                                text = styleLabel(st),
                                                fontSize = 11.sp,
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SoulSyncColors.Accent,
                                            selectedLabelColor = SoulSyncColors.OnAccent,
                                            containerColor = SoulSyncColors.PanelSolid,
                                            labelColor = SoulSyncColors.TextSoft,
                                        ),
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun labelForRowId(id: String): String = when {
    id == "continue" -> "Continue Watching"
    id == "watchlist" -> "Watchlist"
    id == "top-rated" -> "Top 10 in Your Library"
    id == "recent-movies" -> "Recently Added Movies"
    id == "recent-shows" -> "TV Shows"
    id == "recent-music" -> "Recent Music"
    id == "youtube" -> "YouTube"
    id == "podcasts" -> "Podcasts"
    id == "audiobooks" -> "Audiobooks"
    id.startsWith("genre:movie:") -> "${id.removePrefix("genre:movie:")} Movies"
    id.startsWith("genre:show:") -> "${id.removePrefix("genre:show:")} Shows"
    else -> id.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

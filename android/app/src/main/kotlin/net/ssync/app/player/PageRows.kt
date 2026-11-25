package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.HomeLayout
import net.ssync.player.HomeLayoutResponse
import net.ssync.player.LayoutCatalog
import net.ssync.player.LayoutRow
import net.ssync.player.MusicApiResult
import net.ssync.player.PageShelves
import net.ssync.player.PlayerApiClient

/**
 * The rows of a browse page (Movies, TV, Music, YouTube), from SoulSync's
 * page layout contract: which rows, in what order, in what style, for this
 * screen or for every screen of the profile. The same layouts the web
 * player's pages draw from, so arranging Movies in a browser arranges it
 * here.
 *
 * Until the layout has loaded (or if it can't be read) the page's default
 * rows show, so a page never waits on it.
 */
@Stable
class PageRowsState internal constructor(
    val page: String,
    private val api: PlayerApiClient,
    private val device: String,
    private val scope: CoroutineScope,
) {
    var layout by mutableStateOf(HomeLayout(rows = PageShelves.DEFAULT_ROWS[page.substringBefore(':')] ?: emptyList()))
        private set
    var catalog by mutableStateOf(LayoutCatalog())
        private set
    /** this screen follows the profile's shared rows, not its own */
    var shared by mutableStateOf(true)
        private set
    /** someone has arranged this page (for this screen or for all): false is the page's default */
    var arranged by mutableStateOf(false)
        private set
    var editing by mutableStateOf(false)

    /** the rows to draw: the ones that are on, in order */
    val rows: List<LayoutRow> get() = layout.rows.filter { it.on }

    fun isOn(id: String): Boolean = layout.rows.any { it.on && it.id == id }

    internal fun take(resp: HomeLayoutResponse) {
        if (resp.layout.rows.isNotEmpty()) layout = resp.layout
        if (resp.catalog.rows.isNotEmpty()) catalog = resp.catalog
        shared = resp.shared
        arranged = resp.scope != null
    }

    suspend fun load() {
        (api.fetchPageLayout(page, device) as? MusicApiResult.Success)?.let {
            take(it.data)
            pageLayoutCache["${api.baseUrl}|$page"] = it.data
        }
    }

    fun save(updated: HomeLayout, toAll: Boolean) {
        layout = updated
        shared = toAll
        arranged = true
        editing = false
        scope.launch {
            // the server cleans what it stores: show that. a save that failed
            // goes back to what the server has, not a layout that only exists here
            when (val saved = api.savePageLayout(page, updated, device, toAll)) {
                is MusicApiResult.Success -> if (saved.data.layout.rows.isNotEmpty()) layout = saved.data.layout
                else -> load()
            }
        }
    }

    /** this screen back to the shared rows; a screen already on them puts the page back to its default */
    fun reset() {
        editing = false
        scope.launch {
            api.resetPageLayout(page, if (shared) null else device)
            load()
        }
    }
}

private val pageLayoutCache = mutableMapOf<String, HomeLayoutResponse>()

@Composable
fun rememberPageRows(api: PlayerApiClient, page: String): PageRowsState {
    val context = LocalContext.current.applicationContext
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val state = remember(api.baseUrl, page) {
        PageRowsState(page, api, PlayerDevicePrefs.layoutDeviceId(context, isTv), scope).also { s ->
            pageLayoutCache["${api.baseUrl}|$page"]?.let(s::take)
        }
    }
    LaunchedEffect(state) { state.load() }
    return state
}

/** the page's row editor, when it is open */
@Composable
fun PageRowsEditor(
    state: PageRowsState,
    title: String,
    movieGenres: List<String> = emptyList(),
    showGenres: List<String> = emptyList(),
    /** rows this screen is showing before anyone arranged the page (a TV hub's
     *  own shelves): the editor opens with them on, so saving keeps them */
    showingNow: Set<String> = emptySet(),
) {
    if (!state.editing) return
    val initial = if (state.arranged || showingNow.isEmpty()) state.layout else state.layout.copy(
        rows = state.layout.rows.map { if (it.id in showingNow) it.copy(on = true) else it },
    )
    HomeEditorSheet(
        initialLayout = initial,
        initialShared = state.shared,
        availableMovieGenres = movieGenres,
        onSave = state::save,
        onReset = state::reset,
        onDismiss = { state.editing = false },
        catalog = state.catalog,
        title = title,
        showBillboard = false,
        availableShowGenres = showGenres,
    )
}

/** the button at the foot of a page's rows that opens its editor */
@Composable
fun CustomizePageButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(
            onClick = onClick,
            border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
            modifier = Modifier.dpadFocusable(),
        ) {
            Text("Customize this page")
        }
    }
}

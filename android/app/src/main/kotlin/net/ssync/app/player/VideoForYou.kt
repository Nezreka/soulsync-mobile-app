package net.ssync.app.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.ssync.player.CatalogMovie
import net.ssync.player.CatalogRecentEpisode
import net.ssync.player.CatalogShow
import net.ssync.player.ExtraRail
import net.ssync.player.ExtraTitle
import net.ssync.player.LayoutRow
import net.ssync.player.MusicApiResult
import net.ssync.player.PageShelves
import net.ssync.player.PlayerApiClient
import net.ssync.player.ProgressRow
import java.time.LocalDate

/** a library title as a card, whichever kind the page is of */
private data class Owned(
    val id: Int,
    val title: String,
    val subtitle: String?,
    val rating: Double?,
    val poster: String,
    val backdrop: String,
)

/**
 * "For you" on the Movies and TV pages: the rows the page's layout names, in
 * its order and styles (SoulSync core/player/page_layout.py). Every row the
 * web player's page has is here: the library rows (PageShelves), continue
 * watching, and tmdb's picks (recommended, more like, the curated lists),
 * where a title you don't have opens its request sheet.
 */
@Composable
internal fun VideoForYouRows(
    kind: String, // "movie" | "show"
    api: PlayerApiClient,
    movies: List<CatalogMovie>,
    shows: List<CatalogShow>,
    recentEpisodes: List<CatalogRecentEpisode>,
    onOpen: (Int) -> Unit,
    /** "see all" of a shelf: the library tab, filtered */
    onSeeAll: (genre: String?, decade: String?) -> Unit,
    onRequest: (ExtraTitle) -> Unit,
    /** groups to browse by (movie collections or studios, tv networks): name to a cover's title id */
    makers: List<Pair<String, Int>> = emptyList(),
    onPickMaker: ((String) -> Unit)? = null,
) {
    val isMovie = kind == "movie"
    val state = rememberPageRows(api, if (isMovie) "movies" else "tv")
    val rows = state.rows

    var continuing by remember(api.baseUrl) { mutableStateOf<List<ProgressRow>>(emptyList()) }
    var forYou by remember(api.baseUrl) { mutableStateOf<List<ExtraTitle>>(emptyList()) }
    var moreLike by remember(api.baseUrl) { mutableStateOf<List<ExtraRail>>(emptyList()) }
    val curated = remember(api.baseUrl) { mutableStateMapOf<String, List<ExtraTitle>>() }

    // only what the rows that are on need
    val wantsContinue = state.isOn("continue")
    val wantsForYou = state.isOn("recommended") || state.isOn("recommended-add")
    val wantsMoreLike = state.isOn("more-like")
    val wantedLists = rows.mapNotNull { PageShelves.curatedList(kind, it.id)?.first }
    LaunchedEffect(api.baseUrl, wantsContinue) {
        if (wantsContinue) (api.fetchContinue() as? MusicApiResult.Success)?.let { continuing = it.data }
    }
    LaunchedEffect(api.baseUrl, wantsForYou) {
        if (wantsForYou && forYou.isEmpty()) (api.fetchDiscoverForYou() as? MusicApiResult.Success)?.let { forYou = it.data }
    }
    LaunchedEffect(api.baseUrl, wantsMoreLike) {
        if (wantsMoreLike && moreLike.isEmpty()) (api.fetchDiscoverMoreLike() as? MusicApiResult.Success)?.let { moreLike = it.data }
    }
    LaunchedEffect(api.baseUrl, wantedLists) {
        for (key in wantedLists) if (key !in curated) {
            (api.fetchDiscoverList(key) as? MusicApiResult.Success)?.let { curated[key] = it.data }
        }
    }

    fun owned(m: CatalogMovie) = Owned(
        m.id, m.title, m.year?.toString(), m.rating, api.artwork.moviePoster(m.id), api.artwork.movieBackdrop(m.id),
    )
    fun owned(s: CatalogShow) = Owned(
        s.id, s.title, "${s.episodeCount} episodes", s.rating, api.artwork.showPoster(s.id), api.artwork.showBackdrop(s.id),
    )
    val ownedById: Map<Int, Owned> = remember(movies, shows, kind) {
        if (isMovie) movies.associate { it.id to owned(it) } else shows.associate { it.id to owned(it) }
    }
    val ownedByTmdb: Map<Int, Int> = remember(movies, shows, kind) {
        if (isMovie) movies.mapNotNull { m -> m.tmdbId?.let { it to m.id } }.toMap()
        else shows.mapNotNull { s -> s.tmdbId?.let { it to s.id } }.toMap()
    }
    val today = remember { LocalDate.now() }
    val dayKey = today.toString()
    val yearAgo = today.minusYears(1).toString()

    /** tmdb titles of this page's kind, split into the ones you have and the ones you don't */
    fun split(items: List<ExtraTitle>): Pair<List<Owned>, List<ExtraTitle>> {
        val mine = mutableListOf<Owned>()
        val others = mutableListOf<ExtraTitle>()
        val seen = HashSet<Int>()
        for (t in items) {
            if (t.kind != kind) continue
            val libId = t.libraryId ?: ownedByTmdb[t.tmdbId]
            val have = libId?.let { ownedById[it] }
            if (have != null) {
                if (seen.add(have.id)) mine += have
            } else if (seen.add(-t.tmdbId)) others += t
        }
        return mine to others
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        for (row in rows) {
            when (row.id) {
                "continue" -> {
                    val mine = continuing.filter { !it.finished }.mapNotNull { p ->
                        val frac = p.progressFraction.takeIf { it > 0.01f }
                        if (isMovie && p.itemType == "movie") {
                            ownedById[p.itemId.toIntOrNull() ?: -1]?.let { Triple(it, it.subtitle, frac) to (null as String?) }
                        } else if (!isMovie && p.itemType == "episode") {
                            val show = ownedById[p.meta?.parentId?.toIntOrNull() ?: -1] ?: return@mapNotNull null
                            val still = p.itemId.toIntOrNull()?.let { api.artwork.episodeStill(it) }
                            Triple(show, p.meta?.title ?: p.meta?.subtitle, frac) to still
                        } else null
                    }.distinctBy { it.first.first.id }
                    if (mine.isNotEmpty()) item(key = "row-continue") {
                        MediaRail("Continue watching") {
                            items(mine, key = { "cw-${it.first.first.id}" }) { (card, still) ->
                                val (o, sub, frac) = card
                                WideMediaCard(
                                    title = o.title,
                                    subtitle = sub,
                                    artworkUrl = still ?: o.backdrop,
                                    fallbackArtworkUrl = o.backdrop,
                                    secondaryFallbackArtworkUrl = o.poster,
                                    progress = frac,
                                    width = if (row.style == "large") 280.dp else 228.dp,
                                    onClick = { onOpen(o.id) },
                                )
                            }
                        }
                    }
                }
                "new-episodes" -> if (!isMovie && recentEpisodes.isNotEmpty()) item(key = "row-new-episodes") {
                    MediaRail("New episodes") {
                        items(recentEpisodes.take(18), key = { "ep-${it.id}" }) { ep ->
                            WideMediaCard(
                                title = ep.showTitle,
                                subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" + (ep.title?.let { " · $it" } ?: ""),
                                artworkUrl = if (ep.hasStill) api.artwork.episodeStill(ep.id) else api.artwork.showBackdrop(ep.showId),
                                fallbackArtworkUrl = api.artwork.showBackdrop(ep.showId),
                                width = if (row.style == "large") 280.dp else 228.dp,
                                onClick = { onOpen(ep.showId) },
                            )
                        }
                    }
                }
                "recommended" -> ownedRail(row, "row-recommended", "Recommended for you", split(forYou).first.take(30), onOpen)
                "recommended-add" -> extraRail(
                    row, "row-recommended-add",
                    if (isMovie) "Recommended movies to add" else "Recommended shows to add",
                    split(forYou).second.take(30), api, onRequest,
                )
                "more-like" -> moreLike.forEachIndexed { i, rail ->
                    mixedRail(row, "row-more-$i", rail.title, split(rail.items), api, onOpen, onRequest)
                }
                "collections", "studios", "networks" -> if (onPickMaker != null && makers.isNotEmpty()) {
                    val title = when (row.id) {
                        "collections" -> "Collections"
                        "studios" -> "Studios"
                        else -> "Networks"
                    }
                    item(key = "row-${row.id}") {
                        MediaRail(title) {
                            items(makers.take(24), key = { "mk-${it.first}" }) { (name, coverId) ->
                                PosterCard(
                                    title = name,
                                    artworkUrl = ownedById[coverId]?.poster,
                                    onClick = { onPickMaker(name) },
                                )
                            }
                        }
                    }
                }
                else -> {
                    val list = PageShelves.curatedList(kind, row.id)
                    if (list != null) {
                        mixedRail(row, "row-${row.id}", list.second, split(curated[list.first].orEmpty()), api, onOpen, onRequest)
                    } else {
                        val shelves = if (isMovie) {
                            PageShelves.movies(row.id, movies, today.year, dayKey, yearAgo)
                                .map { Triple(it.title, it.items.map(::owned), it.key) to (it.genre to it.decade) }
                        } else {
                            PageShelves.shows(row.id, shows, today.year, dayKey, yearAgo)
                                .map { Triple(it.title, it.items.map(::owned), it.key) to (it.genre to it.decade) }
                        }
                        for ((shelf, filter) in shelves) {
                            val (title, cards, key) = shelf
                            val seeAll: (() -> Unit)? = when {
                                filter.first != null || filter.second != null -> { { onSeeAll(filter.first, filter.second) } }
                                row.id == "recent" -> { { onSeeAll(null, null) } }
                                else -> null
                            }
                            ownedRail(row, "row-$key", title, cards, onOpen, seeAll)
                        }
                    }
                }
            }
        }
        item(key = "customize") { CustomizePageButton { state.editing = true } }
    }

    PageRowsEditor(
        state = state,
        title = if (isMovie) "Customize Movies" else "Customize TV Shows",
        movieGenres = if (isMovie) movies.flatMap { it.genres }.distinct().sorted() else emptyList(),
        showGenres = if (isMovie) emptyList() else shows.flatMap { it.genres }.distinct().sorted(),
    )
}

private fun cardWidth(style: String): Dp = if (style == "large") 150.dp else 122.dp
private fun wideWidth(style: String): Dp = if (style == "large") 280.dp else 228.dp

/** a row of titles you have, in the row's style: cards, large cards, wide stills, or a numbered top ten */
private fun LazyListScope.ownedRail(
    row: LayoutRow,
    key: String,
    title: String,
    cards: List<Owned>,
    onOpen: (Int) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    if (cards.isEmpty()) return
    item(key = key) {
        MediaRail(title, actionLabel = onSeeAll?.let { "All" }, onAction = onSeeAll) {
            when (row.style) {
                "ranked" -> items(cards.take(10).withIndex().toList(), key = { "$key-${it.value.id}" }) { (i, o) ->
                    RankedPosterCard(rank = i + 1, title = o.title, artworkUrl = o.poster, onClick = { onOpen(o.id) })
                }
                "wide" -> items(cards, key = { "$key-${it.id}" }) { o ->
                    WideMediaCard(
                        title = o.title, subtitle = o.subtitle, artworkUrl = o.backdrop,
                        fallbackArtworkUrl = o.poster, width = wideWidth(row.style), onClick = { onOpen(o.id) },
                    )
                }
                else -> items(cards, key = { "$key-${it.id}" }) { o ->
                    PosterCard(
                        title = o.title, subtitle = o.subtitle, artworkUrl = o.poster,
                        badge = o.rating?.let { "%.1f".format(it) }, width = cardWidth(row.style),
                        onClick = { onOpen(o.id) },
                    )
                }
            }
        }
    }
}

/** a row of titles you don't have: each opens its request sheet */
private fun LazyListScope.extraRail(
    row: LayoutRow,
    key: String,
    title: String,
    items: List<ExtraTitle>,
    api: PlayerApiClient,
    onRequest: (ExtraTitle) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key) {
        MediaRail(title) {
            items(items, key = { "$key-${it.tmdbId}" }) { t ->
                PosterCard(
                    title = t.title, subtitle = t.yearText, artworkUrl = api.artwork.resolve(t.poster),
                    inLibrary = false, width = cardWidth(row.style), onClick = { onRequest(t) },
                )
            }
        }
    }
}

/** tmdb's list as it comes: yours open, the rest can be requested. fewer than four is not a row (the web's rule) */
private fun LazyListScope.mixedRail(
    row: LayoutRow,
    key: String,
    title: String,
    split: Pair<List<Owned>, List<ExtraTitle>>,
    api: PlayerApiClient,
    onOpen: (Int) -> Unit,
    onRequest: (ExtraTitle) -> Unit,
) {
    val (mine, others) = split
    if (mine.size + others.size < 4) return
    item(key = key) {
        MediaRail(title) {
            items(mine.take(30), key = { "$key-o${it.id}" }) { o ->
                PosterCard(
                    title = o.title, subtitle = o.subtitle, artworkUrl = o.poster,
                    width = cardWidth(row.style), onClick = { onOpen(o.id) },
                )
            }
            items(others.take(30 - mine.size.coerceAtMost(30)), key = { "$key-x${it.tmdbId}" }) { t ->
                PosterCard(
                    title = t.title, subtitle = t.yearText, artworkUrl = api.artwork.resolve(t.poster),
                    inLibrary = false, width = cardWidth(row.style), onClick = { onRequest(t) },
                )
            }
        }
    }
}

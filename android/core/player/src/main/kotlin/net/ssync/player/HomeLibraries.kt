package net.ssync.player

// home and the player's libraries (SoulSync scrap/HOME_LIBRARIES_CONTRACT.md):
// a row per library, an on this day row, and one card per title across libraries.

/** a home row that draws from a library, or the on this day row */
sealed interface LibraryRow {
    data class Recent(val libraryId: Int) : LibraryRow
    data class Continue(val libraryId: Int) : LibraryRow
    data object Memories : LibraryRow
}

object HomeLibraries {
    const val MEMORIES = "home-memories"
    private val LIB_ROW = Regex("""^lib-(recent|continue):(\d{1,9})$""")

    fun recentId(libraryId: Int) = "lib-recent:$libraryId"
    fun continueId(libraryId: Int) = "lib-continue:$libraryId"

    /** the row an id names, null for any other row */
    fun parse(id: String): LibraryRow? {
        if (id == MEMORIES) return LibraryRow.Memories
        val m = LIB_ROW.matchEntire(id) ?: return null
        val lib = m.groupValues[2].toInt()
        return if (m.groupValues[1] == "recent") LibraryRow.Recent(lib) else LibraryRow.Continue(lib)
    }

    /** the movie or show library a row draws from; null when this profile can't see it (hidden, removed) */
    fun library(row: LibraryRow, libraries: List<PlayerLibrary>): PlayerLibrary? {
        val id = when (row) {
            is LibraryRow.Recent -> row.libraryId
            is LibraryRow.Continue -> row.libraryId
            LibraryRow.Memories -> return null
        }
        return libraries.firstOrNull { it.id == id && it.kind in TITLE_KINDS }
    }

    fun hasHomeLibrary(libraries: List<PlayerLibrary>) = libraries.any { it.kind == "home" }

    /** may this row be drawn for this profile: a library row only for a library it can see */
    fun visible(id: String, libraries: List<PlayerLibrary>): Boolean = when (val row = parse(id)) {
        null -> true
        LibraryRow.Memories -> hasHomeLibrary(libraries)
        else -> library(row, libraries) != null
    }

    /** the row's title, with the library's current name; null for other rows or one that isn't shown */
    fun title(id: String, libraries: List<PlayerLibrary>): String? = when (val row = parse(id)) {
        null -> null
        LibraryRow.Memories -> if (hasHomeLibrary(libraries)) "On this day" else null
        is LibraryRow.Recent -> library(row, libraries)?.let { "Recently added in ${it.name}" }
        is LibraryRow.Continue -> library(row, libraries)?.let { "Continue in ${it.name}" }
    }

    /** the editor's words for a row (the server's, for when its catalog doesn't have the row) */
    fun hint(id: String, libraries: List<PlayerLibrary>): String = when (val row = parse(id)) {
        null -> ""
        LibraryRow.Memories -> "Home videos filmed on this day in years past"
        is LibraryRow.Recent -> library(row, libraries)?.let { "The newest in ${it.name} first" }.orEmpty()
        is LibraryRow.Continue -> library(row, libraries)?.let { "What you started in ${it.name}" }.orEmpty()
    }

    /** the styles a row can take (home_layout.py's _POSTER and _WIDE) */
    fun styles(id: String): List<String> = when (parse(id)) {
        is LibraryRow.Recent -> listOf("standard", "large", "wide", "ranked")
        null -> emptyList()
        else -> listOf("wide", "large")
    }

    fun defaultStyle(id: String): String = if (parse(id) is LibraryRow.Recent) "standard" else "wide"

    /** every library row this profile could have, for the editor to offer, in the server's order */
    fun offered(libraries: List<PlayerLibrary>): List<String> = buildList {
        if (hasHomeLibrary(libraries)) add(MEMORIES)
        for (lib in libraries) if (lib.kind in TITLE_KINDS) {
            add(recentId(lib.id))
            add(continueId(lib.id))
        }
    }

    /** the library a title is in: its own, else the main one of its kind */
    fun libraryOf(libraryId: Int?, kind: String, libraries: List<PlayerLibrary>): Int? =
        libraryId ?: mainLibraryId(kind, libraries)

    /** the main (default) library of a kind */
    fun mainLibraryId(kind: String, libraries: List<PlayerLibrary>): Int? =
        libraries.firstOrNull { it.kind == kind && it.main }?.id

    /** a library's titles, newest first */
    fun recentMovies(libraryId: Int, movies: List<CatalogMovie>, libraries: List<PlayerLibrary>): List<CatalogMovie> =
        movies.filter { libraryOf(it.libraryId, "movie", libraries) == libraryId }.sortedByDescending { it.addedAt.orEmpty() }

    fun recentShows(libraryId: Int, shows: List<CatalogShow>, libraries: List<PlayerLibrary>): List<CatalogShow> =
        shows.filter { libraryOf(it.libraryId, "show", libraries) == libraryId }.sortedByDescending { it.addedAt.orEmpty() }

    /** the continue watching items whose title is in this library: a movie by its id, an episode by its show */
    fun continueIn(
        library: PlayerLibrary,
        items: List<ProgressRow>,
        movies: List<CatalogMovie>,
        shows: List<CatalogShow>,
        libraries: List<PlayerLibrary>,
    ): List<ProgressRow> {
        return if (library.kind == "movie") {
            val mine = movies.filter { libraryOf(it.libraryId, "movie", libraries) == library.id }.mapTo(HashSet()) { it.id }
            items.filter { it.itemType == "movie" && it.itemId.toIntOrNull() in mine }
        } else {
            val mine = shows.filter { libraryOf(it.libraryId, "show", libraries) == library.id }.mapTo(HashSet()) { it.id }
            items.filter {
                when (it.itemType) {
                    "episode" -> it.meta?.parentId?.toIntOrNull() in mine
                    "show" -> it.itemId.toIntOrNull() in mine
                    else -> false
                }
            }
        }
    }

    /**
     * moves the row at [index] one place up or down past the rows the editor
     * shows; a row it doesn't show (a hidden library's) stays where it is
     */
    fun move(rows: List<LayoutRow>, index: Int, up: Boolean, shown: (LayoutRow) -> Boolean): List<LayoutRow> {
        if (index !in rows.indices) return rows
        val step = if (up) -1 else 1
        var j = index + step
        while (j in rows.indices && !shown(rows[j])) j += step
        if (j !in rows.indices) return rows
        val list = rows.toMutableList()
        list[index] = rows[j]
        list[j] = rows[index]
        return list
    }

    private val TITLE_KINDS = setOf("movie", "show")
}

/**
 * one card per title on the catalog-wide home rows: a film in Movies and in
 * Movies 4K is one card (same tmdb id, else same title and year), opening the
 * copy in the main library, with a 4K chip when another copy is 4K. library
 * pages don't use this; they show their own copy.
 */
class TitleCopies private constructor(
    private val movies: Map<Int, CatalogMovie>,
    private val shows: Map<Int, CatalogShow>,
    private val movieMain: Map<Int, Int>,
    private val showMain: Map<Int, Int>,
    private val movie4k: Set<Int>,
    private val show4k: Set<Int>,
) {
    /** the id of the copy a card opens */
    fun movieId(id: Int): Int = movieMain[id] ?: id
    fun showId(id: Int): Int = showMain[id] ?: id

    /** another copy of this title is 4K */
    fun movieHas4k(id: Int): Boolean = movieId(id) in movie4k
    fun showHas4k(id: Int): Boolean = showId(id) in show4k

    /** the list with each title once, where it first appears, as its main copy */
    fun movies(list: List<CatalogMovie>): List<CatalogMovie> {
        if (movieMain.isEmpty()) return list
        val seen = HashSet<Int>()
        return list.mapNotNull { m ->
            val id = movieId(m.id)
            if (seen.add(id)) movies[id] ?: m else null
        }
    }

    fun shows(list: List<CatalogShow>): List<CatalogShow> {
        if (showMain.isEmpty()) return list
        val seen = HashSet<Int>()
        return list.mapNotNull { s ->
            val id = showId(s.id)
            if (seen.add(id)) shows[id] ?: s else null
        }
    }

    /** (kind, id) cards with each title once, as its main copy */
    fun cards(list: List<Pair<String, Int>>): List<Pair<String, Int>> =
        list.map { (k, id) -> k to (if (k == "movie") movieId(id) else showId(id)) }.distinct()

    companion object {
        val EMPTY = TitleCopies(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet(), emptySet())

        private val FOUR_K = Regex("""(?i)(^|[^a-z0-9])(4k|2160p?|uhd)([^a-z0-9]|$)""")

        /** a 4K library: the server says (uhd), or an older one's name does */
        fun is4kLibrary(lib: PlayerLibrary): Boolean =
            lib.kind != "home" && (lib.uhd ?: FOUR_K.containsMatchIn(lib.name))

        /** what makes two rows the same title: the tmdb id, else the title and year; null when neither */
        fun key(tmdbId: Int?, title: String, year: Int?): String? = when {
            tmdbId != null && tmdbId > 0 -> "t:$tmdbId"
            year != null && year > 0 -> "n:${title.trim().lowercase()}|$year"
            else -> null
        }

        fun of(catalog: VideoCatalog): TitleCopies = of(catalog.movies, catalog.shows, catalog.libraries)

        fun of(movies: List<CatalogMovie>, shows: List<CatalogShow>, libraries: List<PlayerLibrary>): TitleCopies {
            // one library of each kind: nothing can be in two
            if (libraries.count { it.kind == "movie" } < 2 && libraries.count { it.kind == "show" } < 2) return EMPTY
            val fourK = libraries.filter(::is4kLibrary).mapTo(HashSet()) { it.id }
            val mv = group(movies, "movie", libraries, fourK, { it.id }, { it.libraryId }) { key(it.tmdbId, it.title, it.year) }
            val sh = group(shows, "show", libraries, fourK, { it.id }, { it.libraryId }) { key(it.tmdbId, it.title, it.year) }
            return TitleCopies(
                movies = movies.associateBy { it.id },
                shows = shows.associateBy { it.id },
                movieMain = mv.first,
                showMain = sh.first,
                movie4k = mv.second,
                show4k = sh.second,
            )
        }

        /** (id to its main copy's id, for titles with copies) and (main ids with a 4K copy besides) */
        private fun <T> group(
            items: List<T>,
            kind: String,
            libraries: List<PlayerLibrary>,
            fourK: Set<Int>,
            id: (T) -> Int,
            libraryId: (T) -> Int?,
            key: (T) -> String?,
        ): Pair<Map<Int, Int>, Set<Int>> {
            val main = HomeLibraries.mainLibraryId(kind, libraries)
            val groups = LinkedHashMap<String, MutableList<T>>()
            for (it in items) groups.getOrPut(key(it) ?: continue) { mutableListOf() }.add(it)
            val mainOf = HashMap<Int, Int>()
            val with4k = HashSet<Int>()
            for (copies in groups.values) {
                if (copies.size < 2) continue
                // a copy is 4K by the library its files are in
                fun is4k(t: T) = libraryId(t)?.let { it in fourK } == true
                val pick = copies.firstOrNull { main != null && HomeLibraries.libraryOf(libraryId(it), kind, libraries) == main }
                    ?: copies.firstOrNull { !is4k(it) }
                    ?: copies.first()
                val pickId = id(pick)
                for (c in copies) mainOf[id(c)] = pickId
                if (copies.any { it !== pick && is4k(it) }) with4k += pickId
            }
            return mainOf to with4k
        }
    }
}

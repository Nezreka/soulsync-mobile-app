package net.ssync.app.player

import net.ssync.player.PlayerLibrary

/** a movie or show on a library's own home row */
internal data class LibraryTitleCard(
    val kind: String,
    val id: Int,
    val title: String,
    val subtitle: String?,
    val rating: Double?,
    val poster: String,
    val backdrop: String,
)

/** where a library's "All" goes: its own page, else the page it is part of */
internal fun libraryDestination(lib: PlayerLibrary): PlayerDestination = when {
    lib.kind == "home" -> PlayerDestination.HomeVideos(lib)
    lib.ownPage -> PlayerDestination.Library(lib)
    lib.kind == "show" -> PlayerDestination.Tv
    else -> PlayerDestination.Movies
}

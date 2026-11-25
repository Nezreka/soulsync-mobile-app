package net.ssync.player

/**
 * The sidebar's places in a profile's own order (SoulSync player settings
 * `sidebar_order` / `sidebar_hidden`). A place is one of the player's pages
 * ("/player/movies") or a library with a page of its own ("lib:5"): the same
 * ids the web player uses, so arranging the sidebar in a browser arranges it
 * here.
 *
 * The web player's rule (webui -lib/nav-order.ts): the saved places first, in
 * their order; a place the order doesn't name keeps its usual spot after
 * them; a saved place the player doesn't have is skipped; a hidden place is
 * left out, but never Home.
 */
object NavOrder {
    const val HOME = "/player"

    fun arrange(ids: List<String>, order: List<String>, hidden: Collection<String>): List<String> {
        val gone = hidden.filter { it != HOME }.toSet()
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return ids.filter { it !in gone }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { if (it.value in rank) 0 else 1 }
                    .thenBy { rank[it.value] ?: it.index },
            )
            .map { it.value }
    }

    fun shown(id: String, hidden: Collection<String>): Boolean = id == HOME || id !in hidden
}

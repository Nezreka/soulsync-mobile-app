package net.ssync.player

/**
 * Exact SoulSync `/player` artwork URL builder (ported from `webui/src/routes/player/-lib/images.ts`),
 * with guaranteed per-item fallback resolvers so no card ever drops its cover image.
 */
class PlayerArtwork(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    companion object {
        /**
         * a TV draws the same artwork much larger than a phone does (a poster
         * card is a third wider, an album cover fills half the screen), and it
         * was asking for the phone's sizes: every album cover was the 256 px
         * thumbnail whatever size it was drawn at, which is what looked
         * pixelated on a 4K set. set once at start on a TV; the image loader
         * still scales each picture down to the size it is shown at.
         */
        @Volatile
        var largeScreen: Boolean = false
    }

    /** the width to ask for: half as much again on a TV */
    private fun w(width: Int): Int = if (largeScreen) width * 3 / 2 else width

    fun moviePoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/movie/$id?w=${w(width)}"

    fun showPoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/show/$id?w=${w(width)}"

    fun seasonPoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/season/$id?w=${w(width)}"

    fun episodeStill(id: Int, width: Int = 500): String =
        "$base/api/video/poster/episode/$id?w=${w(width)}"

    fun movieBackdrop(id: Int, width: Int = 780): String =
        "$base/api/video/backdrop/movie/$id?w=${w(width)}"

    fun showBackdrop(id: Int, width: Int = 780): String =
        "$base/api/video/backdrop/show/$id?w=${w(width)}"

    fun albumArtwork(albumId: Int?): String? =
        albumId?.let { "$base/api/library/v2/artwork/album/$it" + if (largeScreen) "" else "?size=thumb" }

    fun audiobookCover(asin: String): String =
        "$base/api/audiobooks/library/$asin/cover"

    fun profileAvatar(profileId: String): String =
        "$base/api/profiles/$profileId/avatar"

    /**
     * Guaranteed fallback artwork resolver by item type and ID when metadata
     * omits an explicit `backdrop` or `artwork` path.
     */
    fun forItem(
        itemType: String,
        itemId: String,
        preferBackdrop: Boolean = false,
        parentId: String? = null,
    ): String? {
        val intId = itemId.toIntOrNull()
        return when (itemType) {
            "movie" -> intId?.let { if (preferBackdrop) movieBackdrop(it) else moviePoster(it) }
            "show" -> intId?.let { if (preferBackdrop) showBackdrop(it) else showPoster(it) }
            "episode" -> intId?.let { episodeStill(it) }
                ?: parentId?.toIntOrNull()?.let { if (preferBackdrop) showBackdrop(it) else showPoster(it) }
            "audiobook" -> audiobookCover(itemId)
            "track" -> parentId?.toIntOrNull()?.let { albumArtwork(it) }
            "youtube" -> "https://i.ytimg.com/vi/$itemId/mqdefault.jpg"
            else -> null
        }
    }

    fun resolve(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        // TMDB bare image paths (e.g. "/abc123.jpg") from cast or similar titles
        if (!normalized.startsWith("/api/") && !normalized.startsWith("/static/") &&
            (normalized.endsWith(".jpg", ignoreCase = true) ||
                normalized.endsWith(".png", ignoreCase = true) ||
                normalized.endsWith(".webp", ignoreCase = true))
        ) {
            return "https://image.tmdb.org/t/p/${if (largeScreen) "w500" else "w342"}$normalized"
        }
        return if (!largeScreen && normalized.startsWith("/api/library/v2/artwork/") && !normalized.contains("size=")) {
            val join = if (normalized.contains("?")) "&" else "?"
            "$base$normalized${join}size=thumb"
        } else {
            "$base$normalized"
        }
    }
}

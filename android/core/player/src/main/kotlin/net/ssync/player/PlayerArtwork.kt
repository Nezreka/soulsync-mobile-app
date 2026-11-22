package net.ssync.player

/**
 * Exact SoulSync `/player` artwork URL builder (ported from `webui/src/routes/player/-lib/images.ts`),
 * with guaranteed per-item fallback resolvers so no card ever drops its cover image.
 */
class PlayerArtwork(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    fun moviePoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/movie/$id?w=$width"

    fun showPoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/show/$id?w=$width"

    fun seasonPoster(id: Int, width: Int = 342): String =
        "$base/api/video/poster/season/$id?w=$width"

    fun episodeStill(id: Int, width: Int = 500): String =
        "$base/api/video/poster/episode/$id?w=$width"

    fun movieBackdrop(id: Int, width: Int = 1280): String =
        "$base/api/video/backdrop/movie/$id?w=$width"

    fun showBackdrop(id: Int, width: Int = 1280): String =
        "$base/api/video/backdrop/show/$id?w=$width"

    fun albumArtwork(albumId: Int?): String? =
        albumId?.let { "$base/api/library/v2/artwork/album/$it?size=thumb" }

    fun audiobookCover(asin: String): String =
        "$base/api/audiobooks/library/$asin/cover"

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
            else -> null
        }
    }

    fun resolve(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val normalized = if (url.startsWith("/")) url else "/$url"
        return if (normalized.startsWith("/api/library/v2/artwork/") && !normalized.contains("size=")) {
            val join = if (normalized.contains("?")) "&" else "?"
            "$base$normalized${join}size=thumb"
        } else {
            "$base$normalized"
        }
    }
}

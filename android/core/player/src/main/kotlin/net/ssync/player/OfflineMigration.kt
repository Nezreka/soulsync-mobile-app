package net.ssync.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * the first downloads build kept a manifest.json of what it saved, and files
 * named "<type>_<id>.media" beside it. its manifest often never got written
 * (its classes had no serializer in the app module), so the files alone are
 * enough to bring a download over.
 */
object OfflineMigration {
    @Serializable
    data class LegacyItem(
        val key: String = "",
        val itemType: String = "",
        val itemId: String = "",
        val title: String = "",
        val subtitle: String = "",
        val artworkUrl: String? = null,
        val isVideo: Boolean = false,
        val localPath: String = "",
        val sizeBytes: Long = 0L,
        val downloadedAtMs: Long = 0L,
        val parentId: String? = null,
    )

    @Serializable
    data class LegacyRemoval(val itemType: String = "", val itemId: String = "")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // longest first: podcast_episode before a bare "podcast"
    private val KNOWN_TYPES = listOf("podcast_episode", "audiobook", "episode", "youtube", "podcast", "movie", "track")

    fun parseManifest(text: String): List<LegacyItem> =
        runCatching { json.decodeFromString(ListSerializer(LegacyItem.serializer()), text) }.getOrDefault(emptyList())

    fun parseRemovals(text: String): List<LegacyRemoval> =
        runCatching { json.decodeFromString(ListSerializer(LegacyRemoval.serializer()), text) }.getOrDefault(emptyList())

    /** "episode_812.media" -> ("episode", "812"); anything else -> null */
    fun parseFileName(name: String): Pair<String, String>? {
        if (!name.endsWith(".media")) return null
        val stem = name.removeSuffix(".media")
        val type = KNOWN_TYPES.firstOrNull { stem.startsWith(it + "_") } ?: return null
        val id = stem.removePrefix(type + "_")
        if (id.isBlank()) return null
        return type to id
    }

    /** the old manager's device id, so its rows on the server can be closed */
    fun legacyDeviceId(model: String): String =
        "android-${model.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"

    data class LegacyFile(val name: String, val path: String, val sizeBytes: Long, val modifiedMs: Long)

    /**
     * the old downloads as new records. a manifest entry wins (it has the
     * title); a file the manifest missed still comes over, titled from the
     * server on the next sync. files that are gone are dropped.
     */
    fun migrate(manifest: List<LegacyItem>, files: List<LegacyFile>, startOrder: Long = 1L): List<DownloadTask> {
        val byPath = files.associateBy { it.path }
        val byName = files.associateBy { it.name }
        val out = LinkedHashMap<String, DownloadTask>()
        var order = startOrder
        for (m in manifest) {
            val file = byPath[m.localPath] ?: byName[m.localPath.substringAfterLast('/')] ?: continue
            val parsed = parseFileName(file.name)
            val type = m.itemType.lowercase().ifBlank { parsed?.first.orEmpty() }
            val id = m.itemId.ifBlank { parsed?.second.orEmpty() }
            if (type.isBlank() || id.isBlank()) continue
            val key = offlineKey(type, id)
            if (key in out) continue
            out[key] = DownloadTask(
                key = key,
                itemType = type,
                itemId = id,
                title = m.title.ifBlank { fallbackTitle(type, id) },
                subtitle = m.subtitle,
                artwork = relativeArtwork(m.artworkUrl),
                isVideo = m.isVideo || type in OFFLINE_VIDEO_KINDS,
                parentId = m.parentId,
                quality = "original",
                state = TaskState.DOWNLOADED,
                order = order++,
                bytesDone = file.sizeBytes,
                totalBytes = file.sizeBytes,
                localPath = file.path,
                reason = "migrated",
                addedAtMs = m.downloadedAtMs.takeIf { it > 0 } ?: file.modifiedMs,
                completedAtMs = m.downloadedAtMs.takeIf { it > 0 } ?: file.modifiedMs,
                needsMeta = m.title.isBlank(),
            )
        }
        for (f in files) {
            val (type, id) = parseFileName(f.name) ?: continue
            val key = offlineKey(type, id)
            if (key in out) continue
            out[key] = DownloadTask(
                key = key,
                itemType = type,
                itemId = id,
                title = fallbackTitle(type, id),
                isVideo = type in OFFLINE_VIDEO_KINDS,
                quality = "original",
                state = TaskState.DOWNLOADED,
                order = order++,
                bytesDone = f.sizeBytes,
                totalBytes = f.sizeBytes,
                localPath = f.path,
                reason = "migrated",
                addedAtMs = f.modifiedMs,
                completedAtMs = f.modifiedMs,
                needsMeta = true,
            )
        }
        return out.values.toList()
    }

    private fun fallbackTitle(type: String, id: String): String = when (type) {
        "movie" -> "Movie"
        "episode" -> "Episode"
        "track" -> "Song"
        "audiobook" -> "Audiobook"
        "podcast_episode", "podcast" -> "Podcast episode"
        "youtube" -> "YouTube video"
        else -> "Download"
    } + " $id".takeIf { id.length <= 12 }.orEmpty()

    /** the old manifest kept full urls; the server's paths start at /api */
    fun relativeArtwork(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val at = raw.indexOf("/api/")
        return if (raw.startsWith("http") && at > 0) raw.substring(at) else raw
    }
}

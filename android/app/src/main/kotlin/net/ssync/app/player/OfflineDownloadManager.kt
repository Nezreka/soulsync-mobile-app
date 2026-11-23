package net.ssync.app.player

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@Serializable
data class OfflineItem(
    val key: String, // "$itemType:$itemId"
    val itemType: String, // movie | episode | youtube | podcast | audiobook | track
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val isVideo: Boolean = false,
    val localPath: String = "",
    val sizeBytes: Long = 0L,
    val downloadedAtMs: Long = 0L,
    val parentId: String? = null,
)

@Serializable
data class PendingRemoval(
    val itemType: String,
    val itemId: String,
)

data class DownloadTaskState(
    val key: String,
    val title: String,
    val itemType: String,
    val progress: Float = 0f, // 0..1, -1 if indeterminate
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
)

/**
 * Best-in-class Android Offline Downloads Engine:
 * - Supports Movies, TV Episodes, YouTube, Podcasts, Audiobooks, and Music tracks
 * - Saves files locally in `filesDir/soulsync_offline` with a persisted JSON manifest
 *   so the Downloads tab and local playback work 100% offline (Airplane Mode)
 * - Logs every download to `/api/player/downloads` and syncs deletions back to
 *   `/api/player/downloads/removed` (queueing offline deletions for next reconnect!)
 */
object OfflineDownloadManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var offlineDir: File? = null
    private var manifestFile: File? = null
    private var removalsFile: File? = null
    private var deviceId: String = "android-${Build.MODEL.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
    private var deviceName: String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifBlank { "Android" }

    private val _items = MutableStateFlow<List<OfflineItem>>(emptyList())
    val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()

    private val _active = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadTaskState>> = _active.asStateFlow()

    fun init(context: Context) {
        if (offlineDir != null) return
        val dir = File(context.applicationContext.filesDir, "soulsync_offline").apply { mkdirs() }
        offlineDir = dir
        manifestFile = File(dir, "manifest.json")
        removalsFile = File(dir, "pending_removals.json")
        loadManifest()
    }

    private fun loadManifest() {
        val f = manifestFile ?: return
        if (!f.exists()) return
        runCatching {
            val loaded = json.decodeFromString<List<OfflineItem>>(f.readText())
                .filter { File(it.localPath).exists() }
            _items.value = loaded
        }
    }

    private fun saveManifest(list: List<OfflineItem>) {
        val f = manifestFile ?: return
        runCatching { f.writeText(json.encodeToString(list)) }
    }

    private fun loadPendingRemovals(): MutableList<PendingRemoval> {
        val f = removalsFile ?: return mutableListOf()
        if (!f.exists()) return mutableListOf()
        return runCatching { json.decodeFromString<List<PendingRemoval>>(f.readText()).toMutableList() }
            .getOrElse { mutableListOf() }
    }

    private fun savePendingRemovals(list: List<PendingRemoval>) {
        val f = removalsFile ?: return
        runCatching { f.writeText(json.encodeToString(list)) }
    }

    fun keyOf(itemType: String, itemId: String): String = "${itemType.lowercase()}:$itemId"

    fun isDownloaded(itemType: String, itemId: String): Boolean {
        val k = keyOf(itemType, itemId)
        val hit = _items.value.firstOrNull { it.key == k } ?: return false
        return File(hit.localPath).exists()
    }

    fun localUriIfDownloaded(itemType: String, itemId: String): String? {
        val k = keyOf(itemType, itemId)
        val hit = _items.value.firstOrNull { it.key == k } ?: return null
        val file = File(hit.localPath)
        return if (file.exists()) Uri.fromFile(file).toString() else null
    }

    fun totalBytes(): Long = _items.value.sumOf { it.sizeBytes }

    fun syncPendingRemovals(api: PlayerApiClient) {
        scope.launch {
            val pending = loadPendingRemovals()
            if (pending.isEmpty()) return@launch
            val remaining = mutableListOf<PendingRemoval>()
            for (rem in pending) {
                val res = api.markOfflineDownloadRemoved(rem.itemType, rem.itemId, deviceId)
                if (res !is MusicApiResult.Success) {
                    remaining += rem
                }
            }
            savePendingRemovals(remaining)
        }
    }

    fun enqueueDownload(
        api: PlayerApiClient,
        itemType: String,
        itemId: String,
        title: String,
        subtitle: String = "",
        artworkUrl: String? = null,
        streamUrl: String,
        isVideo: Boolean = false,
        parentId: String? = null,
    ) {
        val dir = offlineDir ?: return
        val key = keyOf(itemType, itemId)
        if (_active.value.containsKey(key)) return

        _active.value = _active.value + (key to DownloadTaskState(key = key, title = title, itemType = itemType, progress = 0f))

        scope.launch {
            try {
                // Check server download permission first
                val permCheck = api.fetchMyDownloads(includeRemoved = false)
                if (permCheck is MusicApiResult.Success && !permCheck.data.allowDownloads && !permCheck.data.isAdmin) {
                    _active.value = _active.value + (key to DownloadTaskState(key, title, itemType, error = "Downloads disabled by admin"))
                    return@launch
                }

                val safeName = "${itemType.lowercase()}_${itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.media"
                val tmpFile = File(dir, "$safeName.part")
                val destFile = File(dir, safeName)

                val req = Request.Builder().url(streamUrl).get().build()
                api.pinnedAuthenticatedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty stream")
                    val total = body.contentLength()
                    var read = 0L
                    var lastEmit = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 200L) {
                                    lastEmit = now
                                    val prog = if (total > 0L) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
                                    _active.value = _active.value + (key to DownloadTaskState(key, title, itemType, prog, read, total))
                                }
                            }
                        }
                    }
                    if (destFile.exists()) destFile.delete()
                    tmpFile.renameTo(destFile)

                    val saved = OfflineItem(
                        key = key,
                        itemType = itemType.lowercase(),
                        itemId = itemId,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        isVideo = isVideo,
                        localPath = destFile.absolutePath,
                        sizeBytes = destFile.length(),
                        downloadedAtMs = System.currentTimeMillis(),
                        parentId = parentId,
                    )
                    val nextList = listOf(saved) + _items.value.filterNot { it.key == key }
                    _items.value = nextList
                    saveManifest(nextList)
                    _active.value = _active.value - key

                    // Log to server so admin & user history stay in sync
                    api.logOfflineDownload(
                        itemType = saved.itemType,
                        itemId = saved.itemId,
                        title = saved.title,
                        subtitle = saved.subtitle.ifBlank { null },
                        artwork = saved.artworkUrl?.let { raw ->
                            val b = api.baseUrl.trimEnd('/')
                            if (raw.startsWith(b, ignoreCase = true)) raw.substring(b.length) else raw
                        },
                        deviceId = deviceId,
                        deviceName = deviceName,
                        quality = "original",
                        sizeBytes = saved.sizeBytes,
                    )
                }
            } catch (e: Exception) {
                _active.value = _active.value + (key to DownloadTaskState(key, title, itemType, error = e.localizedMessage ?: "Download failed"))
            }
        }
    }

    fun deleteDownload(api: PlayerApiClient, item: OfflineItem) {
        scope.launch {
            runCatching { File(item.localPath).delete() }
            val next = _items.value.filterNot { it.key == item.key }
            _items.value = next
            saveManifest(next)

            val res = api.markOfflineDownloadRemoved(item.itemType, item.itemId, deviceId)
            if (res !is MusicApiResult.Success) {
                val pending = loadPendingRemovals()
                pending += PendingRemoval(item.itemType, item.itemId)
                savePendingRemovals(pending)
            }
        }
    }

    fun dismissError(key: String) {
        _active.value = _active.value - key
    }
}

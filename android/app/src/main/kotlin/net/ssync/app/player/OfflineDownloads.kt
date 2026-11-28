package net.ssync.app.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ssync.player.DownloadQueue
import net.ssync.player.DownloadTask
import net.ssync.player.OfflineCodec
import net.ssync.player.OfflineEventQueue
import net.ssync.player.OfflineMigration
import net.ssync.player.OfflinePendingOp
import net.ssync.player.OfflineRule
import net.ssync.player.OfflineSyncEvent
import net.ssync.player.TaskState
import net.ssync.player.offlineKey
import java.io.File

data class OfflineSettings(
    val wifiOnly: Boolean = true,
    /** 0 = no limit */
    val limitBytes: Long = 0L,
    /** remembered per kind; blank until someone picks one */
    val videoQuality: String = "",
    val audioQuality: String = "",
    /** auto-delete watched videos: 0 = off, -1 = right away, >0 = hours after watching (#2340) */
    val autoDeleteWatchedVideoHours: Int = 0,
)

/** live numbers for a download in flight (not saved: they change every half second) */
data class LiveProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
)

/** one thing to download, from a plan, a track row or the server's wanted list */
data class OfflineRequest(
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val isVideo: Boolean,
    val quality: String,
    val parentId: String? = null,
    val groupType: String = "",
    val groupId: String = "",
    val groupTitle: String = "",
    val groupArtwork: String? = null,
    val sortIndex: Int = 0,
    val expectedBytes: Long = 0L,
    val reason: String = "manual",
    val watched: Boolean = false,
    val positionS: Double = 0.0,
    val durationS: Double = 0.0,
)

/**
 * the phone's downloads: what's here, what's queued, the settings, and the
 * plays made offline. everything is saved in filesDir/soulsync_offline, so it
 * all works with no connection and survives the app being closed.
 */
object OfflineDownloads {
    private const val PREFS = "soulsync_offline_prefs"
    private val lock = Any()

    private var appContext: Context? = null
    private var dir: File? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live.asStateFlow()

    private val _settings = MutableStateFlow(OfflineSettings())
    val settings: StateFlow<OfflineSettings> = _settings.asStateFlow()

    /** this phone's smart rules as the server last told it */
    private val _rules = MutableStateFlow<List<OfflineRule>>(emptyList())
    val rules: StateFlow<List<OfflineRule>> = _rules.asStateFlow()

    private var events: List<OfflineSyncEvent> = emptyList()
    private var ops: List<OfflinePendingOp> = emptyList()

    val isReady: Boolean get() = dir != null

    fun init(context: Context) {
        synchronized(lock) {
            if (dir != null) return
            val app = context.applicationContext
            appContext = app
            val d = File(app.filesDir, "soulsync_offline").apply { mkdirs() }
            dir = d
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _settings.value = OfflineSettings(
                wifiOnly = sp.getBoolean("wifi_only", true),
                limitBytes = sp.getLong("limit_bytes", 0L),
                videoQuality = sp.getString("video_quality", "").orEmpty(),
                audioQuality = sp.getString("audio_quality", "").orEmpty(),
                autoDeleteWatchedVideoHours = sp.getInt("auto_delete_watched_video_hours", 0),
            )
            val store = File(d, "downloads.json")
            val loaded = if (store.exists()) {
                OfflineCodec.decodeTasks(store.readText())
            } else {
                migrateLegacy(d)
            }
            // a file someone cleared from storage is not a download any more
            val alive = loaded.filter { it.state != TaskState.DOWNLOADED || File(it.localPath).exists() }
            _tasks.value = DownloadQueue.afterRestart(alive)
            if (!store.exists() || alive.size != loaded.size) persist(_tasks.value)
            events = File(d, "events.json").takeIf { it.exists() }?.let { OfflineCodec.decodeEvents(it.readText()) }.orEmpty()
            ops = File(d, "ops.json").takeIf { it.exists() }?.let { OfflineCodec.decodeOps(it.readText()) }.orEmpty()
            _rules.value = File(d, "rules.json").takeIf { it.exists() }?.let { OfflineCodec.decodeRules(it.readText()) }.orEmpty()
        }
        pruneExpiredWatchedVideos()
    }

    /**
     * the first build's downloads come over as they are: files stay where
     * they were, its queued removals go to the server under its old device id
     */
    private fun migrateLegacy(d: File): List<DownloadTask> {
        val manifest = File(d, "manifest.json").takeIf { it.exists() }
            ?.let { OfflineMigration.parseManifest(runCatching { it.readText() }.getOrDefault("")) }.orEmpty()
        val files = d.listFiles().orEmpty().filter { it.isFile }.map {
            OfflineMigration.LegacyFile(it.name, it.absolutePath, it.length(), it.lastModified())
        }
        val migrated = OfflineMigration.migrate(manifest, files)
        val legacyId = OfflineMigration.legacyDeviceId(Build.MODEL.orEmpty())
        val legacyOps = mutableListOf<OfflinePendingOp>()
        File(d, "pending_removals.json").takeIf { it.exists() }?.let { f ->
            OfflineMigration.parseRemovals(runCatching { f.readText() }.getOrDefault("")).forEach {
                legacyOps += OfflinePendingOp(op = "legacy_removed", type = it.itemType, id = it.itemId, quality = legacyId)
            }
            f.delete()
        }
        // the old rows on the server close; the new report logs them under this phone's real id
        migrated.forEach { legacyOps += OfflinePendingOp(op = "legacy_removed", type = it.itemType, id = it.itemId, quality = legacyId) }
        if (legacyOps.isNotEmpty()) {
            ops = ops + legacyOps
            runCatching { File(d, "ops.json").writeText(OfflineCodec.encodeOps(ops)) }
        }
        File(d, "manifest.json").takeIf { it.exists() }?.renameTo(File(d, "manifest.migrated.json"))
        // the old build's half-done files can't be resumed: it kept no record of them
        d.listFiles().orEmpty().filter { it.name.endsWith(".media.part") }.forEach { it.delete() }
        return migrated
    }

    fun directory(): File? = dir

    fun deviceId(context: Context): String =
        PlayerDevicePrefs.layoutDeviceId(context, isTv = false)

    fun deviceName(): String =
        "${Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }} ${Build.MODEL.orEmpty()}".trim()
            .let { name ->
                // "Google Pixel 8" reads better than "Google Google Pixel 8"
                val parts = name.split(' ')
                if (parts.size > 1 && parts[1].equals(parts[0], ignoreCase = true)) parts.drop(1).joinToString(" ") else name
            }
            .ifBlank { "Android phone" }

    // ── tasks ──

    /**
     * the download for an item. a podcast episode is stored under the
     * server's "<podcast_id>:<episode key>" but played under its bare key
     */
    fun task(itemType: String, itemId: String): DownloadTask? {
        val key = offlineKey(itemType, itemId)
        val list = _tasks.value
        return list.firstOrNull { it.key == key }
            ?: if (itemType == "podcast_episode") list.firstOrNull { it.itemType == "podcast_episode" && it.itemId.endsWith(":$itemId") } else null
    }

    fun update(transform: (List<DownloadTask>) -> List<DownloadTask>): List<DownloadTask> = synchronized(lock) {
        val next = transform(_tasks.value)
        if (next != _tasks.value) {
            _tasks.value = next
            persist(next)
        }
        next
    }

    fun updateTask(key: String, transform: (DownloadTask) -> DownloadTask) {
        update { list -> list.map { if (it.key == key) transform(it) else it } }
    }

    private fun persist(list: List<DownloadTask>) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "downloads.json.tmp")
            tmp.writeText(OfflineCodec.encodeTasks(list))
            if (!tmp.renameTo(File(d, "downloads.json"))) {
                File(d, "downloads.json").writeText(OfflineCodec.encodeTasks(list))
                tmp.delete()
            }
        }
    }

    fun setLive(key: String, progress: LiveProgress?) {
        _live.value = if (progress == null) _live.value - key else _live.value + (key to progress)
    }

    /** a file:// uri when [itemType]/[itemId] is downloaded and still on disk */
    fun localUri(itemType: String, itemId: String): String? {
        val t = task(itemType, itemId) ?: return null
        if (t.state != TaskState.DOWNLOADED) return null
        val f = File(t.localPath)
        return if (f.exists()) Uri.fromFile(f).toString() else null
    }

    fun isDownloaded(itemType: String, itemId: String): Boolean = localUri(itemType, itemId) != null

    /** queue [requests]; ones already here or queued are left alone. starts the engine */
    fun enqueue(context: Context, requests: List<OfflineRequest>): Int {
        init(context)
        var added = 0
        val now = System.currentTimeMillis()
        update { list ->
            val have = list.associateBy { it.key }.toMutableMap()
            var order = DownloadQueue.nextOrder(list)
            for (r in requests) {
                val key = offlineKey(r.itemType, r.itemId)
                val existing = have[key]
                if (existing != null && existing.state != TaskState.FAILED) continue
                have[key] = DownloadTask(
                    key = key,
                    itemType = r.itemType.lowercase(),
                    itemId = r.itemId,
                    title = r.title,
                    subtitle = r.subtitle,
                    artwork = r.artwork,
                    isVideo = r.isVideo,
                    parentId = r.parentId,
                    groupType = r.groupType,
                    groupId = r.groupId,
                    groupTitle = r.groupTitle,
                    groupArtwork = r.groupArtwork,
                    sortIndex = r.sortIndex,
                    quality = r.quality,
                    state = TaskState.QUEUED,
                    order = existing?.order ?: order++,
                    expectedBytes = r.expectedBytes,
                    reason = r.reason,
                    watched = r.watched,
                    positionS = r.positionS,
                    durationS = r.durationS,
                    addedAtMs = now,
                )
                added++
            }
            have.values.toList()
        }
        if (added > 0) {
            DownloadService.start(context)
            OfflineSync.requestReport()
        }
        return added
    }

    fun pause(key: String) = updateTask(key) {
        if (it.state.isActive) it.copy(state = TaskState.PAUSED) else it
    }

    fun resume(context: Context, key: String) {
        updateTask(key) {
            if (it.state == TaskState.PAUSED || it.state == TaskState.FAILED) {
                it.copy(state = if (it.prepareId != null && it.fileUrl == null) TaskState.PREPARING else TaskState.QUEUED, error = null, attempts = 0)
            } else it
        }
        DownloadService.start(context)
        OfflineSync.requestReport()
    }

    fun pauseAll() = update { list -> list.map { if (it.state.isActive) it.copy(state = TaskState.PAUSED) else it } }

    fun resumeAll(context: Context) {
        update { list ->
            list.map {
                if (it.state == TaskState.PAUSED || it.state == TaskState.FAILED) {
                    it.copy(state = if (it.prepareId != null && it.fileUrl == null) TaskState.PREPARING else TaskState.QUEUED, error = null, attempts = 0)
                } else it
            }
        }
        DownloadService.start(context)
    }

    fun moveUp(key: String) = update { DownloadQueue.moveUp(it, key) }
    fun moveDown(key: String) = update { DownloadQueue.moveDown(it, key) }
    fun moveToTop(key: String) = update { DownloadQueue.moveToTop(it, key) }

    /**
     * take [keys] off the phone (downloaded or not). something the web or
     * the tv sent here is also taken off the server's wish list, so the next
     * sync doesn't bring it back
     */
    fun remove(keys: Collection<String>, tellServer: Boolean = true) {
        if (keys.isEmpty()) return
        val gone = mutableListOf<DownloadTask>()
        update { list ->
            list.filter { t ->
                if (t.key in keys) {
                    gone += t
                    false
                } else true
            }
        }
        for (t in gone) {
            setLive(t.key, null)
            if (t.localPath.isNotBlank()) runCatching { File(t.localPath).delete() }
            if (t.localArtwork.isNotBlank()) runCatching { File(t.localArtwork).delete() }
            partFile(t)?.let { runCatching { it.delete() } }
            if (tellServer && t.reason == "wish") addOp(OfflinePendingOp(op = "wish_remove", type = t.itemType, id = t.itemId, quality = t.quality))
        }
        if (gone.isNotEmpty()) OfflineSync.requestReport()
    }

    fun partFile(t: DownloadTask): File? = dir?.let { File(it, safeName(t) + ".part") }

    fun safeName(t: DownloadTask): String =
        "${t.itemType}_${t.itemId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)}-${t.quality}"

    fun markWatched(itemType: String, itemId: String, watched: Boolean = true) {
        val key = task(itemType, itemId)?.key ?: return
        val now = System.currentTimeMillis()
        updateTask(key) {
            if (it.watched == watched && (!watched || it.watchedAtMs > 0L)) it
            else it.copy(watched = watched, watchedAtMs = if (watched) now else 0L)
        }
        if (watched) pruneExpiredWatchedVideos(now)
    }

    /** remove downloaded videos past the configured auto-delete-after-viewing window (#2340) */
    fun pruneExpiredWatchedVideos(nowMs: Long = System.currentTimeMillis()): Int {
        val delayHours = _settings.value.autoDeleteWatchedVideoHours
        if (delayHours == 0) return 0
        val expired = DownloadQueue.expiredWatchedVideoKeys(_tasks.value, delayHours, nowMs)
        if (expired.isEmpty()) return 0
        remove(expired, tellServer = false)
        return expired.size
    }

    fun savePosition(itemType: String, itemId: String, positionS: Double, durationS: Double) {
        val key = task(itemType, itemId)?.key ?: return
        updateTask(key) { it.copy(positionS = positionS, durationS = if (durationS > 0) durationS else it.durationS) }
    }

    // ── storage ──

    fun usedBytes(): Long = _tasks.value.sumOf { it.diskBytes }

    fun freeBytes(): Long = runCatching { StatFs((dir ?: return 0L).absolutePath).availableBytes }.getOrDefault(0L)

    fun totalDiskBytes(): Long = runCatching { StatFs((dir ?: return 0L).absolutePath).totalBytes }.getOrDefault(0L)

    // ── settings ──

    fun updateSettings(context: Context, transform: (OfflineSettings) -> OfflineSettings) {
        init(context)
        val before = _settings.value
        val next = transform(before)
        _settings.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("wifi_only", next.wifiOnly)
            .putLong("limit_bytes", next.limitBytes)
            .putString("video_quality", next.videoQuality)
            .putString("audio_quality", next.audioQuality)
            .putInt("auto_delete_watched_video_hours", next.autoDeleteWatchedVideoHours)
            .apply()
        if (next.autoDeleteWatchedVideoHours != 0 && next.autoDeleteWatchedVideoHours != before.autoDeleteWatchedVideoHours) {
            pruneExpiredWatchedVideos()
        }
        if (before.wifiOnly != next.wifiOnly || before.limitBytes != next.limitBytes) {
            // a looser rule may let waiting downloads go now
            update { list -> list.map { if (it.state == TaskState.FAILED && it.error?.startsWith("Over") == true) it.copy(state = TaskState.QUEUED, error = null) else it } }
            DownloadService.start(context)
            OfflineSync.requestReport()
        }
    }

    fun setRules(list: List<OfflineRule>) {
        _rules.value = list
        dir?.let { d -> runCatching { File(d, "rules.json").writeText(OfflineCodec.encodeRules(list)) } }
    }

    // ── offline events and pending server changes ──

    fun addEvent(event: OfflineSyncEvent) = synchronized(lock) {
        events = OfflineEventQueue.add(events, event)
        dir?.let { d -> runCatching { File(d, "events.json").writeText(OfflineCodec.encodeEvents(events)) } }
    }

    fun pendingEvents(): List<OfflineSyncEvent> = synchronized(lock) { events }

    fun eventsSent(sent: List<OfflineSyncEvent>) = synchronized(lock) {
        events = OfflineEventQueue.afterSent(events, sent)
        dir?.let { d -> runCatching { File(d, "events.json").writeText(OfflineCodec.encodeEvents(events)) } }
    }

    fun addOp(op: OfflinePendingOp) = synchronized(lock) {
        if (op in ops) return@synchronized
        ops = ops + op
        dir?.let { d -> runCatching { File(d, "ops.json").writeText(OfflineCodec.encodeOps(ops)) } }
    }

    fun pendingOps(): List<OfflinePendingOp> = synchronized(lock) { ops }

    fun opsDone(done: List<OfflinePendingOp>) = synchronized(lock) {
        ops = ops - done.toSet()
        dir?.let { d -> runCatching { File(d, "ops.json").writeText(OfflineCodec.encodeOps(ops)) } }
    }
}

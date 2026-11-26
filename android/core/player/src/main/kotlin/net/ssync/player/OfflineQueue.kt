package net.ssync.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// the phone's own record of its downloads, and the pure rules over it.
// no android here: everything is unit tested.

@Serializable
enum class TaskState {
    @SerialName("queued") QUEUED,
    @SerialName("preparing") PREPARING, // asked the server, waiting for it to convert
    @SerialName("downloading") DOWNLOADING,
    @SerialName("paused") PAUSED,
    @SerialName("failed") FAILED,
    @SerialName("downloaded") DOWNLOADED,
    ;

    val isActive: Boolean get() = this == QUEUED || this == PREPARING || this == DOWNLOADING
    val isPending: Boolean get() = this != DOWNLOADED
}

@Serializable
data class DownloadTask(
    val key: String,
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    /** artwork path as the server gave it (relative), never a signed cache link */
    val artwork: String? = null,
    val isVideo: Boolean = false,
    val parentId: String? = null,
    /** show / season / album / artist / playlist / podcast / audiobook / movie / youtube */
    val groupType: String = "",
    val groupId: String = "",
    val groupTitle: String = "",
    val groupArtwork: String? = null,
    /** order inside its group (episode / track number) */
    val sortIndex: Int = 0,
    val quality: String = "original",
    val state: TaskState = TaskState.QUEUED,
    /** place in the queue: lower goes first */
    val order: Long = 0L,
    val bytesDone: Long = 0L,
    val totalBytes: Long = 0L,
    /** the size the plan promised, before the server knows the real one */
    val expectedBytes: Long = 0L,
    val prepareId: String? = null,
    /** where the prepared file is served, once the server says it's ready */
    val fileUrl: String? = null,
    val convertProgress: Double = 0.0,
    val etag: String? = null,
    val localPath: String = "",
    /** the artwork saved beside the file, so cards look right with no connection */
    val localArtwork: String = "",
    val error: String? = null,
    val attempts: Int = 0,
    /** manual | wish | rule | migrated */
    val reason: String = "manual",
    val watched: Boolean = false,
    val positionS: Double = 0.0,
    val durationS: Double = 0.0,
    val addedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
    /** set on migrated items that still need their title from the server */
    val needsMeta: Boolean = false,
) {
    /** bytes this download takes on disk now (whole or partial) */
    val diskBytes: Long get() = if (state == TaskState.DOWNLOADED) maxOf(totalBytes, bytesDone) else bytesDone

    /** bytes it still needs to finish */
    val remainingBytes: Long
        get() {
            if (state == TaskState.DOWNLOADED) return 0L
            val whole = if (totalBytes > 0) totalBytes else expectedBytes
            return (whole - bytesDone).coerceAtLeast(0L)
        }

    val progress: Double
        get() = when {
            state == TaskState.DOWNLOADED -> 1.0
            totalBytes > 0 -> (bytesDone.toDouble() / totalBytes).coerceIn(0.0, 1.0)
            expectedBytes > 0 -> (bytesDone.toDouble() / expectedBytes).coerceIn(0.0, 0.99)
            else -> 0.0
        }

    /** what the library calls it: a show's episodes are TV, a playlist's songs are music */
    val shelf: OfflineShelf get() = shelfOf(itemType)
}

enum class OfflineShelf(val label: String) {
    TV("TV"), MOVIES("Movies"), YOUTUBE("YouTube"), HOME("Home videos"), MUSIC("Music"), PODCASTS("Podcasts"), BOOKS("Books")
}

fun shelfOf(itemType: String): OfflineShelf = when (itemType.lowercase()) {
    "episode" -> OfflineShelf.TV
    "movie" -> OfflineShelf.MOVIES
    "youtube" -> OfflineShelf.YOUTUBE
    "home" -> OfflineShelf.HOME
    "podcast_episode", "podcast" -> OfflineShelf.PODCASTS
    "audiobook" -> OfflineShelf.BOOKS
    else -> OfflineShelf.MUSIC
}

object OfflineCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; isLenient = true }
    private val tasks = ListSerializer(DownloadTask.serializer())
    private val events = ListSerializer(OfflineSyncEvent.serializer())
    private val ops = ListSerializer(OfflinePendingOp.serializer())
    private val rules = ListSerializer(OfflineRule.serializer())

    fun encodeTasks(list: List<DownloadTask>): String = json.encodeToString(tasks, list)
    fun decodeTasks(text: String): List<DownloadTask> = runCatching { json.decodeFromString(tasks, text) }.getOrDefault(emptyList())
    fun encodeEvents(list: List<OfflineSyncEvent>): String = json.encodeToString(events, list)
    fun decodeEvents(text: String): List<OfflineSyncEvent> = runCatching { json.decodeFromString(events, text) }.getOrDefault(emptyList())
    fun encodeOps(list: List<OfflinePendingOp>): String = json.encodeToString(ops, list)
    fun decodeOps(text: String): List<OfflinePendingOp> = runCatching { json.decodeFromString(ops, text) }.getOrDefault(emptyList())
    fun encodeRules(list: List<OfflineRule>): String = json.encodeToString(rules, list)
    fun decodeRules(text: String): List<OfflineRule> = runCatching { json.decodeFromString(rules, text) }.getOrDefault(emptyList())
}

/** queue order, reorder and retry rules */
object DownloadQueue {
    /** what's waiting, in the order it runs */
    fun pending(tasks: List<DownloadTask>): List<DownloadTask> =
        tasks.filter { it.state.isPending }.sortedWith(compareBy<DownloadTask> { it.order }.thenBy { it.addedAtMs })

    /** tasks the engine may work on now, in order (paused and failed wait for a person) */
    fun runnable(tasks: List<DownloadTask>): List<DownloadTask> =
        pending(tasks).filter { it.state.isActive }

    fun nextOrder(tasks: List<DownloadTask>): Long = (tasks.maxOfOrNull { it.order } ?: 0L) + 1L

    /**
     * after a restart nothing is really downloading or converting: put those
     * back in the queue, keeping the bytes and the server job so they resume
     */
    fun afterRestart(tasks: List<DownloadTask>): List<DownloadTask> = tasks.map {
        if (it.state == TaskState.DOWNLOADING) it.copy(state = TaskState.QUEUED) else it
    }

    /** move [key] to [toIndex] among the pending tasks; the others keep their order */
    fun move(tasks: List<DownloadTask>, key: String, toIndex: Int): List<DownloadTask> {
        val pending = pending(tasks).toMutableList()
        val from = pending.indexOfFirst { it.key == key }
        if (from < 0) return tasks
        val moving = pending.removeAt(from)
        pending.add(toIndex.coerceIn(0, pending.size), moving)
        val newOrder = pending.mapIndexed { i, t -> t.key to (i + 1).toLong() }.toMap()
        return tasks.map { t -> newOrder[t.key]?.let { t.copy(order = it) } ?: t }
    }

    fun moveUp(tasks: List<DownloadTask>, key: String): List<DownloadTask> {
        val i = pending(tasks).indexOfFirst { it.key == key }
        return if (i <= 0) tasks else move(tasks, key, i - 1)
    }

    fun moveDown(tasks: List<DownloadTask>, key: String): List<DownloadTask> {
        val p = pending(tasks)
        val i = p.indexOfFirst { it.key == key }
        return if (i < 0 || i >= p.lastIndex) tasks else move(tasks, key, i + 1)
    }

    fun moveToTop(tasks: List<DownloadTask>, key: String): List<DownloadTask> = move(tasks, key, 0)

    /** a dropped connection retries on its own this many times before it waits for a person */
    const val MAX_AUTO_ATTEMPTS = 5

    /** wait before the next automatic try: 5s, 15s, 45s... up to 5 minutes */
    fun retryDelayMs(attempts: Int): Long {
        var d = 5_000L
        repeat((attempts - 1).coerceAtLeast(0)) { d = (d * 3).coerceAtMost(300_000L) }
        return d.coerceAtMost(300_000L)
    }
}

/** what the reconcile loop should do, from /wanted against the phone */
data class ReconcilePlan(
    val add: List<OfflineWantedAdd>,
    val remove: List<String>,
)

object OfflineReconcile {
    /**
     * adds the phone doesn't have (in any state but failed-for-good), and
     * removes for what it has. a different quality of something already here
     * is left alone: no re-downloading behind someone's back.
     */
    fun plan(wanted: OfflineWanted, tasks: List<DownloadTask>): ReconcilePlan {
        val have = tasks.associateBy { it.key }
        val removeKeys = wanted.remove.map { it.key }.filter { it in have }.distinct()
        val removeSet = removeKeys.toSet()
        val adds = wanted.add
            .distinctBy { it.key }
            .filter { it.key !in removeSet }
            .filter { a ->
                val t = have[a.key] ?: return@filter true
                // a failed one from the server's list is tried again
                t.state == TaskState.FAILED && t.attempts < DownloadQueue.MAX_AUTO_ATTEMPTS
            }
        return ReconcilePlan(add = adds, remove = removeKeys)
    }

    fun reportState(state: TaskState): String = when (state) {
        TaskState.DOWNLOADED -> "downloaded"
        TaskState.DOWNLOADING, TaskState.PREPARING -> "downloading"
        TaskState.QUEUED -> "queued"
        TaskState.PAUSED -> "paused"
        TaskState.FAILED -> "failed"
    }

    /** the device's whole truth for /devices/report */
    fun reportItems(tasks: List<DownloadTask>): List<OfflineReportItem> = tasks.map { t ->
        OfflineReportItem(
            itemType = t.itemType,
            itemId = t.itemId,
            quality = t.quality,
            sizeBytes = if (t.state == TaskState.DOWNLOADED) t.diskBytes else maxOf(t.totalBytes, t.expectedBytes),
            state = reportState(t.state),
            progress = (t.progress * 1000.0).toLong() / 1000.0,
        )
    }

    /** fill titles and groups the server knows onto the phone's records */
    fun mergeDeviceInfo(tasks: List<DownloadTask>, device: OfflineDevice?): List<DownloadTask> {
        if (device == null) return tasks
        val byKey = device.items.associateBy { it.key }
        return tasks.map { t ->
            val d = byKey[t.key] ?: return@map t
            val raw = d.groupRef()
            val g = raw?.normalized()
            val fill = t.groupType.isBlank() && g != null
            t.copy(
                title = if (t.needsMeta || t.title.isBlank()) d.title?.takeIf { it.isNotBlank() } ?: t.title else t.title,
                subtitle = if (t.subtitle.isBlank()) d.subtitle.orEmpty() else t.subtitle,
                artwork = t.artwork ?: d.artwork,
                groupType = if (fill) g!!.type else t.groupType,
                groupId = if (fill) g!!.id else t.groupId,
                groupTitle = if (t.groupTitle.isBlank() && g != null) g.title else t.groupTitle,
                groupArtwork = t.groupArtwork ?: g?.artwork,
                // a season from the server orders its episodes after the ones before it
                sortIndex = if (t.sortIndex == 0 && raw?.seasonNumber != null) raw.seasonNumber!! * 1000 else t.sortIndex,
                // the server's watch state counts too (watched on another screen)
                watched = t.watched || d.watched,
                positionS = if (t.positionS <= 0.0 && d.positionS > 0.0) d.positionS else t.positionS,
                durationS = if (t.durationS <= 0.0) d.durationS else t.durationS,
                needsMeta = t.needsMeta && d.title.isNullOrBlank(),
            )
        }
    }
}

/** plays, positions and watched marks made offline, kept small and deduped */
object OfflineEventQueue {
    const val MAX_EVENTS = 2000

    fun add(queue: List<OfflineSyncEvent>, event: OfflineSyncEvent): List<OfflineSyncEvent> {
        val next = when (event.kind) {
            // only the newest position of an item matters
            "progress" -> queue.filterNot { it.kind == "progress" && it.itemType == event.itemType && it.itemId == event.itemId } + event
            // one watched mark is enough
            "watched" -> if (queue.any { it.kind == "watched" && it.itemType == event.itemType && it.itemId == event.itemId }) queue else queue + event
            // a play counts once: the server dedupes by device + item + at too
            "play" -> if (queue.any { it.kind == "play" && it.itemType == event.itemType && it.itemId == event.itemId && it.at == event.at }) queue else queue + event
            else -> queue
        }
        if (next.size <= MAX_EVENTS) return next
        // over the cap: drop the oldest positions first, then the oldest of anything
        val over = next.size - MAX_EVENTS
        val oldestProgress = next.filter { it.kind == "progress" }.take(over).toSet()
        val trimmed = next.filterNot { it in oldestProgress }
        return if (trimmed.size <= MAX_EVENTS) trimmed else trimmed.takeLast(MAX_EVENTS)
    }

    /** what's left once [sent] reached the server */
    fun afterSent(queue: List<OfflineSyncEvent>, sent: List<OfflineSyncEvent>): List<OfflineSyncEvent> {
        val gone = sent.toSet()
        return queue.filterNot { it in gone }
    }

    fun batches(queue: List<OfflineSyncEvent>, size: Int = 200): List<List<OfflineSyncEvent>> = queue.chunked(size)
}

enum class FitVerdict { FITS, OVER_LIMIT, NO_SPACE }

data class FitCheck(val verdict: FitVerdict, val roomBytes: Long, val neededBytes: Long) {
    val fits: Boolean get() = verdict == FitVerdict.FITS
}

object OfflineStorage {
    /** left free on the phone whatever the limit says */
    const val RESERVE_BYTES: Long = 512L * 1024 * 1024

    /** room left for downloads: under the limit (0 = no limit) and on the disk */
    fun room(usedBytes: Long, limitBytes: Long, freeBytes: Long): Long {
        val disk = (freeBytes - RESERVE_BYTES).coerceAtLeast(0L)
        if (limitBytes <= 0L) return disk
        return minOf((limitBytes - usedBytes).coerceAtLeast(0L), disk)
    }

    fun check(neededBytes: Long, usedBytes: Long, limitBytes: Long, freeBytes: Long): FitCheck {
        val room = room(usedBytes, limitBytes, freeBytes)
        val verdict = when {
            neededBytes <= room -> FitVerdict.FITS
            limitBytes > 0L && neededBytes > (limitBytes - usedBytes) -> FitVerdict.OVER_LIMIT
            else -> FitVerdict.NO_SPACE
        }
        return FitCheck(verdict, room, neededBytes)
    }

    /**
     * may the engine start [task] now? bytes already on disk for it don't
     * count again, and the rest of the queue doesn't block it
     */
    fun canStart(task: DownloadTask, tasks: List<DownloadTask>, limitBytes: Long, freeBytes: Long): FitCheck {
        val used = tasks.sumOf { it.diskBytes }
        return check(task.remainingBytes, used, limitBytes, freeBytes)
    }

    /**
     * what "free up space" removes: watched things first (biggest first), then
     * the oldest downloads, until [needBytes] is freed. needBytes <= 0 means
     * every watched thing and nothing else.
     */
    fun freeUpPlan(tasks: List<DownloadTask>, needBytes: Long): List<DownloadTask> {
        val done = tasks.filter { it.state == TaskState.DOWNLOADED }
        val watched = done.filter { it.watched }.sortedByDescending { it.diskBytes }
        if (needBytes <= 0L) return watched
        val rest = done.filterNot { it.watched }.sortedBy { it.completedAtMs }
        val out = mutableListOf<DownloadTask>()
        var freed = 0L
        for (t in watched + rest) {
            if (freed >= needBytes) break
            out += t
            freed += t.diskBytes
        }
        return out
    }

    fun bytesByShelf(tasks: List<DownloadTask>): Map<OfflineShelf, Long> =
        tasks.groupBy { it.shelf }.mapValues { (_, v) -> v.sumOf { it.diskBytes } }.filterValues { it > 0 }
}

/** one card on the Downloads screen: a show, an album, a playlist, a movie */
data class OfflineGroup(
    val shelf: OfflineShelf,
    val groupType: String,
    val groupId: String,
    val title: String,
    val artwork: String?,
    val tasks: List<DownloadTask>,
) {
    val key: String get() = "$groupType:$groupId"
    val downloaded: List<DownloadTask> get() = tasks.filter { it.state == TaskState.DOWNLOADED }
    val bytes: Long get() = tasks.sumOf { it.diskBytes }
    val watched: List<DownloadTask> get() = downloaded.filter { it.watched }
    val watchedBytes: Long get() = watched.sumOf { it.diskBytes }
}

object OfflineGrouping {
    /** downloaded and pending items folded into cards, newest first in each shelf */
    fun groups(tasks: List<DownloadTask>): List<OfflineGroup> {
        val byGroup = tasks.groupBy { t ->
            when {
                t.groupId.isNotBlank() -> "${normalGroupType(t)}:${t.groupId}"
                t.itemType == "episode" && !t.parentId.isNullOrBlank() -> "show:${t.parentId}"
                t.itemType == "track" && !t.parentId.isNullOrBlank() -> "album:${t.parentId}"
                else -> "${t.itemType}:${t.itemId}"
            }
        }
        return byGroup.map { (k, list) ->
            val first = list.first()
            val type = k.substringBefore(':')
            val id = k.substringAfter(':')
            val single = list.size == 1 && first.groupId.isBlank()
            OfflineGroup(
                shelf = first.shelf,
                groupType = type,
                groupId = id,
                title = when {
                    single -> first.title
                    first.groupTitle.isNotBlank() -> first.groupTitle
                    else -> first.subtitle.ifBlank { first.title }
                },
                artwork = first.groupArtwork ?: first.artwork,
                tasks = list.sortedWith(compareBy<DownloadTask> { it.sortIndex }.thenBy { it.addedAtMs }),
            )
        }.sortedByDescending { g -> g.tasks.maxOf { maxOf(it.completedAtMs, it.addedAtMs) } }
    }

    // a season's episodes sit with their show
    private fun normalGroupType(t: DownloadTask): String = when (t.groupType) {
        "season" -> "show"
        "" -> t.itemType
        else -> t.groupType
    }

    /** "S2 E5" from a sort index of season * 1000 + episode */
    fun episodeLabel(sortIndex: Int): String? =
        if (sortIndex >= 1000) "S${sortIndex / 1000} E${sortIndex % 1000}" else null
}

/** bytes and speeds the way people read them */
object OfflineFormat {
    fun bytes(n: Long): String {
        if (n <= 0L) return "0 MB"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            n >= gb -> trim(n / gb) + " GB"
            n >= mb -> "${(n / mb).toLong().coerceAtLeast(1)} MB"
            else -> "${(n / kb).toLong().coerceAtLeast(1)} KB"
        }
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return ""
        val mb = 1024.0 * 1024
        return if (bytesPerSecond >= mb) trim(bytesPerSecond / mb) + " MB/s" else "${bytesPerSecond / 1024} KB/s"
    }

    private fun trim(v: Double): String =
        if (v >= 100) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")

    /** a plan's quality id as a short tag for a row ("720p", "320 kbps") */
    fun qualityTag(quality: String): String = when (quality) {
        "original" -> "Original"
        "high" -> "1080p"
        "phone" -> "720p"
        "small" -> "480p"
        "320", "256", "128" -> "$quality kbps"
        else -> quality
    }
}

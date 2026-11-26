package net.ssync.app.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

data class ActivePlayable(
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val streamUrl: String,
    val isVideo: Boolean = false,
    val resumePositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val parentId: String? = null,
    val partyId: String? = null,
    /** True when playing from the Live Guide: never save Continue Watching progress; only mark movies watched at >=92%. */
    val isLiveGuide: Boolean = false,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelNumber: Int? = null,
)

/**
 * Coordinates unified playback across all SoulSync `/player` media types:
 * - Audio (Music tracks, Podcasts, Audiobooks, Music Channels) via Media3 [MediaController]
 * - Video (Movies, TV Episodes, YouTube, Live Video Guide) via full-screen [VideoPlayerOverlay]
 * - Progress reporting (`PUT /api/player/progress`) and live presence (`POST /api/player/presence`)
 */
@Stable
class PlaybackCoordinator(
    /**
     * the current client. it's replaced when the phone moves between wi-fi
     * and mobile data (another base url) or the profile changes; the
     * coordinator stays, so what's playing doesn't vanish mid-video
     */
    api: PlayerApiClient,
    private val scope: CoroutineScope,
    private val controllerProvider: () -> MediaController?,
    private val castHandler: (suspend (ActivePlayable, List<ActivePlayable>) -> Boolean)? = null,
) {
    var api: PlayerApiClient = api

    var activeItem by mutableStateOf<ActivePlayable?>(null)
        private set
    var queue by mutableStateOf<List<ActivePlayable>>(emptyList())
        private set
    var videoQueue by mutableStateOf<List<ActivePlayable>>(emptyList())
        private set
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playbackSpeed by mutableFloatStateOf(1.0f)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(androidx.media3.common.Player.REPEAT_MODE_OFF)
        private set
    var showAudioSheet by mutableStateOf(false)
    var showVideoOverlay by mutableStateOf(false)
    var isVideoMinimized by mutableStateOf(false)
    var lyricsText by mutableStateOf<String?>(null)
        private set

    /** Registered by GuideScreen/HomeScreen so VideoPlayerOverlay can surf live channels (CH ▲ / CH ▼). */
    var liveChannelSurfer by mutableStateOf<((currentChannelId: String, delta: Int) -> Unit)?>(null)

    /** Registered by GuideScreen/HomeScreen so a 24/7 Live Guide channel refills its upcoming schedule forever. */
    var liveChannelRefiller by mutableStateOf<((channelId: String) -> List<ActivePlayable>)?>(null)

    fun surfLiveChannel(delta: Int) {
        val currentCh = activeItem?.channelId ?: return
        liveChannelSurfer?.invoke(currentCh, delta)
    }

    /**
     * a downloaded item plays from its file. offline, it also resumes where
     * it was left (the server's position can't be asked) and its play is
     * kept to send when the connection is back
     */
    private fun resolveOffline(item: ActivePlayable): ActivePlayable {
        val local = OfflineDownloads.localUri(item.itemType, item.itemId) ?: return item
        val task = OfflineDownloads.task(item.itemType, item.itemId)
        val resume = if (item.resumePositionMs <= 0L && task != null && !task.watched && task.positionS > 10) {
            (task.positionS * 1000).toLong()
        } else {
            item.resumePositionMs
        }
        return item.copy(streamUrl = local, resumePositionMs = resume)
    }

    private fun isLocalFile(item: ActivePlayable): Boolean = item.streamUrl.startsWith("file:")

    /** a play of a downloaded item while offline counts once the server is back */
    private fun notePlayStart(item: ActivePlayable) {
        if (isLocalFile(item) && !OfflineSync.online.value) {
            OfflineSync.queueEvent("play", item.itemType, item.itemId)
        }
    }

    /**
     * save a position (and watched mark) on the server, or keep it for later
     * when the server can't be reached; downloads also remember it locally
     */
    private suspend fun saveOrQueue(
        item: ActivePlayable,
        positionMs: Long,
        durationMs: Long,
        finished: Boolean,
        markWatched: Boolean,
    ) {
        val pos = positionMs / 1000.0
        val dur = durationMs.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0
        OfflineSync.noteLocal(item.itemType, item.itemId, pos, dur, markWatched || finished)
        val saved = if (OfflineSync.online.value || !isLocalFile(item)) {
            api.saveProgress(
                itemType = item.itemType,
                itemId = item.itemId,
                positionSeconds = pos,
                durationSeconds = dur.takeIf { it > 0 },
                finished = finished,
                meta = buildProgressMeta(item),
            )
        } else {
            MusicApiResult.Error("offline")
        }
        if (saved is MusicApiResult.Error) {
            if (isLocalFile(item) || !OfflineSync.online.value) OfflineSync.queueEvent("progress", item.itemType, item.itemId, pos, dur)
        }
        if (markWatched) {
            val w = if (saved is MusicApiResult.Success) api.markWatched(itemType = item.itemType, itemId = item.itemId) else saved
            if (w is MusicApiResult.Error && (isLocalFile(item) || !OfflineSync.online.value)) {
                OfflineSync.queueEvent("watched", item.itemType, item.itemId, pos, dur)
            }
        }
    }

    fun playAudio(item: ActivePlayable, upcoming: List<ActivePlayable> = emptyList()) {
        val resolvedItem = resolveOffline(item)
        val resolvedUpcoming = upcoming.map { resolveOffline(it) }
        showVideoOverlay = false
        isVideoMinimized = false
        activeItem = resolvedItem
        queue = listOf(resolvedItem) + resolvedUpcoming
        notePlayStart(resolvedItem)
        // an earlier video session's queue mustn't start on the tv when this ends
        videoQueue = emptyList()
        lyricsText = null

        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(item, queue.drop(1))
                if (handledByCast) {
                    controllerProvider()?.pause()
                    audioOnCast = true
                    isPlaying = true
                    reportPresence(item, paused = false)
                    return@launch
                }
                startLocalAudio(resolvedItem)
            }
            return
        }
        startLocalAudio(resolvedItem)
    }

    /** Append a track to the active playback queue (or start playing if nothing is active). */
    fun enqueueAudio(item: ActivePlayable) {
        if (activeItem == null) {
            playAudio(item)
            return
        }
        queue = queue + item
        controllerProvider()?.addMediaItem(toMediaItem(item))
    }

    private fun startLocalAudio(item: ActivePlayable) {
        audioOnCast = false
        val controller = controllerProvider()
        if (controller != null) {
            val mediaItems = queue.map { toMediaItem(it) }
            controller.setMediaItems(mediaItems, 0, item.resumePositionMs)
            controller.setPlaybackSpeed(playbackSpeed)
            controller.prepare()
            controller.play()
            isPlaying = true
        }

        if (item.itemType == "track") {
            scope.launch {
                val res = api.fetchTrackLyrics(item.itemId)
                if (res is MusicApiResult.Success) {
                    lyricsText = res.data
                }
            }
        }
        reportPresence(item, paused = false)
    }

    fun playVideo(item: ActivePlayable, upcoming: List<ActivePlayable> = emptyList()) {
        // Pause background audio when starting video
        controllerProvider()?.pause()
        showAudioSheet = false
        isVideoMinimized = false
        val videoItem = resolveOffline(item).copy(isVideo = true)
        val resolvedUpcoming = upcoming.map { resolveOffline(it).copy(isVideo = true) }
        notePlayStart(videoItem)
        videoQueue = listOf(videoItem) + resolvedUpcoming
        activeItem = videoItem
        positionMs = videoItem.resumePositionMs.coerceAtLeast(0L)
        durationMs = videoItem.durationMs.coerceAtLeast(0L)
        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(videoItem, resolvedUpcoming)
                if (handledByCast) {
                    showVideoOverlay = false
                    isPlaying = true
                    reportPresence(videoItem, paused = false, overridePositionMs = positionMs, overrideDurationMs = durationMs)
                    return@launch
                }
                showVideoOverlay = true
                isPlaying = true
                reportPresence(videoItem, paused = false, overridePositionMs = positionMs, overrideDurationMs = durationMs)
            }
            return
        }
        showVideoOverlay = true
        isPlaying = true
        reportPresence(videoItem, paused = false, overridePositionMs = positionMs, overrideDurationMs = durationMs)
    }

    /**
     * Called when Chromecast's native media queue advances on its own to the next
     * queued item on the TV. Keeps activeItem, videoQueue, and Live Presence in sync
     * without interrupting Chromecast playback.
     */
    fun syncCastAdvancedTo(title: String?, key: String? = null) {
        if (videoQueue.size <= 1) return
        val finished = activeItem
        if (finished != null && finished.isLiveGuide && finished.itemType == "movie") {
            scope.launch {
                api.markWatched(itemType = "movie", itemId = finished.itemId)
            }
        }
        // the item's own key first; a title only for media cast before keys
        val byKey = key?.let { k -> videoQueue.indexOfFirst { "${it.itemType}:${it.itemId}" == k }.takeIf { it > 0 } }
        val idx = byKey ?: if (!title.isNullOrBlank()) {
            videoQueue.indexOfFirst { it.title.equals(title, ignoreCase = true) }.takeIf { it > 0 } ?: 1
        } else {
            1
        }
        val remaining = videoQueue.drop(idx)
        if (remaining.isEmpty()) return
        val next = remaining.first().copy(resumePositionMs = 0L)
        videoQueue = remaining
        activeItem = next
        positionMs = 0L
        durationMs = next.durationMs.coerceAtLeast(0L)
        isPlaying = true
        reportPresence(next, paused = false, overridePositionMs = 0L, overrideDurationMs = durationMs)
    }

    /** Hide the local video overlay when handing off playback to Chromecast while keeping activeItem & videoQueue intact. */
    fun hideLocalVideoForCast() {
        handingOffToCast = true
        showVideoOverlay = false
        isVideoMinimized = false
    }

    /** the local overlay is closing because the tv took over: keep the queue */
    private var handingOffToCast = false

    /** Advance to the next queued video (e.g., next scheduled program on a Live Guide channel). */
    fun playNextVideo(): Boolean {
        val finished = activeItem
        if (finished != null && finished.isLiveGuide && finished.itemType == "movie") {
            scope.launch {
                api.markWatched(itemType = "movie", itemId = finished.itemId)
            }
        }
        var remaining = videoQueue.drop(1)
        // Refill the Live Guide channel schedule dynamically so 24/7 channels NEVER stop!
        if (remaining.isEmpty() && finished != null && finished.isLiveGuide && !finished.channelId.isNullOrBlank()) {
            val refilled = liveChannelRefiller?.invoke(finished.channelId!!)
                ?.map { resolveOffline(it).copy(isVideo = true) }
                .orEmpty()
            if (refilled.isNotEmpty()) {
                remaining = refilled
            }
        }
        if (remaining.isEmpty()) return false
        val next = remaining.first().copy(resumePositionMs = 0L)
        // keep a Live Guide queue topped up. rebuilding every channel is heavy:
        // it runs off the main thread (it janked or stalled the tv at each
        // program change) and lands on the queue when it's done
        val refill = liveChannelRefiller
        if (remaining.size <= 3 && next.isLiveGuide && !next.channelId.isNullOrBlank() && refill != null) {
            val channelId = next.channelId!!
            scope.launch {
                val extra = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    refill(channelId).map { resolveOffline(it).copy(isVideo = true) }
                }
                if (activeItem?.channelId != channelId) return@launch
                val have = videoQueue
                val add = extra.filter { cand -> have.none { it.itemType == cand.itemType && it.itemId == cand.itemId } }
                if (add.isNotEmpty()) videoQueue = have + add
            }
        }
        videoQueue = remaining
        activeItem = next
        positionMs = 0L
        durationMs = next.durationMs.coerceAtLeast(0L)
        isPlaying = true
        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(next, remaining.drop(1))
                if (handledByCast) {
                    showVideoOverlay = false
                }
                reportPresence(next, paused = false, overridePositionMs = 0L, overrideDurationMs = durationMs)
            }
        } else {
            reportPresence(next, paused = false, overridePositionMs = 0L, overrideDurationMs = durationMs)
        }
        return true
    }

    private fun toRelativeArtwork(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val base = api.baseUrl.trimEnd('/')
        if (raw.startsWith(base, ignoreCase = true)) {
            val rel = raw.substring(base.length)
            if (rel.startsWith("/")) return rel
        }
        return raw
    }

    private fun buildProgressMeta(item: ActivePlayable): net.ssync.player.ProgressMeta {
        val href = when (item.itemType) {
            "movie" -> "/player/movies/${item.itemId}"
            "show" -> "/player/tv/${item.itemId}"
            "episode" -> item.parentId?.let { "/player/tv/$it" } ?: "/player/tv"
            "youtube" -> "/player/youtube"
            "home" -> "/player/home-videos"
            "audiobook" -> "/player/audiobooks/${item.itemId}"
            "podcast", "podcast_episode" -> "/player/podcasts"
            else -> "/player/music"
        }
        val relArt = toRelativeArtwork(item.artworkUrl)
        return net.ssync.player.ProgressMeta(
            title = item.title.takeIf { it.isNotBlank() },
            subtitle = item.subtitle.takeIf { it.isNotBlank() },
            artwork = relArt,
            backdrop = relArt,
            href = href,
        )
    }

    fun minimizeVideo(currentPositionMs: Long, currentDurationMs: Long) {
        val current = activeItem ?: return
        if (!current.isVideo) return
        positionMs = currentPositionMs.coerceAtLeast(0L)
        if (currentDurationMs > 0L) durationMs = currentDurationMs
        showVideoOverlay = false
        isVideoMinimized = true
    }

    fun expandVideo() {
        val current = activeItem ?: return
        if (!current.isVideo) return
        isVideoMinimized = false
        showVideoOverlay = true
    }

    fun detachWatchParty() {
        val current = activeItem ?: return
        if (current.partyId != null) {
            activeItem = current.copy(partyId = null)
        }
    }

    /**
     * the overlay for [closed] went away. its position is saved under [closed],
     * never under whatever is active now: the overlay also goes when the next
     * program, a channel surf or a new video replaces it, and reading
     * activeItem then closed the new one and saved the old position on it.
     * the player only shuts when [closed] is still what's active (the person
     * closed it), and never on a hand-off to cast.
     */
    fun closeVideo(closed: ActivePlayable, lastPositionMs: Long, totalDurationMs: Long) {
        val current = closed
        val stillActive = activeItem?.let { it.itemId == closed.itemId && it.streamUrl == closed.streamUrl } == true
        val toCast = handingOffToCast
        handingOffToCast = false
        if (stillActive && !toCast) {
            showVideoOverlay = false
            isVideoMinimized = false
            isPlaying = false
            activeItem = null
            videoQueue = emptyList()
        }
        run {
            scope.launch {
                // a hand-off to the next item (or the tv) isn't leaving the party
                if (stillActive && !toCast && !current.partyId.isNullOrBlank()) {
                    api.leaveWatchParty(current.partyId)
                }
                val reachedCredits = totalDurationMs > 0 && lastPositionMs >= (totalDurationMs * 0.92)
                if (current.isLiveGuide) {
                    // Guide playback never appears in Continue Watching; only tracks watched state for movies.
                    if (reachedCredits && current.itemType == "movie") {
                        api.markWatched(itemType = "movie", itemId = current.itemId)
                    }
                } else {
                    saveOrQueue(
                        item = current,
                        positionMs = lastPositionMs,
                        durationMs = totalDurationMs,
                        finished = reachedCredits,
                        markWatched = reachedCredits && (current.itemType == "movie" || current.itemType == "episode" || current.itemType == "youtube" || current.itemType == "home"),
                    )
                }
                if (stillActive && !toCast) api.clearPresence()
            }
        }
    }

    fun togglePlayPause() {
        val controller = controllerProvider() ?: return
        if (controller.isPlaying) {
            controller.pause()
            isPlaying = false
            activeItem?.let { reportPresence(it, paused = true) }
        } else {
            controller.play()
            isPlaying = true
            activeItem?.let { reportPresence(it, paused = false) }
        }
    }

    fun skipNext() {
        controllerProvider()?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        controllerProvider()?.seekToPreviousMediaItem()
    }

    fun stopPlayback() {
        controllerProvider()?.run {
            stop()
            clearMediaItems()
        }
        showAudioSheet = false
        showVideoOverlay = false
        isVideoMinimized = false
        isPlaying = false
        activeItem = null
        queue = emptyList()
        videoQueue = emptyList()
        positionMs = 0L
        durationMs = 0L
        lyricsText = null
        scope.launch {
            api.clearPresence()
        }
    }

    fun seekTo(ms: Long) {
        controllerProvider()?.seekTo(ms.coerceAtLeast(0L))
        positionMs = ms.coerceAtLeast(0L)
    }

    fun cycleSpeed() {
        val next = when (playbackSpeed) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        playbackSpeed = next
        controllerProvider()?.setPlaybackSpeed(next)
    }

    fun toggleShuffle() {
        val next = !shuffleEnabled
        shuffleEnabled = next
        controllerProvider()?.shuffleModeEnabled = next
    }

    fun cycleRepeatMode() {
        val next = when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        repeatMode = next
        controllerProvider()?.repeatMode = next
    }

    private fun fromMediaItem(mediaItem: MediaItem): ActivePlayable {
        val meta = mediaItem.mediaMetadata
        val extras = meta.extras
        val itemType = extras?.getString("itemType")?.takeIf { it.isNotBlank() } ?: "track"
        val parentId = extras?.getString("parentId")?.takeIf { it.isNotBlank() }
        val artUrl = extras?.getString("artworkUrl")?.takeIf { it.isNotBlank() }
            ?: meta.artworkUri?.toString()?.takeIf { it.isNotBlank() }
        val stream = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        return ActivePlayable(
            itemType = itemType,
            itemId = mediaItem.mediaId,
            title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: "Track",
            subtitle = meta.artist?.toString().orEmpty(),
            artworkUrl = artUrl,
            streamUrl = stream,
            isVideo = false,
            parentId = parentId,
        )
    }

    /**
     * Rehydrates [activeItem], [queue], position, duration, and transport state directly from
     * Media3's live [MediaController]. Called when the Activity resumes (e.g. unlocking the phone
     * in the car over Bluetooth), when MediaController connects, or on any Player.Listener event.
     */
    fun rehydrateFromController() {
        if (audioOnCast) return
        val controller = controllerProvider() ?: return
        val cur = activeItem
        if (cur != null && cur.isVideo) return

        val currentMedia = controller.currentMediaItem ?: return
        val playingOrReady = controller.isPlaying ||
            controller.playbackState == androidx.media3.common.Player.STATE_READY ||
            controller.playbackState == androidx.media3.common.Player.STATE_BUFFERING

        if (cur == null && !playingOrReady) return

        // Rebuild queue if empty or out of sync with MediaController's live playlist
        val count = controller.mediaItemCount
        if (count > 0 && (queue.isEmpty() || queue.none { it.itemId == currentMedia.mediaId })) {
            val rebuilt = buildList(count) {
                for (i in 0 until count) {
                    val mi = controller.getMediaItemAt(i)
                    val existing = queue.firstOrNull { it.itemId == mi.mediaId }
                    add(existing ?: fromMediaItem(mi))
                }
            }
            if (rebuilt.isNotEmpty()) queue = rebuilt
        }

        val resolved = queue.firstOrNull { it.itemId == currentMedia.mediaId } ?: fromMediaItem(currentMedia)
        if (activeItem?.itemId != resolved.itemId) {
            activeItem = resolved
            lyricsText = null
            if (resolved.itemType == "track") {
                scope.launch {
                    val res = api.fetchTrackLyrics(resolved.itemId)
                    if (res is MusicApiResult.Success && activeItem?.itemId == resolved.itemId) {
                        lyricsText = res.data
                    }
                }
            }
        }
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        durationMs = controller.duration.coerceAtLeast(0L)
        isPlaying = controller.isPlaying
        shuffleEnabled = controller.shuffleModeEnabled
        repeatMode = controller.repeatMode
    }

    private var lastAudioPresenceBeatMs = 0L

    /** the audio is on a cast device: the local controller holds a paused old item */
    private var audioOnCast = false
    private var lastProgressSaveMs = 0L
    private var wasPlayingAudio = false

    fun syncAudioProgress() {
        // the tv is playing it: the paused local player says nothing true about it
        if (audioOnCast) return
        val controller = controllerProvider() ?: return
        var item = activeItem
        if (item == null) {
            rehydrateFromController()
            item = activeItem ?: return
        }
        if (item.isVideo) return

        // the queue moved on (skip, auto-advance, Bluetooth AVRCP, crossfade):
        // ALWAYS sync to controller.currentMediaItem even if queue was recreated!
        val currentMedia = controller.currentMediaItem
        val playingId = currentMedia?.mediaId
        if (playingId != null && playingId != item.itemId) {
            val next = queue.firstOrNull { it.itemId == playingId } ?: fromMediaItem(currentMedia)
            if (queue.none { it.itemId == playingId }) {
                rehydrateFromController()
            }
            activeItem = next
            item = next
            lyricsText = null
            notePlayStart(next)
            if (next.itemType == "track") {
                scope.launch {
                    val res = api.fetchTrackLyrics(next.itemId)
                    if (res is MusicApiResult.Success && activeItem?.itemId == next.itemId) lyricsText = res.data
                }
            }
            reportPresence(next, paused = !controller.isPlaying, overridePositionMs = 0L, overrideDurationMs = controller.duration.coerceAtLeast(0L))
        }
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        durationMs = controller.duration.coerceAtLeast(0L)
        isPlaying = controller.isPlaying
        shuffleEnabled = controller.shuffleModeEnabled
        repeatMode = controller.repeatMode

        val now = System.currentTimeMillis()
        if (now - lastAudioPresenceBeatMs >= 10_000L) {
            lastAudioPresenceBeatMs = now
            reportPresence(item, paused = !isPlaying, overridePositionMs = positionMs, overrideDurationMs = durationMs)
        }

        // every 15 seconds while it plays, and once as it pauses: it was every
        // second, paused or not
        val pausedNow = wasPlayingAudio && !isPlaying
        val due = isPlaying && now - lastProgressSaveMs >= 15_000L
        wasPlayingAudio = isPlaying
        if ((due || pausedNow) && !item.isLiveGuide && positionMs > 0 &&
            item.itemType in setOf("podcast_episode", "audiobook")
        ) {
            lastProgressSaveMs = now
            val saving = item
            val at = positionMs
            val length = durationMs
            scope.launch {
                val finished = length > 0 && at >= length * 0.95
                saveOrQueue(saving, at, length, finished = finished, markWatched = false)
            }
        }
    }

    private fun reportPresence(
        item: ActivePlayable,
        paused: Boolean,
        overridePositionMs: Long? = null,
        overrideDurationMs: Long? = null,
    ) {
        val posMs = (overridePositionMs ?: positionMs.takeIf { it > 0L } ?: item.resumePositionMs).coerceAtLeast(0L)
        val durMs = (overrideDurationMs ?: durationMs.takeIf { it > 0L } ?: item.durationMs).coerceAtLeast(0L)
        val channelRef = item.channelId?.takeIf { it.isNotBlank() }?.let { cid ->
            net.ssync.player.PresenceChannelRef(
                id = cid,
                name = item.channelName.orEmpty(),
                number = item.channelNumber,
            )
        }
        scope.launch {
            api.sendPresence(
                itemType = item.itemType,
                itemId = item.itemId,
                title = item.title,
                subtitle = item.subtitle.ifBlank { null },
                artwork = toRelativeArtwork(item.artworkUrl),
                positionSeconds = posMs / 1000.0,
                durationSeconds = durMs.takeIf { it > 0L }?.let { it / 1000.0 },
                paused = paused,
                live = item.isLiveGuide,
                channel = channelRef,
            )
        }
    }

    private fun toMediaItem(item: ActivePlayable): MediaItem {
        val extras = android.os.Bundle().apply {
            putString("itemType", item.itemType)
            if (!item.parentId.isNullOrBlank()) putString("parentId", item.parentId)
            if (!item.artworkUrl.isNullOrBlank()) putString("artworkUrl", item.artworkUrl)
        }
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.subtitle)
            .setExtras(extras)
        if (!item.artworkUrl.isNullOrBlank()) {
            runCatching { metaBuilder.setArtworkUri(android.net.Uri.parse(item.artworkUrl)) }
        }
        return MediaItem.Builder()
            .setMediaId(item.itemId)
            .setUri(item.streamUrl)
            .setCustomCacheKey("${item.itemType}:${item.itemId}")
            .setMediaMetadata(metaBuilder.build())
            .build()
    }
}

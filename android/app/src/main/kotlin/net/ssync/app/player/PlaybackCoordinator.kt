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
    private val api: PlayerApiClient,
    private val scope: CoroutineScope,
    private val controllerProvider: () -> MediaController?,
    private val castHandler: (suspend (ActivePlayable, List<ActivePlayable>) -> Boolean)? = null,
) {
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

    private fun resolveOffline(item: ActivePlayable): ActivePlayable {
        val local = OfflineDownloadManager.localUriIfDownloaded(item.itemType, item.itemId)
        return if (local != null) item.copy(streamUrl = local) else item
    }

    fun playAudio(item: ActivePlayable, upcoming: List<ActivePlayable> = emptyList()) {
        val resolvedItem = resolveOffline(item)
        val resolvedUpcoming = upcoming.map { resolveOffline(it) }
        showVideoOverlay = false
        isVideoMinimized = false
        activeItem = resolvedItem
        queue = listOf(resolvedItem) + resolvedUpcoming
        lyricsText = null

        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(item, queue.drop(1))
                if (handledByCast) {
                    controllerProvider()?.pause()
                    isPlaying = true
                    reportPresence(item, paused = false)
                    return@launch
                }
                startLocalAudio(item)
            }
            return
        }
        startLocalAudio(item)
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
    fun syncCastAdvancedTo(title: String?) {
        if (videoQueue.size <= 1) return
        val finished = activeItem
        if (finished != null && finished.isLiveGuide && finished.itemType == "movie") {
            scope.launch {
                api.markWatched(itemType = "movie", itemId = finished.itemId)
            }
        }
        val idx = if (!title.isNullOrBlank()) {
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
        showVideoOverlay = false
        isVideoMinimized = false
    }

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
        // Keep queue topped up when running low on a Live Guide channel
        if (remaining.size <= 3 && next.isLiveGuide && !next.channelId.isNullOrBlank()) {
            val extra = liveChannelRefiller?.invoke(next.channelId!!)
                ?.map { resolveOffline(it).copy(isVideo = true) }
                ?.filter { cand -> remaining.none { it.itemType == cand.itemType && it.itemId == cand.itemId } }
                .orEmpty()
            if (extra.isNotEmpty()) {
                remaining = remaining + extra
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

    fun closeVideo(lastPositionMs: Long, totalDurationMs: Long) {
        val current = activeItem
        showVideoOverlay = false
        isVideoMinimized = false
        isPlaying = false
        if (current?.isVideo == true) {
            activeItem = null
            videoQueue = emptyList()
        }
        if (current != null) {
            scope.launch {
                if (!current.partyId.isNullOrBlank()) {
                    api.leaveWatchParty(current.partyId)
                }
                val reachedCredits = totalDurationMs > 0 && lastPositionMs >= (totalDurationMs * 0.92)
                if (current.isLiveGuide) {
                    // Guide playback never appears in Continue Watching; only tracks watched state for movies.
                    if (reachedCredits && current.itemType == "movie") {
                        api.markWatched(itemType = "movie", itemId = current.itemId)
                    }
                } else {
                    api.saveProgress(
                        itemType = current.itemType,
                        itemId = current.itemId,
                        positionSeconds = lastPositionMs / 1000.0,
                        durationSeconds = totalDurationMs.takeIf { it > 0 }?.let { it / 1000.0 },
                        finished = reachedCredits,
                        meta = buildProgressMeta(current),
                    )
                    if (reachedCredits && (current.itemType == "movie" || current.itemType == "episode")) {
                        api.markWatched(itemType = current.itemType, itemId = current.itemId)
                    }
                }
                api.clearPresence()
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

    private var lastAudioPresenceBeatMs = 0L

    fun syncAudioProgress() {
        val item = activeItem ?: return
        if (item.isVideo) return
        val controller = controllerProvider() ?: return
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        durationMs = controller.duration.coerceAtLeast(0L)
        isPlaying = controller.isPlaying

        val now = System.currentTimeMillis()
        if (now - lastAudioPresenceBeatMs >= 10_000L) {
            lastAudioPresenceBeatMs = now
            reportPresence(item, paused = !isPlaying, overridePositionMs = positionMs, overrideDurationMs = durationMs)
        }

        if (!item.isLiveGuide && positionMs > 0 && item.itemType in setOf("podcast_episode", "audiobook")) {
            scope.launch {
                api.saveProgress(
                    itemType = item.itemType,
                    itemId = item.itemId,
                    positionSeconds = positionMs / 1000.0,
                    durationSeconds = durationMs.takeIf { it > 0 }?.let { it / 1000.0 },
                    meta = buildProgressMeta(item),
                )
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
        }
        return MediaItem.Builder()
            .setMediaId(item.itemId)
            .setUri(item.streamUrl)
            .setCustomCacheKey("${item.itemType}:${item.itemId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }
}

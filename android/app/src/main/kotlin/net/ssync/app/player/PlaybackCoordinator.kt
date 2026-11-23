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
    private val castHandler: (suspend (ActivePlayable) -> Boolean)? = null,
) {
    var activeItem by mutableStateOf<ActivePlayable?>(null)
        private set
    var queue by mutableStateOf<List<ActivePlayable>>(emptyList())
        private set
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playbackSpeed by mutableFloatStateOf(1.0f)
        private set
    var showAudioSheet by mutableStateOf(false)
    var showVideoOverlay by mutableStateOf(false)
    var lyricsText by mutableStateOf<String?>(null)
        private set

    private fun resolveOffline(item: ActivePlayable): ActivePlayable {
        val local = OfflineDownloadManager.localUriIfDownloaded(item.itemType, item.itemId)
        return if (local != null) item.copy(streamUrl = local) else item
    }

    fun playAudio(item: ActivePlayable, upcoming: List<ActivePlayable> = emptyList()) {
        val resolvedItem = resolveOffline(item)
        val resolvedUpcoming = upcoming.map { resolveOffline(it) }
        showVideoOverlay = false
        activeItem = resolvedItem
        queue = listOf(resolvedItem) + resolvedUpcoming
        lyricsText = null

        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(item)
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

    fun playVideo(item: ActivePlayable) {
        // Pause background audio when starting video
        controllerProvider()?.pause()
        showAudioSheet = false
        val videoItem = resolveOffline(item).copy(isVideo = true)
        activeItem = videoItem
        positionMs = videoItem.resumePositionMs.coerceAtLeast(0L)
        durationMs = videoItem.durationMs.coerceAtLeast(0L)
        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(videoItem)
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
            "podcast" -> "/player/podcasts"
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

    fun closeVideo(lastPositionMs: Long, totalDurationMs: Long) {
        val current = activeItem
        showVideoOverlay = false
        isPlaying = false
        if (current != null) {
            scope.launch {
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
        isPlaying = false
        activeItem = null
        queue = emptyList()
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

        if (!item.isLiveGuide && positionMs > 0 && item.itemType in setOf("podcast", "audiobook")) {
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

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

    fun playAudio(item: ActivePlayable, upcoming: List<ActivePlayable> = emptyList()) {
        showVideoOverlay = false
        activeItem = item
        queue = listOf(item) + upcoming
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
        val videoItem = item.copy(isVideo = true)
        activeItem = videoItem
        val caster = castHandler
        if (caster != null) {
            scope.launch {
                val handledByCast = caster(videoItem)
                if (handledByCast) {
                    showVideoOverlay = false
                    isPlaying = true
                    reportPresence(videoItem, paused = false)
                    return@launch
                }
                showVideoOverlay = true
                isPlaying = true
                reportPresence(videoItem, paused = false)
            }
            return
        }
        showVideoOverlay = true
        isPlaying = true
        reportPresence(videoItem, paused = false)
    }

    private fun buildProgressMeta(item: ActivePlayable): net.ssync.player.ProgressMeta {
        val href = when (item.itemType) {
            "movie" -> "/player/movies/${item.itemId}"
            "show" -> "/player/tv/${item.itemId}"
            "youtube" -> "/player/youtube"
            "audiobook" -> "/player/audiobooks/${item.itemId}"
            "podcast" -> "/player/podcasts"
            else -> "/player/music"
        }
        return net.ssync.player.ProgressMeta(
            title = item.title.takeIf { it.isNotBlank() },
            subtitle = item.subtitle.takeIf { it.isNotBlank() },
            artwork = item.artworkUrl,
            backdrop = item.artworkUrl,
            href = href,
        )
    }

    fun closeVideo(lastPositionMs: Long, totalDurationMs: Long) {
        val current = activeItem
        showVideoOverlay = false
        isPlaying = false
        if (current != null) {
            scope.launch {
                api.saveProgress(
                    itemType = current.itemType,
                    itemId = current.itemId,
                    positionSeconds = lastPositionMs / 1000.0,
                    durationSeconds = totalDurationMs.takeIf { it > 0 }?.let { it / 1000.0 },
                    finished = totalDurationMs > 0 && lastPositionMs >= (totalDurationMs * 0.92),
                    meta = buildProgressMeta(current),
                )
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

    fun syncAudioProgress() {
        val item = activeItem ?: return
        if (item.isVideo) return
        val controller = controllerProvider() ?: return
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        durationMs = controller.duration.coerceAtLeast(0L)
        isPlaying = controller.isPlaying

        if (positionMs > 0 && item.itemType in setOf("podcast", "audiobook")) {
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

    private fun reportPresence(item: ActivePlayable, paused: Boolean) {
        scope.launch {
            api.sendPresence(
                itemType = item.itemType,
                itemId = item.itemId,
                title = item.title,
                subtitle = item.subtitle.ifBlank { null },
                artwork = item.artworkUrl,
                positionSeconds = positionMs / 1000.0,
                durationSeconds = item.durationMs.takeIf { it > 0 }?.let { it / 1000.0 },
                paused = paused,
            )
        }
    }

    private fun toMediaItem(item: ActivePlayable): MediaItem =
        MediaItem.Builder()
            .setMediaId(item.itemId)
            .setUri(item.streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .build(),
            )
            .build()
}

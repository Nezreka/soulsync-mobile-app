package net.ssync.player

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.ssync.network.CertPinningOkHttp
import net.ssync.storage.KeystoreServerVault
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared 512 MB LRU Media3 disk cache for instant gapless playback,
 * 10-track lookahead pre-caching, and seamless dual-deck crossfading.
 */
@UnstableApi
object SoulSyncAudioCache {
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: run {
                val dir = File(context.applicationContext.cacheDir, "soulsync_audio_cache")
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext),
                ).also { cache = it }
            }
        }
    }
}

/**
 * Background audio playback service powered by AndroidX Media3 (`ExoPlayer` + `MediaSessionService`).
 *
 * Features:
 * - 512 MB Media3 `SimpleCache` + 10-track lookahead pre-fetcher so upcoming tracks are ready on disk
 * - Dual-deck equal-power (cos/sin) crossfade with Smart Album Gapless (auto-disabled for
 *   consecutive tracks from the same album)
 * - Streams FLAC / Opus / MP3 / AAC over the cert-pinned, device-token-authenticated OkHttpClient
 */
class SoulSyncPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var fadeOutPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var crossfadeMonitorJob: Job? = null
    private var activeFadeJob: Job? = null
    private var prefetchJob: Job? = null
    private val prefetchedKeys = ConcurrentHashMap.newKeySet<String>()
    private var lastCrossfadeMediaId: String? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val httpClient = buildPinnedPlaybackClient()
        val upstreamFactory = OkHttpDataSource.Factory(httpClient)
        val audioCache = SoulSyncAudioCache.get(this)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // a downloaded song is a file:// uri, which OkHttp can't open ("Malformed
        // URL"): DefaultDataSource sends files to the file reader and the rest
        // through the pinned, cached http source
        val playbackDataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Secondary deck used solely for equal-power crossfade tail (no audio focus theft)
        fadeOutPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .build()

        val prefs = applicationContext.getSharedPreferences("soulsync_player_device_prefs", Context.MODE_PRIVATE)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                triggerLookaheadPrefetch(player, cacheDataSourceFactory, prefs)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                triggerLookaheadPrefetch(player, cacheDataSourceFactory, prefs)
            }

            // a pause mid-crossfade pauses all of it: the outgoing deck kept
            // playing for up to 12 seconds under a paused player
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) return
                activeFadeJob?.cancel()
                activeFadeJob = null
                fadeOutPlayer?.let {
                    it.stop()
                    it.clearMediaItems()
                }
                player.volume = 1f
            }
        })

        startCrossfadeMonitor(player, prefs)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    @OptIn(UnstableApi::class)
    private fun triggerLookaheadPrefetch(
        player: ExoPlayer,
        cacheDataSourceFactory: CacheDataSource.Factory,
        prefs: SharedPreferences,
    ) {
        val count = prefs.getInt("prefetch_count", 10).coerceIn(0, 10)
        if (count <= 0) return
        val currIdx = player.currentMediaItemIndex
        val total = player.mediaItemCount
        if (currIdx < 0 || currIdx + 1 >= total) return

        val upcoming = mutableListOf<MediaItem>()
        val end = (currIdx + 1 + count).coerceAtMost(total)
        for (i in (currIdx + 1) until end) {
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: continue
            // Only prefetch remote HTTP/HTTPS audio streams (skip local file:// offline downloads)
            if (uri.scheme == "http" || uri.scheme == "https") {
                upcoming += item
            }
        }
        if (upcoming.isEmpty()) return

        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch(Dispatchers.IO) {
            for (item in upcoming) {
                if (!isActive) break
                val uri = item.localConfiguration?.uri ?: continue
                val key = item.localConfiguration?.customCacheKey ?: uri.toString()
                if (!prefetchedKeys.add(key)) continue
                runCatching {
                    val dataSpec = DataSpec.Builder()
                        .setUri(uri)
                        .setKey(key)
                        .build()
                    val ds = cacheDataSourceFactory.createDataSource()
                    CacheWriter(ds, dataSpec, null, null).cache()
                }.onFailure {
                    prefetchedKeys.remove(key)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startCrossfadeMonitor(player: ExoPlayer, prefs: SharedPreferences) {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(200L)
                val cfSec = prefs.getInt("crossfade_seconds", 4).coerceIn(0, 12)
                if (cfSec <= 0 || !player.isPlaying) continue
                val curr = player.currentMediaItem ?: continue
                val nextIdx = player.nextMediaItemIndex
                if (nextIdx == C.INDEX_UNSET || nextIdx >= player.mediaItemCount) continue
                val next = player.getMediaItemAt(nextIdx)

                val currExtras = curr.mediaMetadata.extras
                val nextExtras = next.mediaMetadata.extras
                val currType = currExtras?.getString("itemType") ?: "track"
                val nextType = nextExtras?.getString("itemType") ?: "track"
                if (currType != "track" || nextType != "track") continue

                // Smart Album Gapless: disable crossfade for consecutive tracks from the same album
                val smartGapless = prefs.getBoolean("smart_album_gapless", true)
                val currParent = currExtras?.getString("parentId")
                val nextParent = nextExtras?.getString("parentId")
                if (smartGapless && !currParent.isNullOrBlank() && currParent == nextParent) continue

                val dur = player.duration
                val pos = player.currentPosition
                val cfMs = cfSec * 1000L
                if (dur <= cfMs + 2500L) continue
                val remaining = dur - pos
                val fadeKey = "${player.currentMediaItemIndex}:${curr.mediaId}"
                if (remaining in 400L..cfMs && lastCrossfadeMediaId != fadeKey) {
                    lastCrossfadeMediaId = fadeKey
                    performEqualPowerCrossfade(player, curr, pos, remaining.coerceAtMost(cfMs))
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun performEqualPowerCrossfade(
        primary: ExoPlayer,
        outgoingItem: MediaItem,
        outgoingPosMs: Long,
        fadeDurationMs: Long,
    ) {
        val outDeck = fadeOutPlayer ?: return
        activeFadeJob?.cancel()

        // Start outgoing track on secondary deck at exact current position (instant from SimpleCache!)
        outDeck.stop()
        outDeck.setMediaItem(outgoingItem)
        outDeck.seekTo(outgoingPosMs)
        outDeck.volume = 1f
        outDeck.prepare()
        outDeck.playWhenReady = true

        // Advance primary deck to the next pre-cached track and ramp in from 0
        primary.volume = 0f
        primary.seekToNextMediaItem()
        primary.playWhenReady = true

        activeFadeJob = serviceScope.launch {
            val steps = (fadeDurationMs / 50L).coerceAtLeast(8L)
            for (step in 1..steps) {
                if (!isActive) break
                val t = step.toDouble() / steps.toDouble()
                val outGain = cos(t * Math.PI / 2.0).toFloat().coerceIn(0f, 1f)
                val inGain = sin(t * Math.PI / 2.0).toFloat().coerceIn(0f, 1f)
                outDeck.volume = outGain
                primary.volume = inGain
                delay(50L)
            }
            outDeck.stop()
            outDeck.clearMediaItems()
            primary.volume = 1f
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        crossfadeMonitorJob?.cancel()
        activeFadeJob?.cancel()
        prefetchJob?.cancel()
        serviceScope.cancel()
        fadeOutPlayer?.release()
        fadeOutPlayer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * the playback client, from the pairing in the vault. it was read once
     * when the service started: unpair and pair again (or start unpaired)
     * while the service lived and every stream failed the old pin, or went
     * out with no token. this re-reads the vault now and then and rebuilds
     * the client when the pairing changed.
     */
    private fun buildPinnedPlaybackClient(): okhttp3.Call.Factory = VaultCallFactory()

    private inner class VaultCallFactory : okhttp3.Call.Factory {
        @Volatile private var key: String? = null
        @Volatile private var client: OkHttpClient = OkHttpClient()
        @Volatile private var checkedAt = 0L

        private fun current(): OkHttpClient {
            val now = System.currentTimeMillis()
            if (now - checkedAt < 30_000L && key != null) return client
            checkedAt = now
            val record = runCatching { runBlocking { KeystoreServerVault(applicationContext).load() } }.getOrNull()
            val nextKey = record?.let { "${it.certFingerprintSha256}|${it.deviceToken}" } ?: ""
            if (nextKey != key) {
                client = if (record != null) {
                    CertPinningOkHttp.createPinnedClient(
                        expectedCertFingerprintSha256 = record.certFingerprintSha256,
                        deviceTokenProvider = { record.deviceToken },
                    )
                } else {
                    OkHttpClient()
                }
                key = nextKey
            }
            return client
        }

        override fun newCall(request: okhttp3.Request): okhttp3.Call = current().newCall(request)
    }
}

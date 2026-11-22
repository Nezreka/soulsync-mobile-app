package net.ssync.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.runBlocking
import net.ssync.network.CertPinningOkHttp
import net.ssync.storage.KeystoreServerVault
import okhttp3.OkHttpClient

/**
 * Background audio playback service powered by AndroidX Media3 (`ExoPlayer` + `MediaSessionService`).
 *
 * Streams FLAC / Opus / MP3 / AAC directly from the paired SoulSync server over the
 * cert-pinned, device-token-authenticated OkHttpClient, with audio focus,
 * becoming-noisy handling, and lockscreen / notification / Android TV media controls.
 */
class SoulSyncPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val httpClient = buildPinnedPlaybackClient()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun buildPinnedPlaybackClient(): OkHttpClient {
        val record = runBlocking { KeystoreServerVault(applicationContext).load() }
        return if (record != null) {
            CertPinningOkHttp.createPinnedClient(
                expectedCertFingerprintSha256 = record.certFingerprintSha256,
                deviceTokenProvider = { record.deviceToken },
            )
        } else {
            OkHttpClient()
        }
    }
}

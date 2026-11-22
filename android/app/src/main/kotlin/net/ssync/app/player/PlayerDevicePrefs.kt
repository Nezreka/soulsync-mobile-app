package net.ssync.app.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen-local Player display preferences, matching SoulSync web /player's
 * browser-scoped settings (video quality, picture fit, brightness, background
 * trailers, subtitle appearance, and music visual theme).
 */
data class DeviceDisplayPrefs(
    val videoQuality: String = "auto",
    val pictureFit: String = "fit",
    val brightness: Float = 1.0f,
    val backgroundTrailers: Boolean = true,
    val subtitleSize: String = "medium",
    val subtitleBox: Boolean = true,
    val musicVisual: String = "aurora",
)

object PlayerDevicePrefs {
    private const val PREFS_NAME = "soulsync_player_device_prefs"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_FIT = "picture_fit"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_TRAILERS = "background_trailers"
    private const val KEY_SUB_SIZE = "subtitle_size"
    private const val KEY_SUB_BOX = "subtitle_box"
    private const val KEY_VISUAL = "music_visual"

    val QUALITIES: List<Pair<String, Pair<String, String>>> = listOf(
        "auto" to ("Auto" to "Adapts to your connection"),
        "original" to ("Original" to "Direct play whenever possible"),
        "1080p" to ("1080p" to "Full HD · up to 10 Mbps"),
        "720p" to ("720p" to "HD · up to 4 Mbps"),
        "480p" to ("480p" to "Data saver · up to 1.5 Mbps"),
    )

    val FITS: List<Triple<String, String, String>> = listOf(
        Triple("fit", "Fit", "The whole picture, bars where it needs them"),
        Triple("fill", "Fill screen", "No bars, edges cropped to fill"),
        Triple("stretch", "Stretch", "No bars, picture stretched to fit"),
    )

    val BRIGHTNESS_STEPS: List<Float> = listOf(0.15f, 0.30f, 0.45f, 0.60f, 0.80f, 1.0f)

    val SUBTITLE_SIZES: List<Pair<String, String>> = listOf(
        "small" to "Small",
        "medium" to "Medium",
        "large" to "Large",
    )

    val MUSIC_VISUALS: List<Pair<String, String>> = listOf(
        "aurora" to "Aurora",
        "bars" to "Spectrum",
        "wave" to "Waveform",
        "pulse" to "Pulse",
        "ambient" to "Ambient Glow",
        "off" to "Off",
    )

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(DeviceDisplayPrefs())
    val state: StateFlow<DeviceDisplayPrefs> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _state.value = DeviceDisplayPrefs(
            videoQuality = sp.getString(KEY_QUALITY, "auto") ?: "auto",
            pictureFit = sp.getString(KEY_FIT, "fit") ?: "fit",
            brightness = sp.getFloat(KEY_BRIGHTNESS, 1.0f).coerceIn(0.15f, 1.0f),
            backgroundTrailers = sp.getBoolean(KEY_TRAILERS, true),
            subtitleSize = sp.getString(KEY_SUB_SIZE, "medium") ?: "medium",
            subtitleBox = sp.getBoolean(KEY_SUB_BOX, true),
            musicVisual = sp.getString(KEY_VISUAL, "aurora") ?: "aurora",
        )
    }

    fun update(transform: (DeviceDisplayPrefs) -> DeviceDisplayPrefs) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()?.apply {
            putString(KEY_QUALITY, next.videoQuality)
            putString(KEY_FIT, next.pictureFit)
            putFloat(KEY_BRIGHTNESS, next.brightness)
            putBoolean(KEY_TRAILERS, next.backgroundTrailers)
            putString(KEY_SUB_SIZE, next.subtitleSize)
            putBoolean(KEY_SUB_BOX, next.subtitleBox)
            putString(KEY_VISUAL, next.musicVisual)
            apply()
        }
    }
}

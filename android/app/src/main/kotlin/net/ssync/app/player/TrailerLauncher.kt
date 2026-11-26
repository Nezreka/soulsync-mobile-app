package net.ssync.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import net.ssync.pairing.ui.LocalIsTv

/**
 * the Trailer button: plays a title's whole trailer with sound, full screen, in
 * youtube. the key is TMDB's youtube key from the title's extras. phones try the
 * youtube app, then any browser; a tv tries the youtube tv app, then the phone
 * app, then anything that opens the link.
 */
object TrailerLaunch {
    const val YOUTUBE_APP = "com.google.android.youtube"
    const val YOUTUBE_TV_APP = "com.google.android.youtube.tv"

    private val KEY_SHAPE = Regex("^[A-Za-z0-9_-]+$")

    /** the watch page for a youtube key, or null for a blank or odd key */
    fun watchUrl(key: String?): String? {
        val k = key?.trim().orEmpty()
        if (k.isEmpty() || !KEY_SHAPE.matches(k)) return null
        return "https://www.youtube.com/watch?v=$k"
    }

    /** the apps to try in order; null means whatever opens the link (the browser) */
    fun targets(isTv: Boolean): List<String?> =
        if (isTv) listOf(YOUTUBE_TV_APP, YOUTUBE_APP, null) else listOf(YOUTUBE_APP, null)
}

/** opens the trailer; false (with a message) when nothing on the device can play it */
fun openTrailer(context: Context, key: String?, isTv: Boolean): Boolean {
    val url = TrailerLaunch.watchUrl(key)
    if (url != null) {
        for (pkg in TrailerLaunch.targets(isTv)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (pkg != null) setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // not installed: try the next one
            } catch (_: SecurityException) {
            }
        }
    }
    Toast.makeText(context, "Nothing on this device can play the trailer.", Toast.LENGTH_LONG).show()
    return false
}

/** the Trailer button's action for a title, or null when it has no trailer (no button) */
@androidx.compose.runtime.Composable
fun rememberTrailerAction(key: String?): (() -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = LocalIsTv.current
    if (TrailerLaunch.watchUrl(key) == null) return null
    return { openTrailer(context, key, isTv) }
}

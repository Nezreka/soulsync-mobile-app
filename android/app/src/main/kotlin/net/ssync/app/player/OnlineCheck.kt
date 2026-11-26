package net.ssync.app.player

/** what one look at the server found: it answered, maybe at another of its addresses */
data class ReachResult(val reached: Boolean, val switchTo: String? = null)

/**
 * the rules for offline mode, kept apart from the network so they can be tested.
 * the current address is asked first; if it doesn't answer, every address the
 * server gave us is raced before deciding it's gone. one miss never takes the
 * app offline: it takes [strikesToOffline] in a row. one answer brings it back.
 */
class OnlineCheck(private val strikesToOffline: Int = 2) {
    var misses = 0
        private set

    suspend fun look(
        currentBase: String?,
        probeCurrent: suspend () -> Boolean,
        raceAll: suspend () -> String?,
    ): ReachResult {
        if (currentBase != null && probeCurrent()) return ReachResult(true)
        val best = raceAll()?.trimEnd('/') ?: return ReachResult(false)
        return ReachResult(true, best.takeIf { it != currentBase?.trimEnd('/') })
    }

    /** online or not after this look, given whether the app was online before it */
    fun decide(reached: Boolean, wasOnline: Boolean): Boolean {
        if (reached) {
            misses = 0
            return true
        }
        misses++
        return if (misses >= strikesToOffline) false else wasOnline
    }
}

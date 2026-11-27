package net.ssync.connect

/**
 * what pairing says when it stops, one message per thing that actually
 * went wrong (#2328: every failure used to read "Couldn't reach that
 * server with this code", which sent people chasing the wrong fix).
 *
 * the relay answers an unknown code, an expired code and a server that
 * isn't connected the same way (404 unknown_or_expired_code: by design it
 * can't tell them apart, and must not), so [UNKNOWN_CODE] names both.
 */
object PairingMessages {
    const val UNKNOWN_CODE =
        "That code isn't known. Check it was typed right, or make a new one in SoulSync, Settings, Devices. " +
            "If a new code fails too, your server isn't connected for pairing: Settings, Devices says why."

    const val RELAY_ERROR =
        "The pairing service had a problem. Try again in a minute."

    const val NO_INTERNET =
        "Couldn't reach the pairing service. Check this phone's internet connection and try again."

    const val BAD_ANSWER =
        "Your server's answer didn't check out, so pairing stopped. Make a new code in SoulSync and try again."

    /** the relay found the server, but none of its addresses answered */
    const val CANT_CONNECT =
        "Found your server, but couldn't connect to it. Put this phone on the same wifi as the server. " +
            "SoulSync's port 8443 has to be open (in Docker, publish 8443:8443). " +
            "Away from home, turn on automatic remote access in SoulSync, Settings, Devices, or forward port 8443."

    const val CODE_USED =
        "That code was already used or ran out. Make a new one in SoulSync, Settings, Devices."

    const val TOO_MANY_TRIES =
        "Too many wrong codes. Wait a minute and try again."

    const val LOST_SERVER =
        "Lost the connection to your server while pairing. Try again."

    /** the last fallback, for anything not named above */
    const val FALLBACK = LookupOutcome.DEFAULT_UNREACHABLE_MESSAGE

    /** what a failed redeem says, from its HTTP status (0: no answer at all) */
    fun forRedeemStatus(status: Int): String = when (status) {
        404 -> CODE_USED
        429 -> TOO_MANY_TRIES
        0 -> LOST_SERVER
        else -> FALLBACK
    }
}

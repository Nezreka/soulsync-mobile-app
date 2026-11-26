package net.ssync.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * a pairing link from the QR in SoulSync Settings > Devices:
 * `soulsync://pair?d=<base64url of {v, lookup_id, fingerprint, addresses, code}>`.
 *
 * it only fills the pairing screen in. the code still goes through the
 * normal relay lookup and the user still confirms the fingerprint; the
 * link's fingerprint is one more check on top (the answer must match it).
 */
class PairingLink(
    val code: PairingCode,
    /** the code as typed in the code boxes */
    val codeText: String,
    /** first 12 lowercase hex of the server's lookup id, when the link had one */
    val fingerprint: String?,
) {
    val formattedFingerprint: String?
        get() = fingerprint?.uppercase()?.chunked(4)?.joinToString("-")

    override fun toString(): String = "PairingLink(fingerprint=$formattedFingerprint, code=REDACTED)"

    companion object {
        const val SCHEME = "soulsync"
        const val HOST = "pair"
        const val VERSION = 1

        // a real link is well under 2 KB; anything far past that is not ours
        const val MAX_LINK_CHARS = 8192
        const val MAX_JSON_BYTES = 6144

        const val BAD_LINK = "That pairing link didn't work. Type the code shown in SoulSync instead."
        const val NEWER_LINK =
            "That pairing link is from a newer SoulSync. Update this app, or type the code shown in SoulSync."

        private val json = Json { ignoreUnknownKeys = true }
        private val HEX = Regex("^[0-9a-f]+$")

        /** true for a soulsync://pair link, whatever its contents */
        fun isPairingLink(raw: String?): Boolean {
            val text = raw?.trim() ?: return false
            val head = text.substringBefore('?').substringBefore('#').lowercase()
            return head == "$SCHEME://$HOST" || head == "$SCHEME://$HOST/"
        }

        /** the link, or the plain message to show over the code boxes */
        fun parse(raw: String?): Result<PairingLink> {
            val text = raw?.trim().orEmpty()
            if (!isPairingLink(text) || text.length > MAX_LINK_CHARS) return fail(BAD_LINK)
            val query = text.substringAfter('?', "").substringBefore('#')
            val d = query.split('&')
                .firstOrNull { it.startsWith("d=") }
                ?.removePrefix("d=")
                ?.trimEnd('=')
                ?: return fail(BAD_LINK)
            if (d.isEmpty() || d.length > MAX_LINK_CHARS) return fail(BAD_LINK)
            val bytes = runCatching { Base64.getUrlDecoder().decode(d) }.getOrNull() ?: return fail(BAD_LINK)
            if (bytes.size > MAX_JSON_BYTES) return fail(BAD_LINK)
            val obj = runCatching {
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return fail(BAD_LINK)

            val version = (obj["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return fail(BAD_LINK)
            if (version > VERSION) return fail(NEWER_LINK)
            if (version < 1) return fail(BAD_LINK)

            val codeText = obj.string("code") ?: return fail(BAD_LINK)
            val code = PairingCode.parse(codeText).getOrNull() ?: return fail(BAD_LINK)

            val lookupId = obj.string("lookup_id")?.lowercase()
                ?.takeIf { it.length == ServerIdentity.LOOKUP_ID_LENGTH && HEX.matches(it) }
            val fp = obj.string("fingerprint")?.lowercase()
                ?.takeIf { it.length == ServerIdentity.FINGERPRINT_LENGTH && HEX.matches(it) }
            // the two must agree when both are there
            if (lookupId != null && fp != null && lookupId.take(ServerIdentity.FINGERPRINT_LENGTH) != fp) {
                return fail(BAD_LINK)
            }
            val fingerprint = fp ?: lookupId?.take(ServerIdentity.FINGERPRINT_LENGTH)
            val shown = code.rawForDirectRedemption().chunked(4).joinToString("-")
            return Result.success(PairingLink(code, shown, fingerprint))
        }

        private fun JsonObject.string(key: String): String? {
            val el: JsonElement = this[key] ?: return null
            return (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        private fun fail(message: String): Result<PairingLink> =
            Result.failure(IllegalArgumentException(message))
    }
}

/** what to do with a pairing link, given what this phone is paired to now */
sealed interface PairingLinkAction {
    /** not paired: start pairing with the link */
    data object Pair : PairingLinkAction

    /** already paired to this server: offer to open the player, or pair again */
    data object AlreadyPaired : PairingLinkAction

    /** paired to another server: offer to switch (the app keeps one server) */
    data object Switch : PairingLinkAction
}

object PairingLinkDecision {
    /**
     * [pairedFingerprint] is the stored server's (first 12 hex of its lookup
     * id), null when the phone isn't paired. a link without a fingerprint
     * can't be matched, so it's treated as another server: the user is asked
     * before anything changes.
     */
    fun decide(link: PairingLink, pairedFingerprint: String?): PairingLinkAction {
        val paired = pairedFingerprint?.lowercase() ?: return PairingLinkAction.Pair
        val fp = link.fingerprint ?: return PairingLinkAction.Switch
        return if (CryptoUtils.constantTimeEquals(fp, paired.take(ServerIdentity.FINGERPRINT_LENGTH))) {
            PairingLinkAction.AlreadyPaired
        } else {
            PairingLinkAction.Switch
        }
    }
}

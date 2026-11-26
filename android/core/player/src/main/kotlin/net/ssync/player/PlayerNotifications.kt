package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * player notifications (scrap/NOTIFICATIONS_CONTRACT.md): what the server
 * made for this profile, its kinds and prefs, and the pure bookkeeping the
 * app does with them (what to post, how to group, which channel)
 */
data class PlayerNotification(
    val id: Long,
    val kind: String,
    val title: String,
    val body: String = "",
    val image: String? = null,
    /** a /player/... path */
    val link: String? = null,
    val createdAt: String = "",
    val read: Boolean = false,
)

data class NotificationFeed(
    val notifications: List<PlayerNotification>,
    val unread: Int,
    val latestId: Long,
)

/** one kind as the server names it: a switch in settings, a channel on the phone */
data class NotificationKindInfo(
    val id: String,
    val label: String,
    val help: String = "",
    val default: Boolean = true,
    /** admin messages can't be turned off */
    val locked: Boolean = false,
)

/**
 * the profile's prefs, as core/player/notifications.py keeps them (flat keys).
 * a save sends only what changed: the server refuses a key it doesn't know,
 * and a partial change is how it merges
 */
data class NotificationPrefs(
    val kinds: Map<String, Boolean> = emptyMap(),
    val featuredNotInLibrary: Boolean = false,
    val memoriesTime: String = "09:00",
    val quietHours: Boolean = false,
    val quietStart: String = "22:00",
    val quietEnd: String = "08:00",
    val digest: Boolean = false,
    val digestTime: String = "18:00",
    /** the profile's zone for quiet hours and the digest; blank is the server's */
    val timezone: String = "",
) {
    fun enabled(kind: NotificationKindInfo): Boolean =
        kind.locked || (kinds[kind.id] ?: kind.default)

    /** the PUT body: only the keys that differ from [before] */
    fun changesFrom(before: NotificationPrefs): JsonObject = buildJsonObject {
        val kindChanges = kinds.filter { (k, v) -> before.kinds[k] != v }
        if (kindChanges.isNotEmpty()) put(K_KINDS, JsonObject(kindChanges.mapValues { JsonPrimitive(it.value) }))
        if (featuredNotInLibrary != before.featuredNotInLibrary) put(K_FEATURED, JsonPrimitive(featuredNotInLibrary))
        if (memoriesTime != before.memoriesTime) put(K_MEMORIES_TIME, JsonPrimitive(memoriesTime))
        if (quietHours != before.quietHours) put(K_QUIET, JsonPrimitive(quietHours))
        if (quietStart != before.quietStart) put(K_QUIET_START, JsonPrimitive(quietStart))
        if (quietEnd != before.quietEnd) put(K_QUIET_END, JsonPrimitive(quietEnd))
        if (digest != before.digest) put(K_DIGEST, JsonPrimitive(digest))
        if (digestTime != before.digestTime) put(K_DIGEST_TIME, JsonPrimitive(digestTime))
        if (timezone != before.timezone) put(K_TIMEZONE, JsonPrimitive(timezone))
    }

    companion object {
        const val K_KINDS = "kinds"
        const val K_FEATURED = "featured_not_in_library"
        const val K_MEMORIES_TIME = "memories_time"
        const val K_QUIET = "quiet_hours"
        const val K_QUIET_START = "quiet_start"
        const val K_QUIET_END = "quiet_end"
        const val K_DIGEST = "digest"
        const val K_DIGEST_TIME = "digest_time"
        const val K_TIMEZONE = "timezone"
    }
}

data class NotificationPrefsEnvelope(
    val prefs: NotificationPrefs,
    val kinds: List<NotificationKindInfo>,
)

object NotificationsJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let {
        it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    /** "09:00" style times only; anything else keeps the default */
    private fun time(el: JsonElement?, fallback: String): String {
        val s = el.str() ?: return fallback
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(s) ?: return fallback
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return fallback
        return "%02d:%02d".format(h, min)
    }

    fun parseNotification(o: JsonObject): PlayerNotification? {
        val id = o["id"].long() ?: return null
        return PlayerNotification(
            id = id,
            kind = o["kind"].str() ?: "admin_message",
            title = o["title"].str().orEmpty(),
            body = o["body"].str().orEmpty(),
            image = o["image"].str(),
            link = o["link"].str(),
            createdAt = o["created_at"].str().orEmpty(),
            read = o["read"].bool() ?: (o["read_at"].str() != null),
        )
    }

    fun parseFeed(body: String): NotificationFeed {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return NotificationFeed(emptyList(), 0, 0)
        val items = (root["notifications"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::parseNotification) }
        val latest = root["latest_id"].long() ?: items.maxOfOrNull { it.id } ?: 0L
        val unread = root["unread"].long()?.toInt() ?: items.count { !it.read }
        return NotificationFeed(items, unread.coerceAtLeast(0), latest)
    }

    fun parseKinds(el: JsonElement?): List<NotificationKindInfo> =
        (el as? JsonArray).orEmpty().mapNotNull { k ->
            val o = k as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            NotificationKindInfo(
                id = id,
                label = o["label"].str() ?: NotificationChannels.fallbackLabel(id),
                help = o["help"].str().orEmpty(),
                default = o["default"].bool() ?: true,
                locked = id == "admin_message" || (o["locked"].bool() ?: (o["can_disable"].bool() == false)),
            )
        }

    fun parsePrefs(el: JsonElement?): NotificationPrefs {
        val o = el as? JsonObject ?: return NotificationPrefs()
        val kinds = (o[NotificationPrefs.K_KINDS] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            v.bool()?.let { k to it }
        }.toMap()
        val d = NotificationPrefs()
        return NotificationPrefs(
            kinds = kinds,
            featuredNotInLibrary = o[NotificationPrefs.K_FEATURED].bool() ?: d.featuredNotInLibrary,
            memoriesTime = time(o[NotificationPrefs.K_MEMORIES_TIME], d.memoriesTime),
            quietHours = o[NotificationPrefs.K_QUIET].bool() ?: d.quietHours,
            quietStart = time(o[NotificationPrefs.K_QUIET_START], d.quietStart),
            quietEnd = time(o[NotificationPrefs.K_QUIET_END], d.quietEnd),
            digest = o[NotificationPrefs.K_DIGEST].bool() ?: d.digest,
            digestTime = time(o[NotificationPrefs.K_DIGEST_TIME], d.digestTime),
            timezone = o[NotificationPrefs.K_TIMEZONE].str().orEmpty(),
        )
    }

    fun parsePrefsEnvelope(body: String): NotificationPrefsEnvelope {
        val root = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
        val kinds = parseKinds(root["kinds"]).ifEmpty { NotificationChannels.KNOWN }
        return NotificationPrefsEnvelope(parsePrefs(root["prefs"]), kinds)
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}

/**
 * which notifications a check posts, and where the "since" cursor moves.
 * the first check on a device only sets the cursor: a fresh install never
 * floods the tray with the backlog (it's all in the bell)
 */
object NotificationCursor {
    data class Plan(val toPost: List<PlayerNotification>, val nextCursor: Long)

    fun plan(stored: Long?, feed: NotificationFeed): Plan {
        val newest = maxOf(feed.latestId, feed.notifications.maxOfOrNull { it.id } ?: 0L)
        if (stored == null || stored <= 0L) return Plan(emptyList(), newest)
        // the server's ids went backwards (a restored or new database): start over quietly
        if (newest < stored) return Plan(emptyList(), newest)
        val fresh = feed.notifications
            .filter { it.id > stored && !it.read }
            .distinctBy { it.id }
            .sortedBy { it.id }
        return Plan(fresh, maxOf(stored, newest))
    }
}

/** one kind's share of a check: the newest few to show, and how many came */
data class NotificationGroup(
    val kind: String,
    val shown: List<PlayerNotification>,
    val total: Int,
) {
    /** a summary only when there's more than one: one notification needs no header */
    val needsSummary: Boolean get() = total > 1
}

object NotificationGrouping {
    const val MAX_PER_KIND = 4

    /** kinds in the order their newest arrived (newest first), each newest first */
    fun plan(items: List<PlayerNotification>, maxPerKind: Int = MAX_PER_KIND): List<NotificationGroup> =
        items.groupBy { it.kind }
            .map { (kind, list) ->
                val newestFirst = list.sortedByDescending { it.id }
                NotificationGroup(kind, newestFirst.take(maxPerKind.coerceAtLeast(1)), list.size)
            }
            .sortedByDescending { g -> g.shown.first().id }
}

object NotificationChannels {
    private const val PREFIX = "ssn_"

    val KNOWN: List<NotificationKindInfo> = listOf(
        NotificationKindInfo("new_episode", "New episodes"),
        NotificationKindInfo("featured_movie", "Featured films"),
        NotificationKindInfo("watchlist_available", "From your watchlist"),
        NotificationKindInfo("request_done", "Your requests"),
        NotificationKindInfo("season_premiere", "Season premieres"),
        NotificationKindInfo("leaving_soon", "Leaving soon", default = false),
        NotificationKindInfo("download_ready", "Downloads ready"),
        NotificationKindInfo("party_invite", "Watch party invites"),
        NotificationKindInfo("admin_message", "Messages from the admin", locked = true),
        NotificationKindInfo("new_music", "New music", default = false),
        NotificationKindInfo("home_memories", "Memories", default = false),
    )

    /** made by the server itself (a daily digest, "send a test"): a channel each, never a switch */
    val SYSTEM: List<NotificationKindInfo> = listOf(
        NotificationKindInfo("digest", "Daily digest"),
        NotificationKindInfo("test", "Test notifications"),
    )

    fun fallbackLabel(kind: String): String =
        (KNOWN + SYSTEM).firstOrNull { it.id == kind }?.label
            ?: kind.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { "SoulSync" }

    /** a channel id per kind: safe characters only, stable across versions */
    fun channelId(kind: String): String =
        PREFIX + kind.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(40).ifBlank { "other" }

    fun groupKey(kind: String): String = "net.ssync.notify." + channelId(kind)

    /** the system notification id for one notification (kept clear of the download progress id) */
    fun systemId(id: Long): Int = 10_000 + (id and 0x1FFFFFFFL).toInt()

    /** the group summary's id for a kind: in its own range, so it never meets a [systemId] */
    fun summaryId(kind: String): Int = 0x40000000 + (channelId(kind).hashCode() and 0x00FFFFFF)
}

/** the notification routes, over the paired server's pinned client */
class NotificationsApiClient(
    baseUrl: String,
    val http: OkHttpClient,
) {
    val base: String = baseUrl.trimEnd('/')

    suspend fun fetch(since: Long? = null, limit: Int = 50): MusicApiResult<NotificationFeed> {
        val q = buildString {
            append("?limit=").append(limit.coerceIn(1, 200))
            if (since != null && since > 0) append("&since=").append(since)
        }
        return call(Request.Builder().url("$base/api/player/notifications$q").get().build()) {
            NotificationsJson.parseFeed(it)
        }
    }

    suspend fun markRead(ids: List<Long>): MusicApiResult<Unit> {
        if (ids.isEmpty()) return MusicApiResult.Success(Unit)
        val body = buildJsonObject { put("ids", JsonArray(ids.distinct().map { JsonPrimitive(it) })) }
        return post("$base/api/player/notifications/read", body)
    }

    suspend fun markAllRead(): MusicApiResult<Unit> =
        post("$base/api/player/notifications/read", buildJsonObject { put("all", JsonPrimitive(true)) })

    suspend fun delete(id: Long): MusicApiResult<Unit> =
        call(Request.Builder().url("$base/api/player/notifications/$id").delete().build()) { }

    suspend fun fetchPrefs(): MusicApiResult<NotificationPrefsEnvelope> =
        call(Request.Builder().url("$base/api/player/notifications/prefs").get().build()) {
            NotificationsJson.parsePrefsEnvelope(it)
        }

    /** sends [changes] (see [NotificationPrefs.changesFrom]); the saved prefs come back (null when the answer had none) */
    suspend fun savePrefs(changes: JsonObject): MusicApiResult<NotificationPrefs?> {
        if (changes.isEmpty()) return MusicApiResult.Success(null)
        val req = Request.Builder()
            .url("$base/api/player/notifications/prefs")
            .put(changes.toString().toRequestBody(JSON))
            .build()
        return call(req) { body ->
            runCatching {
                val root = Json.parseToJsonElement(body) as? JsonObject
                root?.get("prefs")?.let { NotificationsJson.parsePrefs(it) }
            }.getOrNull()
        }
    }

    suspend fun sendTest(): MusicApiResult<Unit> =
        post("$base/api/player/notifications/test", JsonObject(emptyMap()))

    private suspend fun post(url: String, body: JsonObject): MusicApiResult<Unit> =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()) { }

    private suspend fun <T> call(request: Request, parse: (String) -> T): MusicApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> MusicApiResult.Success(parse(body))
                        r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                        else -> MusicApiResult.Error("Server returned HTTP ${r.code}")
                    }
                }
            } catch (e: Exception) {
                MusicApiResult.Error(e.message ?: "Unable to reach SoulSync server")
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** "5m", "3h", "2d" since a notification was made; created_at as iso text or epoch */
object NotificationTime {
    /** epoch ms, or null when the text can't be read */
    fun parse(createdAt: String): Long? {
        val s = createdAt.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { n -> return if (n > 1e12) n.toLong() else (n * 1000).toLong() }
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(s).toEpochMilli() }
        // no zone given: the server writes utc
        runCatching {
            return java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }

    fun ago(createdAt: String, nowMs: Long): String {
        val at = parse(createdAt) ?: return ""
        val secs = ((nowMs - at) / 1000).coerceAtLeast(0)
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            secs < 7 * 86_400 -> "${secs / 86_400}d"
            else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
        }
    }
}

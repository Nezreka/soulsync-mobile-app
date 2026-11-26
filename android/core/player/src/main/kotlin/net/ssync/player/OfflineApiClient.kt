package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** the /api/player/offline routes (scrap/DOWNLOADS_CONTRACT.md on the server side) */
class OfflineApiClient(
    baseUrl: String,
    val http: OkHttpClient,
) {
    val base: String = baseUrl.trimEnd('/')
    private val root = "$base/api/player/offline"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; isLenient = true }

    /** a server path or url as a full url on this server */
    fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "$base$url"
        else -> "$base/$url"
    }

    fun fileUrl(prepareId: String): String = "$root/file/$prepareId"

    suspend fun plan(type: String, id: String, deviceId: String?): MusicApiResult<OfflinePlan> {
        val b = "$root/plan".toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("id", id)
        if (!deviceId.isNullOrBlank()) b.addQueryParameter("device", deviceId)
        return get(b.build().toString()) { json.decodeFromString(OfflinePlan.serializer(), it) }
    }

    /**
     * a refusal (403, 404, 400...) comes back as a failed prepare with the
     * server's words, so it isn't retried; a dropped connection or a busy
     * server (5xx, 408, 429) comes back as an error, which is
     */
    suspend fun prepare(itemType: String, itemId: String, quality: String): MusicApiResult<OfflinePrepare> {
        val body = buildJsonObject {
            put("item_type", itemType)
            put("item_id", itemId)
            put("quality", quality)
        }
        return preparing(Request.Builder().url("$root/prepare").post(body.toString().toRequestBody(JSON_TYPE)).build())
    }

    suspend fun pollPrepare(prepareId: String): MusicApiResult<OfflinePrepare> =
        preparing(Request.Builder().url("$root/prepare/${seg(prepareId)}").get().build())

    private suspend fun preparing(request: Request): MusicApiResult<OfflinePrepare> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> MusicApiResult.Success(json.decodeFromString(OfflinePrepare.serializer(), body))
                    r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                    isRetryable(r.code) -> MusicApiResult.Error(serverMessage(r.code, body))
                    else -> MusicApiResult.Success(OfflinePrepare(status = "failed", error = serverMessage(r.code, body)))
                }
            }
        } catch (e: Exception) {
            MusicApiResult.Error(e.message ?: "Can't reach SoulSync")
        }
    }

    suspend fun report(report: OfflineDeviceReport): MusicApiResult<Unit> =
        postRaw("$root/devices/report", json.encodeToString(OfflineDeviceReport.serializer(), report)) { }

    suspend fun devices(): MusicApiResult<List<OfflineDevice>> =
        get("$root/devices") { json.decodeFromString(OfflineDevices.serializer(), it).devices }

    suspend fun wanted(deviceId: String): MusicApiResult<OfflineWanted> =
        get("$root/devices/${seg(deviceId)}/wanted") { json.decodeFromString(OfflineWanted.serializer(), it) }

    suspend fun wish(deviceId: String, action: String, type: String, id: String, quality: String): MusicApiResult<Unit> =
        post("$root/wishes", buildJsonObject {
            put("device_id", deviceId)
            put("action", action)
            put("type", type)
            put("id", id)
            put("quality", quality)
        }) { }

    /** returns the rule as the server stored it when it sends one back */
    suspend fun saveRule(rule: OfflineRule): MusicApiResult<OfflineRule?> =
        post("$root/rules", buildJsonObject {
            put("device_id", rule.deviceId)
            put("type", rule.type)
            put("id", rule.id)
            put("quality", rule.quality)
            rule.keepNext?.let { put("keep_next", it) }
            put("delete_watched", rule.deleteWatched)
            put("follow", rule.follow)
        }) { body ->
            val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@post null
            val inner = (o["rule"] as? JsonObject) ?: o
            if (inner["type"] == null) return@post rule.copy(ruleId = idOf(inner) ?: "")
            runCatching { json.decodeFromJsonElement(OfflineRule.serializer(), inner) }.getOrNull()
                ?.let { if (it.ruleId.isBlank()) it.copy(ruleId = idOf(o).orEmpty()) else it }
        }

    suspend fun deleteRule(ruleId: String): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$root/rules/${seg(ruleId)}").delete().build()) { }
    }

    suspend fun sync(deviceId: String, events: List<OfflineSyncEvent>): MusicApiResult<Unit> {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("events", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(OfflineSyncEvent.serializer()), events) as JsonArray)
        }
        return postRaw("$root/sync", body.toString()) { }
    }

    /** true when the server answers at all (any status): the phone is online */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/api/player/downloads").get().build()).execute().use { true }
        }.getOrDefault(false)
    }

    // ── plumbing ──

    private fun idOf(o: JsonObject): String? =
        listOf("rule_id", "id").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }

    private fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> get(url: String, parse: (String) -> T): MusicApiResult<T> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).get().build(), parse)
    }

    private suspend fun <T> post(url: String, body: JsonObject, parse: (String) -> T): MusicApiResult<T> =
        postRaw(url, body.toString(), parse)

    private suspend fun <T> postRaw(url: String, body: String, parse: (String) -> T): MusicApiResult<T> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE)).build(), parse)
    }

    private fun <T> execute(request: Request, parse: (String) -> T): MusicApiResult<T> = try {
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful -> MusicApiResult.Success(parse(body))
                r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                else -> MusicApiResult.Error(serverMessage(r.code, body))
            }
        }
    } catch (e: Exception) {
        MusicApiResult.Error(e.message ?: "Can't reach SoulSync")
    }

    companion object {
        fun isRetryable(code: Int): Boolean = code >= 500 || code == 408 || code == 429

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** the server's own words when it sends any, else plain ones for the status */
        fun serverMessage(code: Int, body: String): String {
            // { success: false, error: "<code>", message: "<words to show>" }
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            str("message")?.let { return it }
            when (str("error")) {
                "downloads_disabled" -> return "Downloads are turned off for this profile."
                "conversion_off" -> return "Only the original can be downloaded: converted copies are off for this profile."
                "not_allowed", "restricted" -> return "This profile can't play this."
                "not_found" -> return "This isn't on the server any more."
                "not_your_device" -> return "Another device already reports with this id."
                null -> Unit
                else -> return str("error")!!
            }
            str("detail")?.let { return it }
            return when (code) {
                401 -> "Not signed in. Pick your profile again."
                403 -> "Downloads are turned off for this profile."
                404 -> "This isn't on the server any more."
                409 -> "The server is busy with this one. Try again soon."
                429, 503 -> "The server is busy. It'll try again."
                else -> "Server returned HTTP $code"
            }
        }
    }
}

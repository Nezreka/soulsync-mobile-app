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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** one label and value on the info sheet. mono values are paths, ids and codecs */
data class ItemInfoRow(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val copy: Boolean = false,
)

data class ItemInfoSection(val title: String, val rows: List<ItemInfoRow>)

/** what GET /api/player/info/<type>/<id> sends back: the app just shows the sections */
data class ItemInfo(
    val title: String = "",
    val subtitle: String = "",
    val artwork: String? = null,
    val sections: List<ItemInfoSection> = emptyList(),
)

object ItemInfoTypes {
    /**
     * the app's item types to the server's. null means there is no info sheet
     * for that kind (a whole show, season, album or artist)
     */
    fun serverType(appType: String): String? = when (appType.trim().lowercase()) {
        "movie" -> "movie"
        "episode", "tv_episode" -> "episode"
        "track", "song" -> "track"
        "audiobook", "book" -> "audiobook"
        "podcast_episode", "podcast" -> "podcast_episode"
        "home", "home_video" -> "home"
        "youtube", "youtube_video" -> "youtube"
        else -> null
    }

    fun supports(appType: String): Boolean = serverType(appType) != null
}

object ItemInfoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): ItemInfo {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("The server sent something the app can't read.")
        // some routes wrap the answer as {"success":true,"info":{...}}
        val root = (o["info"] as? JsonObject) ?: o
        val sections = (root["sections"] as? JsonArray).orEmpty().mapNotNull { section(it) }
        return ItemInfo(
            title = root.str("title").orEmpty(),
            subtitle = root.str("subtitle").orEmpty(),
            artwork = root.str("artwork")?.takeIf { it.isNotBlank() },
            sections = sections,
        )
    }

    private fun section(e: JsonElement): ItemInfoSection? {
        val o = e as? JsonObject ?: return null
        val rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { row(it) }
        if (rows.isEmpty()) return null
        return ItemInfoSection(title = o.str("title").orEmpty(), rows = rows)
    }

    private fun row(e: JsonElement): ItemInfoRow? {
        val o = e as? JsonObject ?: return null
        val label = o.str("label").orEmpty()
        val value = o.str("value")?.takeIf { it.isNotBlank() } ?: return null
        return ItemInfoRow(
            label = label,
            value = value,
            mono = o.bool("mono"),
            copy = o.bool("copy"),
        )
    }

    private fun JsonObject.str(k: String): String? {
        val p = this[k] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.contentOrNull
    }

    private fun JsonObject.bool(k: String): Boolean {
        val p = this[k] as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        return p.booleanOrNull ?: (p.contentOrNull == "1")
    }
}

/** the info route on the same cert-pinned client the rest of the player uses */
class ItemInfoApiClient(
    baseUrl: String,
    private val http: OkHttpClient,
) {
    val base: String = baseUrl.trimEnd('/')

    fun url(serverType: String, id: String): String = "$base/api/player/info/${seg(serverType)}/${seg(id)}"

    suspend fun fetch(appType: String, id: String): MusicApiResult<ItemInfo> {
        val type = ItemInfoTypes.serverType(appType)
            ?: return MusicApiResult.Error("There's no info for this kind of item.")
        if (id.isBlank()) return MusicApiResult.Error("There's no info for this item.")
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url(type, id)).get().build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> MusicApiResult.Success(ItemInfoParser.parse(body))
                        r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                        else -> MusicApiResult.Error(message(r.code, body))
                    }
                }
            } catch (e: IllegalStateException) {
                MusicApiResult.Error(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                MusicApiResult.Error("Can't reach SoulSync.")
            }
        }
    }

    private fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        fun message(code: Int, body: String): String {
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
            str("message")?.let { return it }
            return when (code) {
                401 -> "Not signed in. Pick your profile again."
                403 -> "This profile can't see this."
                404 -> "This isn't on the server any more."
                else -> str("error")?.takeIf { ' ' in it } ?: "The server couldn't load the info. Try again."
            }
        }
    }
}

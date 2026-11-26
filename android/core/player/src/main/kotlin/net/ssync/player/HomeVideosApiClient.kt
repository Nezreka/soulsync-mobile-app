package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** the /api/player/home routes: a home videos library's page, its clips, and the edit sheet */
class HomeVideosApiClient(
    baseUrl: String,
    private val http: OkHttpClient,
) {
    val base: String = baseUrl.trimEnd('/')
    private val root = "$base/api/player/home"

    suspend fun overview(library: Int): MusicApiResult<HomeOverview> =
        get("$root/overview?library=$library") { HomeVideoParser.overview(it) }

    /** one page of a month (newest first), an album (oldest first) or a search */
    suspend fun videos(
        library: Int,
        month: String? = null,
        album: String? = null,
        query: String? = null,
        offset: Int = 0,
        limit: Int = HomePaging.PAGE,
    ): MusicApiResult<HomeVideoPage> {
        val b = "$root/videos".toHttpUrl().newBuilder().addQueryParameter("library", library.toString())
        month?.let { b.addQueryParameter("month", it) }
        album?.let { b.addQueryParameter("album", it) }
        query?.takeIf { it.isNotBlank() }?.let { b.addQueryParameter("q", it.trim()) }
        b.addQueryParameter("offset", offset.toString())
        b.addQueryParameter("limit", limit.toString())
        return get(b.build().toString()) { HomeVideoParser.page(it) }
    }

    suspend fun detail(id: String): MusicApiResult<HomeVideoDetail> =
        get("$root/video/${seg(id)}") { HomeVideoParser.detail(it) ?: throw IllegalStateException("This video isn't on the server any more.") }

    /**
     * saves the edit sheet. only the fields in [changes] are sent; a JsonNull
     * puts a field back to automatic. the answer is the saved video when the
     * server sends it back.
     */
    suspend fun update(id: String, changes: Map<String, JsonElement>): MusicApiResult<HomeVideo?> = withContext(Dispatchers.IO) {
        val body = JsonObject(changes).toString()
        execute(Request.Builder().url("$root/video/${seg(id)}").put(body.toRequestBody(JSON_TYPE)).build(), FORBIDDEN_EDIT) {
            HomeVideoParser.videoOrNull(it)
        }
    }

    fun thumbUrl(id: String): String = "$root/thumb/${seg(id)}"

    fun frameUrl(id: String, atS: Double): String = "$root/frame/${seg(id)}?at=${"%.1f".format(java.util.Locale.US, atS)}"

    /** a path or url from the server as a full url */
    fun absolute(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "$base$url"
        else -> "$base/$url"
    }

    private fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> get(url: String, parse: (String) -> T): MusicApiResult<T> = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).get().build(), FORBIDDEN_SEE, parse)
    }

    private fun <T> execute(request: Request, forbidden: String, parse: (String) -> T): MusicApiResult<T> = try {
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful -> MusicApiResult.Success(parse(body))
                r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                else -> MusicApiResult.Error(message(r.code, body, forbidden))
            }
        }
    } catch (e: IllegalStateException) {
        MusicApiResult.Error(e.message ?: "Something went wrong.")
    } catch (e: Exception) {
        MusicApiResult.Error("Can't reach SoulSync.")
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** the server's own words when it sends any, else plain ones for the status */
        const val FORBIDDEN_EDIT = "Only an admin can change this."
        const val FORBIDDEN_SEE = "This profile can't see this library."

        fun message(code: Int, body: String, forbidden: String = FORBIDDEN_SEE): String {
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
            str("message")?.let { return it }
            return when (code) {
                401 -> "Not signed in. Pick your profile again."
                403 -> forbidden
                404 -> "This isn't on the server any more."
                else -> str("error")?.takeIf { ' ' in it } ?: "The server couldn't do that. Try again."
            }
        }
    }
}

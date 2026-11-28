package net.ssync.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * a subtitle the server knows for a video: one inside the file (`s<n>`) or a
 * file beside it (`x<n>`), ai made ones included. text ones come as WebVTT
 * from the server and the app draws them itself, so a delay can move them.
 */
data class ServerSubtitle(
    val id: String,
    val language: String? = null,
    val title: String? = null,
    val codec: String = "",
    val isDefault: Boolean = false,
    val forced: Boolean = false,
    val sdh: Boolean = false,
    val external: Boolean = false,
    val kind: String = "unknown",
    val ai: Boolean = false,
    val lowConfidence: Boolean = false,
    val delayMs: Int = 0,
) {
    val isText: Boolean get() = kind == "text"
}

/** one ai subtitle job on the server (whisper) */
data class AiSubtitleJob(
    val id: Long,
    val state: String,
    val language: String = "",
    val progress: Double = 0.0,
    val stage: String? = null,
    val etaSeconds: Long? = null,
    val position: Int? = null,
    val confidence: Double? = null,
    val verdict: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = state in ACTIVE
    val isDone: Boolean get() = state == "done"

    /** the words for a job that ended without subtitles, null when it didn't */
    val problem: String?
        get() = when (state) {
            "failed" -> error ?: "Making them didn't work."
            "no_match" -> error ?: "The audio isn't in that language."
            "discarded" -> error ?: "What came out looked made up, so it wasn't kept."
            else -> null
        }

    /** a short line for the row: place in line, percent and time left */
    fun statusLine(): String = when (state) {
        "queued" -> when (val p = position) {
            null, 0 -> "Waiting in line"
            1 -> "Next in line"
            else -> "${ordinal(p)} in line"
        }
        "paused" -> "Waiting while someone else watches"
        "running" -> buildString {
            append("Making subtitles · ${percent(progress)}%")
            etaText(etaSeconds)?.let { append(" · ").append(it) }
        }
        "done" -> "Ready"
        "cancelled" -> "Stopped"
        else -> problem ?: state
    }

    companion object {
        val ACTIVE = setOf("queued", "running", "paused")

        fun percent(progress: Double): Int = (progress.coerceIn(0.0, 1.0) * 100.0).toInt()

        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }

        /** "about 6 min left"; null when the server can't say yet */
        fun etaText(seconds: Long?): String? {
            if (seconds == null || seconds <= 0L) return null
            if (seconds < 60L) return "less than a minute left"
            val mins = ((seconds + 30L) / 60L).coerceAtLeast(1L)
            if (mins < 90L) return "about $mins min left"
            val h = mins / 60L
            val m = mins % 60L
            return if (m == 0L) "about $h h left" else "about $h h $m min left"
        }
    }
}

/** endpoint 5's answer: the jobs plus whether the server can make any */
data class AiSubtitleJobs(
    val jobs: List<AiSubtitleJob> = emptyList(),
    val available: Boolean = true,
    val why: String? = null,
    val watching: Boolean = false,
) {
    /** the newest job for [lang], in whichever code the server wrote it */
    fun forLanguage(lang: String): AiSubtitleJob? = jobs.firstOrNull { SubtitleLanguages.same(it.language, lang) }
}

/** the server's json, read by hand so a missing field never breaks the player */
object ServerSubtitleParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(k: String): Boolean =
        (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false

    private fun JsonObject.num(k: String): Double? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private fun JsonObject.long(k: String): Long? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    fun subtitle(o: JsonObject): ServerSubtitle? {
        val id = o.str("id") ?: return null
        return ServerSubtitle(
            id = id,
            language = o.str("language"),
            title = o.str("title"),
            codec = o.str("codec").orEmpty(),
            isDefault = o.bool("default"),
            forced = o.bool("forced"),
            sdh = o.bool("sdh"),
            external = o.bool("external"),
            kind = o.str("kind") ?: "unknown",
            ai = o.bool("ai"),
            lowConfidence = o.bool("low_confidence"),
            delayMs = SubtitleDelay.clamp((o.long("delay_ms") ?: 0L).toInt()),
        )
    }

    /** endpoint 1: the subtitles of `GET /api/player/tracks/...` */
    fun tracks(body: String): List<ServerSubtitle> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["subtitles"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::subtitle) }
    }

    fun job(o: JsonObject): AiSubtitleJob? {
        val id = o.long("id") ?: return null
        return AiSubtitleJob(
            id = id,
            state = o.str("state") ?: "queued",
            language = o.str("language").orEmpty(),
            progress = o.num("progress") ?: 0.0,
            stage = o.str("stage"),
            etaSeconds = o.long("eta_seconds"),
            position = o.long("position")?.toInt(),
            confidence = o.num("confidence"),
            verdict = o.str("verdict"),
            error = o.str("error"),
        )
    }

    /** endpoint 4: `{job:{...}}` */
    fun startedJob(body: String): AiSubtitleJob? {
        val root = json.parseToJsonElement(body).jsonObject
        return (root["job"] as? JsonObject)?.let(::job)
    }

    /** endpoint 5: `{jobs, available, why, watching}` */
    fun jobs(body: String): AiSubtitleJobs {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["jobs"] as? JsonArray
        return AiSubtitleJobs(
            jobs = arr?.mapNotNull { (it as? JsonObject)?.let(::job) }.orEmpty(),
            available = (root["available"] as? JsonPrimitive)?.booleanOrNull ?: true,
            why = root.str("why"),
            watching = root.bool("watching"),
        )
    }
}

/** language names and matching: sidecars say "en", tracks inside a file say "eng" */
object SubtitleLanguages {
    private val iso3to2: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (code in java.util.Locale.getISOLanguages()) {
            runCatching { java.util.Locale(code).isO3Language }.getOrNull()?.let { m[it.lowercase()] = code }
        }
        // the bibliographic codes some files use
        mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
            "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
            "tib" to "bo", "wel" to "cy").forEach { (k, v) -> m.putIfAbsent(k, v) }
        m
    }

    /** a code as its two letter form when there is one ("eng" -> "en", "pt-BR" -> "pt") */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotBlank() } ?: return null
        if (c.length == 3) return iso3to2[c] ?: c
        return c
    }

    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }

    /** "English", or the code itself when the phone doesn't know it */
    fun name(code: String?): String {
        val c = normalize(code) ?: return "Unknown language"
        if (c == "und") return "Unknown language"
        val n = runCatching { java.util.Locale(c).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
        return if (n.isNullOrBlank() || n.equals(c, ignoreCase = true)) c.uppercase() else n
    }

    /** "English (AI)", "English · Commentary" */
    fun label(s: ServerSubtitle): String {
        val lang = name(s.language) + if (s.ai) " (AI)" else ""
        return if (!s.title.isNullOrBlank() && !s.ai) "$lang · ${s.title}" else lang
    }

    /** the small line under a label: low confidence, forced, sdh, file */
    fun detail(s: ServerSubtitle): String = listOfNotNull(
        if (s.ai && s.lowConfidence) "low confidence" else null,
        if (s.forced) "Forced" else null,
        if (s.sdh) "SDH" else null,
        if (s.external && !s.ai) "File" else null,
    ).joinToString(" · ")
}

/** one line of a subtitle: shown from [startMs] until [endMs] */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** a small WebVTT reader: timings and text only, tags stripped */
object WebVtt {
    private val TAG = Regex("<[^>]*>")
    private val BLOCK_SPLIT = Regex("\n[ \t]*\n")

    fun parse(vtt: String): List<SubtitleCue> {
        val text = vtt.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<SubtitleCue>()
        for (block in text.split(BLOCK_SPLIT)) {
            val lines = block.split('\n')
            val at = lines.indexOfFirst { "-->" in it }
            if (at < 0) continue
            val head = lines[at]
            val parts = head.split("-->")
            if (parts.size < 2) continue
            val start = time(parts[0].trim()) ?: continue
            val end = time(parts[1].trim().substringBefore(' ').substringBefore('\t')) ?: continue
            if (end <= start) continue
            val body = lines.drop(at + 1).joinToString("\n") { clean(it) }.trim('\n', ' ')
            if (body.isBlank()) continue
            out.add(SubtitleCue(start, end, body))
        }
        out.sortBy { it.startMs }
        return out
    }

    /** "01:02:03.456", "02:03.456" (a comma works too) in ms */
    fun time(s: String): Long? {
        val t = s.trim().replace(',', '.')
        val main = t.substringBefore('.')
        val frac = t.substringAfter('.', "0").takeWhile { it.isDigit() }.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        val bits = main.split(':').map { it.trim().toLongOrNull() ?: return null }
        val secs = when (bits.size) {
            3 -> bits[0] * 3600L + bits[1] * 60L + bits[2]
            2 -> bits[0] * 60L + bits[1]
            else -> return null
        }
        return secs * 1000L + frac
    }

    private fun clean(line: String): String = TAG.replace(line, "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&lrm;", "").replace("&rlm;", "").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim()

    /** the cues on screen at [ms], oldest first ([cues] sorted by start) */
    fun activeAt(cues: List<SubtitleCue>, ms: Long): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= ms) { last = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (last < 0) return emptyList()
        val out = ArrayList<SubtitleCue>(2)
        // overlapping cues are rare and short: a few back is plenty
        var i = last
        while (i >= 0 && last - i < 24) {
            val c = cues[i]
            if (c.endMs > ms) out.add(c)
            i--
        }
        out.reverse()
        return out
    }
}

/** a subtitle delay: positive shows lines later, negative earlier */
object SubtitleDelay {
    const val MAX_MS = 30_000
    const val SMALL_STEP_MS = 100
    const val BIG_STEP_MS = 1_000

    fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS)

    /** where on the subtitle's clock the picture is at [positionMs] */
    fun subtitleClock(positionMs: Long, delayMs: Int): Long = positionMs - delayMs

    /** "In sync", "0.3 s later", "1.2 s earlier" */
    fun describe(ms: Int): String {
        if (ms == 0) return "In sync"
        val secs = String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(ms) / 1000.0)
        return if (ms > 0) "$secs s later" else "$secs s earlier"
    }
}

/**
 * saves a delay once the nudging stops: every push restarts the wait. a push
 * for another subtitle saves the one still waiting first, so nothing is lost.
 */
class DelaySaveDebouncer(
    private val scope: CoroutineScope,
    private val waitMs: Long = 600L,
    private val save: suspend (subId: String, delayMs: Int) -> Unit,
) {
    private var job: Job? = null
    private var pending: Pair<String, Int>? = null

    fun push(subId: String, delayMs: Int) {
        val prev = pending
        job?.cancel()
        if (prev != null && prev.first != subId) {
            scope.launch { save(prev.first, prev.second) }
        }
        pending = subId to delayMs
        job = scope.launch {
            delay(waitMs)
            val p = pending ?: return@launch
            pending = null
            save(p.first, p.second)
        }
    }

    /** saves what's waiting now (the player is closing) */
    fun flush() {
        val p = pending ?: return
        pending = null
        job?.cancel()
        scope.launch { save(p.first, p.second) }
    }
}

/** the player's subtitle routes: tracks, WebVTT, the saved delay, and ai jobs */
class SubtitleApiClient(
    baseUrl: String,
    private val http: OkHttpClient,
) {
    val base: String = baseUrl.trimEnd('/')

    private fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun subUrl(path: String, itemType: String, itemId: String, part: String?, sub: String?): String {
        val b = "$base/api/player/$path/${seg(itemType)}/${seg(itemId)}".toHttpUrl().newBuilder()
        part?.takeIf { it.isNotBlank() }?.let { b.addQueryParameter("part", it) }
        sub?.let { b.addQueryParameter("sub", it) }
        return b.build().toString()
    }

    suspend fun tracks(itemType: String, itemId: String, part: String? = null): MusicApiResult<List<ServerSubtitle>> =
        call(Request.Builder().url(subUrl("tracks", itemType, itemId, part, null)).get().build()) {
            ServerSubtitleParser.tracks(it)
        }

    /** the subtitle as WebVTT; never a cached copy, a subtitle file can change */
    suspend fun webVtt(itemType: String, itemId: String, sub: String, part: String? = null): MusicApiResult<String> =
        call(
            Request.Builder().url(subUrl("subtitle", itemType, itemId, part, sub))
                .header("Cache-Control", "no-cache").get().build(),
        ) { it }

    suspend fun saveDelay(itemType: String, itemId: String, sub: String, delayMs: Int, part: String? = null): MusicApiResult<Int> {
        val body = """{"delay_ms":${SubtitleDelay.clamp(delayMs)}}"""
        return call(
            Request.Builder().url(subUrl("subtitle-delay", itemType, itemId, part, sub))
                .put(body.toRequestBody(JSON_TYPE)).build(),
        ) { b ->
            val o = runCatching { Json.parseToJsonElement(b).jsonObject }.getOrNull()
            (o?.get("delay_ms") as? JsonPrimitive)?.longOrNull?.toInt() ?: delayMs
        }
    }

    suspend fun startAiJob(kind: String, itemId: Int, lang: String): MusicApiResult<AiSubtitleJob> {
        val body = """{"kind":"$kind","item_id":$itemId,"lang":"${lang.filter { it.isLetterOrDigit() || it == '-' }}","origin":"player"}"""
        return call(
            Request.Builder().url("$base/api/video/subtitles/ai/jobs").post(body.toRequestBody(JSON_TYPE)).build(),
        ) { ServerSubtitleParser.startedJob(it) ?: throw IllegalStateException("The server didn't start it.") }
    }

    suspend fun aiJobs(kind: String, itemId: Int): MusicApiResult<AiSubtitleJobs> {
        val url = "$base/api/video/subtitles/ai/jobs".toHttpUrl().newBuilder()
            .addQueryParameter("kind", kind).addQueryParameter("item_id", itemId.toString()).build()
        return call(Request.Builder().url(url).get().build()) { ServerSubtitleParser.jobs(it) }
    }

    suspend fun cancelAiJob(jobId: Long): MusicApiResult<Unit> =
        call(
            Request.Builder().url("$base/api/video/subtitles/ai/jobs/$jobId/cancel")
                .post("{}".toRequestBody(JSON_TYPE)).build(),
        ) { }

    private suspend fun <T> call(request: Request, parse: (String) -> T): MusicApiResult<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> MusicApiResult.Success(parse(body))
                    r.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                    else -> MusicApiResult.Error(HomeVideosApiClient.message(r.code, body, FORBIDDEN))
                }
            }
        } catch (e: IllegalStateException) {
            MusicApiResult.Error(e.message ?: "Something went wrong.")
        } catch (e: Exception) {
            MusicApiResult.Error("Can't reach SoulSync.")
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val FORBIDDEN = "This profile can't do that."
    }
}

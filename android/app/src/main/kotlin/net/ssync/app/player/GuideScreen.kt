package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.CatalogMovie
import net.ssync.player.CatalogShow
import net.ssync.player.CatalogShowEpisode
import net.ssync.player.CatalogHome
import net.ssync.player.CatalogYoutube
import net.ssync.player.CommercialClip
import net.ssync.player.GuidePremiere
import net.ssync.player.CommercialsConfig
import net.ssync.player.GuideChannelSpec
import net.ssync.player.GuideLineupEntry
import net.ssync.player.LibSong
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicChannel
import net.ssync.player.MusicChannelTrack
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlaylistDetail
import net.ssync.player.SavedGuideChannel
import net.ssync.player.VideoCatalog
import net.ssync.player.StudioCollection
import net.ssync.player.WatchingSession
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ── Desktop-Parity Channel Model & Grouping (mirrors webui/.../channels.ts) ──

private enum class ChannelKind(val groupLabel: String) {
    NETWORK("Networks"),
    PICK("Picks"),
    SERVER("Channels for everyone"),
    RULE("Your channels"),
    TYPE("Everything"),
    GENRE("Movie and TV genres"),
    STUDIO("Studios"),
    COLLECTION("Film series"),
    DECADE("Decades"),
    KIDS("Kids"),
    PLAYLIST("Your playlists"),
    VIBE("Vibes"),
    MUSIC("Music"),
}

private data class PoolItem(
    val key: String,
    val itemType: String, // "movie" | "episode" | "youtube" | "home" | "track"
    val itemId: String,
    val detailId: Int?,
    val title: String,
    val subtitle: String,
    val seconds: Int,
    val posterUrl: String?,
    val backdropUrl: String?,
)

private data class EpgProgram(
    val itemType: String,
    val itemId: String,
    val detailId: Int?,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val startEpochSec: Long,
    val endEpochSec: Long,
    /** something new, airing in the slot the server gave it */
    val premiere: Boolean = false,
)

/** a premiere on one channel: the item and when it airs (epoch ms) */
private data class PremiereSlot(val item: PoolItem, val atMs: Long)

/**
 * each channel's premieres, by channel id, the web guide's premieresFor: the
 * server gives the slot, the item and the channels it could air on, best
 * first, and this puts each on the first of those the guide has. the home is
 * picked among every video channel, hidden ones too, so a profile that hid a
 * channel doesn't move its premiere onto one everyone else also watches.
 */
private fun premieresByChannel(
    premieres: List<GuidePremiere>,
    channels: List<RawChannel>,
    api: PlayerApiClient,
): Map<String, List<PremiereSlot>> {
    if (premieres.isEmpty()) return emptyMap()
    val hosts = channels.filter { !it.isMusic && !it.ordered }.map { it.id }.toSet()
    val out = LinkedHashMap<String, MutableList<PremiereSlot>>()
    for (p in premieres) {
        val home = p.homes.firstOrNull { it in hosts } ?: continue
        if (p.seconds <= 0) continue
        val show = p.kind == "episode"
        val owner = if (show) (p.showId ?: p.id) else p.id
        val poster = when {
            !p.hasPoster -> null
            show -> api.artwork.showPoster(owner)
            else -> api.artwork.moviePoster(owner)
        }
        out.getOrPut(home) { mutableListOf() } += PremiereSlot(
            item = PoolItem(
                key = p.key,
                itemType = if (show) "episode" else "movie",
                itemId = p.id.toString(),
                detailId = owner,
                title = p.title,
                subtitle = p.subtitle.orEmpty(),
                seconds = p.seconds,
                posterUrl = poster,
                backdropUrl = poster,
            ),
            atMs = p.at,
        )
    }
    return out
}

private data class EpgChannel(
    val id: String,
    val number: Int,
    val name: String,
    val kind: ChannelKind,
    val blurb: String,
    val isMusic: Boolean,
    val mosaicArt: List<String>,
    val programs: List<EpgProgram>,
    val musicPool: List<PoolItem> = emptyList(),
)

private data class ChannelGroupSection(
    val key: String,
    val label: String,
    val channels: List<EpgChannel>,
)

private enum class GuideViewMode(val label: String) { GRID("Grid"), ON_NOW("On now") }

private enum class GuideFilter(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    VIDEO("Video"),
    MUSIC("Music"),
    KIDS("Kids"),
}

private val PX_PER_MIN: Dp = 3.6.dp
private val CHANNEL_COL_WIDTH: Dp = 128.dp
private val ROW_HEIGHT: Dp = 68.dp

private const val MIN_POOL = 3
private val KID_RATINGS = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")
private val LITTLE_RATINGS = setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "G")

private fun isKidSafe(rating: String?): Boolean =
    !rating.isNullOrBlank() && rating.trim().uppercase() in KID_RATINGS

private fun isLittleKidSafe(rating: String?): Boolean =
    !rating.isNullOrBlank() && rating.trim().uppercase() in LITTLE_RATINGS

private fun decadeOf(year: Int?): Int? =
    if (year != null && year >= 1900) (year / 10) * 10 else null

private fun decadeLabel(decade: Int): String = "${decade.toString().takeLast(2)}s"

private fun slug(text: String): String {
    val norm = Normalizer.normalize(text.lowercase(Locale.US), Normalizer.Form.NFKD)
    return norm.replace(Regex("[^\\w]+"), "-").trim('-')
}

private val isoUtcFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
}

private fun parseIsoEpochMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val clean = raw.trim().replace(' ', 'T').substringBefore('.').removeSuffix("Z")
    return runCatching {
        isoUtcFormat.get()?.parse(clean)?.time
    }.getOrNull()
}

private fun parseShowEpisodes(show: CatalogShow): List<CatalogShowEpisode> =
    show.episodes.mapNotNull { arr ->
        val id = arr.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val season = arr.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 1
        val epNum = arr.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 1
        val secs = arr.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 2700
        val title = arr.getOrNull(4)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        CatalogShowEpisode(id, season, epNum, secs, title)
    }

private fun moviePoolItem(m: CatalogMovie, api: PlayerApiClient): PoolItem = PoolItem(
    key = "movie:${m.id}",
    itemType = "movie",
    itemId = m.id.toString(),
    detailId = m.id,
    title = m.title,
    subtitle = listOfNotNull(m.year?.toString(), m.contentRating).joinToString(" · "),
    seconds = m.runtimeSeconds ?: 0,
    posterUrl = if (m.hasPoster) api.artwork.moviePoster(m.id) else null,
    backdropUrl = if (m.hasBackdrop) api.artwork.movieBackdrop(m.id) else api.artwork.moviePoster(m.id),
)

private fun showEpisodePoolItems(s: CatalogShow, api: PlayerApiClient): List<PoolItem> {
    val poster = if (s.hasPoster) api.artwork.showPoster(s.id) else null
    val backdrop = if (s.hasBackdrop) api.artwork.showBackdrop(s.id) else poster
    return parseShowEpisodes(s).map { ep ->
        PoolItem(
            key = "episode:${ep.id}",
            itemType = "episode",
            itemId = ep.id.toString(),
            detailId = s.id,
            title = s.title,
            subtitle = "S${ep.season} E${ep.episode}" + (ep.title?.let { " · $it" } ?: ""),
            seconds = ep.seconds,
            posterUrl = poster,
            backdropUrl = backdrop,
        )
    }
}

private fun youtubePoolItem(v: CatalogYoutube, api: PlayerApiClient): PoolItem = PoolItem(
    key = "youtube:${v.id}",
    itemType = "youtube",
    itemId = v.id,
    detailId = null,
    title = v.title,
    subtitle = v.channelTitle.orEmpty(),
    seconds = v.durationSeconds ?: 0,
    posterUrl = api.artwork.resolve(v.thumbnailUrl) ?: api.artwork.forItem("youtube", v.id),
    backdropUrl = api.artwork.resolve(v.thumbnailUrl) ?: api.artwork.forItem("youtube", v.id),
)

/** a home video as a guide item (the web's homeGuideItem): no picture until the server has made one */
private fun homePoolItem(v: CatalogHome, api: PlayerApiClient): PoolItem {
    val art = api.artwork.resolve(v.thumbUrl)
    return PoolItem(
        key = "home:${v.id}",
        itemType = "home",
        itemId = v.id,
        detailId = null,
        title = v.title,
        subtitle = v.album,
        seconds = v.durationS.toInt(),
        posterUrl = art,
        backdropUrl = art,
    )
}

private fun songPoolItem(s: LibSong, api: PlayerApiClient): PoolItem {
    val secs = s.duration?.let { if (it > 10_000L) (it / 1000L).toInt() else it.toInt() } ?: 210
    val art = api.artwork.albumArtwork(s.albumId)
    return PoolItem(
        key = "track:${s.id}",
        itemType = "track",
        itemId = s.id.toString(),
        detailId = s.albumId,
        title = s.title,
        subtitle = s.artistName ?: "Unknown Artist",
        seconds = secs,
        posterUrl = art,
        backdropUrl = art,
    )
}

private fun musicChannelTrackItem(t: MusicChannelTrack, api: PlayerApiClient): PoolItem {
    val art = api.artwork.albumArtwork(t.albumId)
    return PoolItem(
        key = "track:${t.id}",
        itemType = "track",
        itemId = t.id.toString(),
        detailId = t.albumId,
        title = t.title,
        subtitle = t.artist,
        seconds = t.seconds,
        posterUrl = art,
        backdropUrl = art,
    )
}

private fun hasEnoughPool(pool: List<PoolItem>): Boolean {
    val seen = HashSet<String>()
    for (item in pool) {
        if (item.seconds < 60) continue
        seen += item.key
        if (seen.size >= MIN_POOL) return true
    }
    return false
}

private const val GUIDE_MIN_SECONDS = 60
private const val GUIDE_MAX_SECONDS = 6 * 60 * 60

/** Exact 32-bit unsigned FNV-1a matching Desktop schedule.ts hashSeed() bit-for-bit */
private fun hashSeed32(text: String): UInt {
    var h = 0x811c9dc5u
    for (ch in text) {
        h = (h xor ch.code.toUInt()) * 0x01000193u
    }
    return h
}

/** Exact mulberry32 PRNG matching Desktop schedule.ts seededRandom() bit-for-bit */
private fun mulberry32(seed: UInt): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5u
        var t = a
        t = (t xor (t shr 15)) * (t or 1u)
        t = t xor (t + ((t xor (t shr 7)) * (t or 61u)))
        ((t xor (t shr 14)).toDouble()) / 4294967296.0
    }
}

/** Exact Fisher-Yates matching Desktop schedule.ts seededShuffle() bit-for-bit */
private fun <T> seededShuffle32(items: List<T>, seed: UInt): List<T> {
    if (items.size <= 1) return items
    val out = items.toMutableList()
    val rand = mulberry32(seed)
    for (i in out.lastIndex downTo 1) {
        val j = kotlin.math.floor(rand() * (i + 1)).toInt().coerceIn(0, i)
        val tmp = out[i]
        out[i] = out[j]
        out[j] = tmp
    }
    return out
}

/** Exact lazy forward Fisher-Yates matching Desktop schedule.ts seededOrder() bit-for-bit */
private fun <T> makeSeededDrawer(pool: List<T>, seed: UInt, salt: UInt): () -> T? {
    if (pool.isEmpty()) return { null }
    var lap = 0u
    var rand = mulberry32(seed + salt)
    var swapped = HashMap<Int, Int>(32)
    var i = 0
    return {
        val n = pool.size
        if (i >= n) {
            lap += 1u
            rand = mulberry32(seed + salt + lap)
            swapped.clear()
            i = 0
        }
        val j = i + kotlin.math.floor(rand() * (n - i)).toInt().coerceIn(0, n - i - 1)
        val atJ = swapped[j] ?: j
        swapped[j] = swapped[i] ?: i
        i++
        pool[atJ]
    }
}

// a day is counted on the guide's clock (the server's, net.ssync.player.GuideClock),
// not this device's: the same midnight and the same date on every device
private fun startOfLocalDayMs(epochMs: Long): Long = net.ssync.player.GuideClock.startOfDayMs(epochMs)

private fun dayKeyIso(dayStartMs: Long): String {
    val cal = net.ssync.player.GuideClock.calendar(dayStartMs)
    val y = cal.get(java.util.Calendar.YEAR)
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(Locale.US, y, m, d)
}

private fun isFilmHourFromDayStart(epochMs: Long, dayStartMs: Long): Boolean {
    val h = (((epochMs - dayStartMs) / 3_600_000L).toInt() % 24 + 24) % 24
    return h >= 18 || h < 2
}

private fun deterministicHash(seed: String): Long = hashSeed32(seed).toLong()

/**
 * Exact 1:1 port of Desktop schedule.ts (scheduleDay + programsInWindow):
 * - Same 32-bit FNV-1a hashSeed + mulberry32 PRNG + zero-padded YYYY-MM-DD dayKey
 * - Same cable-style show rotation (drawRotatedShows), dayparting (6pm-2am films),
 *   and ordered marathon progression so Desktop and Android always air the EXACT
 *   same title at the EXACT same minute on every channel!
 */
private fun scheduleVideoPrograms(
    channelId: String,
    pool: List<PoolItem>,
    ordered: Boolean,
    windowStartSec: Long,
    windowEndSec: Long,
    commercialsConfig: CommercialsConfig? = null,
    premieres: List<PremiereSlot> = emptyList(),
): List<EpgProgram> {
    val items = pool
        .filter { it.seconds >= GUIDE_MIN_SECONDS }
        .map { if (it.seconds > GUIDE_MAX_SECONDS) it.copy(seconds = GUIDE_MAX_SECONDS) else it }
    if (items.isEmpty() || windowEndSec <= windowStartSec) return emptyList()

    val fromMs = windowStartSec * 1000L
    val toMs = windowEndSec * 1000L
    val commClips = if (commercialsConfig?.enabled == true && commercialsConfig.clips.isNotEmpty()) {
        commercialsConfig.clips
    } else {
        null
    }

    fun inferChannelEra(cid: String): String {
        val low = cid.lowercase(Locale.US)
        return when {
            low.contains("1970") || low.contains("70s") -> "70s"
            low.contains("1980") || low.contains("80s") -> "80s"
            low.contains("1990") || low.contains("90s") -> "90s"
            low.contains("2000") || low.contains("00s") -> "2000s"
            low.contains("kid") || low.contains("animat") || low.contains("family") -> "kids"
            low.contains("horror") || low.contains("late") || low.contains("thriller") -> "horror"
            else -> "mix"
        }
    }

    fun eraForYear(y: Int?): String? = when {
        y == null -> null
        y in 1970..1979 -> "70s"
        y in 1980..1989 -> "80s"
        y in 1990..1999 -> "90s"
        y in 2000..2011 -> "2000s"
        else -> null
    }

    val yearRegex = Regex("\\b(19[5-9]\\d|20[0-2]\\d)\\b")

    fun scheduleDayExact(dayStartMs: Long): List<EpgProgram> {
        val dayEndMs = dayStartMs + 24L * 60L * 60L * 1000L
        val dayStamp = dayKeyIso(dayStartMs)
        val seed = hashSeed32("$channelId|$dayStamp")
        val out = mutableListOf<EpgProgram>()
        var cursor = dayStartMs
        var contentSinceBreakSec = 0
        var lastBreakIds = emptySet<String>()
        // premieres hold their slot: what's on before one is cut at it, and two
        // at the same time air back to back (schedule.ts, the same lines)
        val dayPremieres = premieres
            .filter { it.atMs >= dayStartMs && it.atMs < dayEndMs && it.item.seconds >= GUIDE_MIN_SECONDS }
            .sortedBy { it.atMs }
        var nextPremiere = 0
        fun premiereNow() {
            while (nextPremiere < dayPremieres.size && dayPremieres[nextPremiere].atMs <= cursor) {
                val p = dayPremieres[nextPremiere++]
                val seconds = minOf(p.item.seconds, GUIDE_MAX_SECONDS)
                out += EpgProgram(
                    itemType = p.item.itemType,
                    itemId = p.item.itemId,
                    detailId = p.item.detailId,
                    title = p.item.title,
                    subtitle = p.item.subtitle,
                    artworkUrl = p.item.backdropUrl ?: p.item.posterUrl,
                    startEpochSec = cursor / 1000L,
                    endEpochSec = (cursor + seconds.toLong() * 1000L) / 1000L,
                    premiere = true,
                )
                cursor += seconds.toLong() * 1000L
            }
        }

        fun placeCommercialBreak(afterItem: PoolItem) {
            val comm = commercialsConfig ?: return
            val clips = commClips ?: return
            if (cursor >= dayEndMs) return
            val ym = yearRegex.find("${afterItem.subtitle} ${afterItem.title}")
            val itemYear = ym?.groupValues?.get(1)?.toIntOrNull()
            val yearEra = if (comm.era == "auto") eraForYear(itemYear) else null
            val targetEra = yearEra ?: if (comm.era == "auto") inferChannelEra(channelId) else comm.era
            val brkSeed = hashSeed32("$channelId|$dayStamp|brk|$cursor")

            val closeYear = if (comm.era == "auto" && itemYear != null && yearEra != null) {
                clips.filter { c -> val cy = c.year; cy != null && abs(cy - itemYear) <= 4 }
            } else {
                emptyList()
            }
            val exactEra = clips.filter { it.era == targetEra }
            val tiers = listOf(closeYear, exactEra, clips)
            val orderedSpots = mutableListOf<CommercialClip>()
            val seenIds = HashSet<String>()
            for (ti in tiers.indices) {
                val shuf = seededShuffle32(tiers[ti], brkSeed + ti.toUInt() * 7919u)
                for (c in shuf) {
                    if (seenIds.add(c.id)) {
                        orderedSpots += c
                    }
                }
            }
            val freshFirst = orderedSpots.filter { it.id !in lastBreakIds } +
                orderedSpots.filter { it.id in lastBreakIds }
            val targetCount = if (comm.mode == "short") 2 else 3
            val budget = if (comm.mode == "short") 65 else 120
            val chosen = mutableListOf<CommercialClip>()
            var usedSec = 0
            for (c in freshFirst) {
                if (chosen.size >= targetCount) break
                val dur = (if (c.durationSeconds > 0) c.durationSeconds else 30).coerceIn(10, 95)
                if (chosen.isNotEmpty() && usedSec + dur > budget) continue
                chosen += c
                usedSec += dur
            }
            if (chosen.isEmpty()) return
            lastBreakIds = chosen.map { it.id }.toSet()
            for (c in chosen) {
                if (cursor >= dayEndMs) break
                val dur = (if (c.durationSeconds > 0) c.durationSeconds else 30).coerceIn(10, 95)
                val cEnd = cursor + dur.toLong() * 1000L
                out += EpgProgram(
                    itemType = "commercial",
                    itemId = c.id,
                    detailId = null,
                    title = c.title,
                    subtitle = "Commercial Break",
                    artworkUrl = null,
                    startEpochSec = cursor / 1000L,
                    endEpochSec = cEnd / 1000L,
                )
                cursor = cEnd
            }
        }

        fun place(item: PoolItem) {
            premiereNow()
            if (cursor >= dayEndMs) return
            var end = cursor + item.seconds.toLong() * 1000L
            val due = dayPremieres.getOrNull(nextPremiere)
            if (due != null && due.atMs < end) end = due.atMs // cut at the premiere
            if (end - cursor >= GUIDE_MIN_SECONDS * 1000L) {
                out += EpgProgram(
                    itemType = item.itemType,
                    itemId = item.itemId,
                    detailId = item.detailId,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.backdropUrl ?: item.posterUrl,
                    startEpochSec = cursor / 1000L,
                    endEpochSec = end / 1000L,
                )
            }
            val airedSec = maxOf(0, kotlin.math.round((end - cursor) / 1000.0).toInt())
            cursor = maxOf(cursor, end)
            if (commClips != null && airedSec > 0) {
                contentSinceBreakSec += airedSec
                if (contentSinceBreakSec >= 300) {
                    placeCommercialBreak(item)
                    contentSinceBreakSec = 0
                }
            }
            premiereNow()
        }

        if (ordered) {
            val dayIndex = kotlin.math.round(dayStartMs.toDouble() / 86400000.0).toLong()
            var index = (((hashSeed32(channelId).toLong() + dayIndex * 7L) % items.size) + items.size).toInt() % items.size
            var guard = 0
            while (cursor < dayEndMs && guard < 400) {
                place(items[index])
                index = (index + 1) % items.size
                guard++
            }
            return out
        }

        fun makeRotatedShowDrawer(subPool: List<PoolItem>, salt: UInt): () -> PoolItem? {
            val byShow = LinkedHashMap<String, MutableList<PoolItem>>()
            val nonEpisodes = mutableListOf<PoolItem>()
            for (item in subPool) {
                val showKey = if (item.itemType == "episode") {
                    item.detailId?.let { "/player/tv/$it" } ?: item.title
                } else null
                if (showKey != null) {
                    byShow.getOrPut(showKey) { mutableListOf() } += item
                } else {
                    nonEpisodes += item
                }
            }
            val showKeys = byShow.keys.toMutableList()
            if (showKeys.size < 2) return makeSeededDrawer(subPool, seed, salt)

            val queues = HashMap<String, Pair<List<PoolItem>, Int>>(showKeys.size + 1)
            val queueIdx = HashMap<String, Int>(showKeys.size + 1)
            for (sk in showKeys) {
                val eps = byShow[sk]!!
                val startOffset = (hashSeed32("$channelId|$sk|$dayStamp") % eps.size.toUInt()).toInt()
                queues[sk] = eps to startOffset
                queueIdx[sk] = startOffset
            }
            if (nonEpisodes.isNotEmpty()) {
                val shuffled = seededShuffle32(nonEpisodes, seed + salt + 31337u)
                queues["__other__"] = shuffled to 0
                queueIdx["__other__"] = 0
                showKeys += "__other__"
            }

            var round = 0u
            var roundOrder = seededShuffle32(showKeys, seed + salt)
            var pos = 0
            var lastShow: String? = null

            return {
                if (pos >= roundOrder.size) {
                    round += 1u
                    val nextOrder = seededShuffle32(showKeys, seed + salt + round * 997u).toMutableList()
                    if (nextOrder.size > 1 && nextOrder[0] == lastShow) {
                        val tmp = nextOrder[0]
                        nextOrder[0] = nextOrder[1]
                        nextOrder[1] = tmp
                    }
                    roundOrder = nextOrder
                    pos = 0
                }
                val sk = roundOrder[pos++]
                lastShow = sk
                val list = queues[sk]!!.first
                val cur = queueIdx[sk] ?: 0
                val item = list[cur % list.size]
                queueIdx[sk] = (cur + 1) % list.size
                item
            }
        }

        val films = items.filter { it.itemType == "movie" }
        val shows = items.filter { it.itemType != "movie" }
        var guard = 0
        if (films.isNotEmpty() && shows.isNotEmpty()) {
            val drawFilm = makeSeededDrawer(films, seed, 1u)
            val drawShow = makeRotatedShowDrawer(shows, 2u)
            while (cursor < dayEndMs && guard < 400) {
                val next = if (isFilmHourFromDayStart(cursor, dayStartMs)) drawFilm() else drawShow()
                if (next == null) break
                place(next)
                guard++
            }
            return out
        }

        val drawAny = makeRotatedShowDrawer(items, 0u)
        while (cursor < dayEndMs && guard < 400) {
            val next = drawAny() ?: break
            place(next)
            guard++
        }
        return out
    }

    val result = mutableListOf<EpgProgram>()
    var dayStart = startOfLocalDayMs(fromMs) - 24L * 60L * 60L * 1000L
    while (dayStart < toMs) {
        val programs = scheduleDayExact(dayStart)
        val nextDayMs = startOfLocalDayMs(dayStart + 36L * 60L * 60L * 1000L)
        val nextDaySec = nextDayMs / 1000L
        for (p in programs) {
            if (p.startEpochSec >= nextDaySec) break
            val clippedEnd = minOf(p.endEpochSec, nextDaySec)
            if (clippedEnd > windowStartSec && p.startEpochSec < windowEndSec) {
                result += p.copy(endEpochSec = clippedEnd)
            }
        }
        dayStart = nextDayMs
    }
    return result
}

private fun scheduleMusicBlocks(
    channelId: String,
    channelName: String,
    blurb: String,
    pool: List<PoolItem>,
    windowStartSec: Long,
    windowEndSec: Long,
): List<EpgProgram> {
    if (pool.isEmpty()) return emptyList()
    // Exact parity with desktop: schedule Day/Window programs with scheduleVideoPrograms,
    // then mergeIntoBlocks(programs, minSeconds = 1800) + blockArtists(block, show = 2).
    val rawPrograms = scheduleVideoPrograms(
        channelId = channelId,
        pool = pool,
        ordered = false,
        windowStartSec = windowStartSec,
        windowEndSec = windowEndSec,
    )
    if (rawPrograms.isEmpty()) return emptyList()
    val minSec = 1800L
    val blocks = mutableListOf<MutableList<EpgProgram>>()
    for (p in rawPrograms) {
        val current = blocks.lastOrNull()
        if (
            current == null ||
            current.last().endEpochSec != p.startEpochSec ||
            (current.last().endEpochSec - current.first().startEpochSec) >= minSec
        ) {
            blocks.add(mutableListOf(p))
        } else {
            current.add(p)
        }
    }
    return blocks.map { items ->
        val lead = items.first()
        val last = items.last()
        val artists = items.mapNotNull { it.subtitle.trim().takeIf { s -> s.isNotEmpty() } }.distinct()
        val sub = when {
            artists.isEmpty() -> blurb
            artists.size <= 2 -> artists.joinToString(", ")
            else -> "${artists.take(2).joinToString(", ")} and ${artists.size - 2} more"
        }
        EpgProgram(
            itemType = "track",
            itemId = lead.itemId,
            detailId = lead.detailId,
            title = channelName,
            subtitle = sub,
            artworkUrl = lead.artworkUrl,
            startEpochSec = lead.startEpochSec,
            endEpochSec = last.endEpochSec,
        )
    }
}

private data class RawChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val blurb: String,
    val pool: List<PoolItem>,
    val isMusic: Boolean = false,
    val ordered: Boolean = false,
)

private fun buildDesktopParityChannels(
    catalog: VideoCatalog,
    musicPoolSongs: List<LibSong>,
    playlists: List<PlaylistDetail>,
    musicChannels: List<MusicChannel>,
    savedChannels: List<SavedGuideChannel>,
    lineup: List<GuideLineupEntry>,
    studioCollections: List<StudioCollection> = emptyList(),
    api: PlayerApiClient,
    windowStartSec: Long,
    windowEndSec: Long,
    commercialsConfig: CommercialsConfig? = null,
    // what's new and when it airs, from the server: the same on every device
    premieres: List<GuidePremiere> = if (GuideWarmCache.baseUrl == api.baseUrl) GuideWarmCache.premieres else emptyList(),
): List<EpgChannel> {
    val raw = mutableListOf<RawChannel>()
    val nowMs = System.currentTimeMillis()
    val monthAgoMs = startOfLocalDayMs(nowMs) - 30L * 86_400_000L

    fun addChannel(c: RawChannel) {
        val playable = c.pool.filter { it.seconds >= 60 }
        if (hasEnoughPool(playable)) {
            raw += c.copy(pool = playable)
        }
    }

    val movies = catalog.movies
    val shows = catalog.shows
    val youtube = catalog.youtube
    val episodesByShow = shows.associate { it.id to showEpisodePoolItems(it, api) }

    // 1. Everything (type): Movies, TV, YouTube, Music
    addChannel(
        RawChannel(
            id = "type-movies",
            name = "Movies",
            kind = ChannelKind.TYPE,
            blurb = "every film you own",
            pool = movies.map { moviePoolItem(it, api) },
        ),
    )
    addChannel(
        RawChannel(
            id = "type-tv",
            name = "TV",
            kind = ChannelKind.TYPE,
            blurb = "episodes from every show",
            pool = shows.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "type-youtube",
            name = "YouTube",
            kind = ChannelKind.TYPE,
            blurb = "your downloaded videos",
            pool = youtube.map { youtubePoolItem(it, api) },
        ),
    )
    // home libraries an admin switched into the guide (off by default); same place as the web's, so channel numbers match
    addChannel(
        RawChannel(
            id = "type-home",
            name = "Home videos",
            kind = ChannelKind.TYPE,
            blurb = "your own clips",
            pool = catalog.home.map { homePoolItem(it, api) },
        ),
    )
    addChannel(
        RawChannel(
            id = "type-music",
            name = "Music",
            kind = ChannelKind.MUSIC,
            blurb = "songs from your library",
            pool = musicPoolSongs.map { songPoolItem(it, api) },
            isMusic = true,
        ),
    )

    // 2. Movie & TV Genres (combined, sorted by total count)
    val genreMap = linkedMapOf<String, Pair<MutableList<CatalogMovie>, MutableList<CatalogShow>>>()
    for (m in movies) {
        for (g in m.genres) {
            genreMap.getOrPut(g) { mutableListOf<CatalogMovie>() to mutableListOf() }.first += m
        }
    }
    for (s in shows) {
        for (g in s.genres) {
            genreMap.getOrPut(g) { mutableListOf<CatalogMovie>() to mutableListOf() }.second += s
        }
    }
    genreMap.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Pair<MutableList<CatalogMovie>, MutableList<CatalogShow>>>> {
                it.value.first.size + it.value.second.size
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .forEach { (genreName, pair) ->
            val (gm, gs) = pair
            val blurb = listOfNotNull(
                if (gm.isNotEmpty()) "${gm.size} films" else null,
                if (gs.isNotEmpty()) "${gs.size} shows" else null,
            ).joinToString(" · ")
            addChannel(
                RawChannel(
                    id = "genre-${slug(genreName)}",
                    name = genreName,
                    kind = ChannelKind.GENRE,
                    blurb = blurb,
                    pool = gm.map { moviePoolItem(it, api) } + gs.flatMap { episodesByShow[it.id].orEmpty() },
                ),
            )
        }

    // 3. Networks (>= 2 shows)
    shows.filter { !it.network.isNullOrBlank() }
        .groupBy { it.network!! }
        .entries
        .filter { it.value.size >= 2 }
        .sortedWith(
            compareByDescending<Map.Entry<String, List<CatalogShow>>> { it.value.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .forEach { (net, list) ->
            addChannel(
                RawChannel(
                    id = "network-${slug(net)}",
                    name = net,
                    kind = ChannelKind.NETWORK,
                    blurb = "${list.size} shows",
                    pool = list.flatMap { episodesByShow[it.id].orEmpty() },
                ),
            )
        }

    // 4. Decades (ascending oldest first, matching desktop)
    movies.mapNotNull { m -> decadeOf(m.year)?.let { it to m } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .forEach { (dec, list) ->
            addChannel(
                RawChannel(
                    id = "decade-$dec",
                    name = "${decadeLabel(dec)} movies",
                    kind = ChannelKind.DECADE,
                    blurb = "${list.size} films from the ${dec}s",
                    pool = list.map { moviePoolItem(it, api) },
                ),
            )
        }

    // 5. Picks: Top rated, New arrivals, Classics, Comfort TV, Late night
    addChannel(
        RawChannel(
            id = "pick-top",
            name = "Top rated",
            kind = ChannelKind.PICK,
            blurb = "films and shows scored 8 or better",
            pool = movies.filter { (it.rating ?: 0.0) >= 8.0 }.map { moviePoolItem(it, api) } +
                shows.filter { (it.rating ?: 0.0) >= 8.0 }.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "pick-new",
            name = "New arrivals",
            kind = ChannelKind.PICK,
            blurb = "added in the last month",
            pool = movies.filter { (parseIsoEpochMs(it.addedAt) ?: 0L) >= monthAgoMs }.map { moviePoolItem(it, api) } +
                shows.filter { (parseIsoEpochMs(it.addedAt) ?: 0L) >= monthAgoMs }.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "pick-classics",
            name = "Classics",
            kind = ChannelKind.PICK,
            blurb = "films before 1980, scored 7 or better",
            pool = movies.filter { (it.year ?: 9999) < 1980 && (it.rating ?: 0.0) >= 7.0 }.map { moviePoolItem(it, api) },
        ),
    )
    addChannel(
        RawChannel(
            id = "pick-comfort",
            name = "Comfort TV",
            kind = ChannelKind.PICK,
            blurb = "sitcoms, back to back",
            pool = shows.filter { s -> s.genres.any { it.equals("comedy", ignoreCase = true) } }
                .flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "pick-late",
            name = "Late night",
            kind = ChannelKind.PICK,
            blurb = "horror and thrillers",
            pool = movies.filter { m ->
                m.genres.any { it.equals("horror", ignoreCase = true) || it.equals("thriller", ignoreCase = true) }
            }.map { moviePoolItem(it, api) },
        ),
    )

    // 6. Kids section (Kids, Little ones, Cartoons, Kids TV, Family movies)
    val kidMovies = movies.filter { isKidSafe(it.contentRating) }
    val kidShows = shows.filter { isKidSafe(it.contentRating) }
    addChannel(
        RawChannel(
            id = "kids",
            name = "Kids",
            kind = ChannelKind.KIDS,
            blurb = "rated G, PG, TV-Y to TV-PG",
            pool = kidMovies.map { moviePoolItem(it, api) } + kidShows.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "kids-little",
            name = "Little ones",
            kind = ChannelKind.KIDS,
            blurb = "rated TV-Y, TV-Y7 and G",
            pool = movies.filter { isLittleKidSafe(it.contentRating) }.map { moviePoolItem(it, api) } +
                shows.filter { isLittleKidSafe(it.contentRating) }.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "kids-cartoons",
            name = "Cartoons",
            kind = ChannelKind.KIDS,
            blurb = "animation for kids",
            pool = kidMovies.filter { m -> m.genres.any { it.equals("animation", ignoreCase = true) } }.map { moviePoolItem(it, api) } +
                kidShows.filter { s -> s.genres.any { it.equals("animation", ignoreCase = true) } }.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "kids-tv",
            name = "Kids TV",
            kind = ChannelKind.KIDS,
            blurb = "shows for kids, back to back",
            pool = kidShows.flatMap { episodesByShow[it.id].orEmpty() },
        ),
    )
    addChannel(
        RawChannel(
            id = "kids-family",
            name = "Family movies",
            kind = ChannelKind.KIDS,
            blurb = "G and PG films for everyone",
            pool = kidMovies.map { moviePoolItem(it, api) },
        ),
    )

    // 7. Studios (>= 6 films) & Film Series (>= 3 films, ordered by year)
    movies.filter { !it.studio.isNullOrBlank() }
        .groupBy { it.studio!! }
        .entries
        .filter { it.value.size >= 6 }
        .sortedWith(
            compareByDescending<Map.Entry<String, List<CatalogMovie>>> { it.value.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .forEach { (studio, list) ->
            addChannel(
                RawChannel(
                    id = "studio-${slug(studio)}",
                    name = studio,
                    kind = ChannelKind.STUDIO,
                    blurb = "${list.size} films",
                    pool = list.map { moviePoolItem(it, api) },
                ),
            )
        }
    movies.filter { !it.collection.isNullOrBlank() }
        .groupBy { it.collection!! }
        .entries
        .filter { it.value.size >= 3 }
        .sortedWith(
            compareByDescending<Map.Entry<String, List<CatalogMovie>>> { it.value.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .forEach { (col, list) ->
            val inOrder = list.sortedBy { it.year ?: 0 }
            addChannel(
                RawChannel(
                    id = "collection-${slug(col)}",
                    name = col.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), ""),
                    kind = ChannelKind.COLLECTION,
                    blurb = "${list.size} films, in order",
                    pool = inOrder.map { moviePoolItem(it, api) },
                    ordered = true,
                ),
            )
        }

    // 7b. SoulSync Collection Studio 24/7 Channels (1:1 parity with Desktop studioCollectionChannels)
    if (studioCollections.isNotEmpty()) {
        val moviesById = movies.associateBy { it.id }
        val showsById = shows.associateBy { it.id }
        studioCollections.forEach { col ->
            val pool = if (col.mediaType == "movie") {
                col.members
                    .mapNotNull { moviesById[it] }
                    .sortedBy { it.year ?: 0 }
                    .map { moviePoolItem(it, api) }
            } else {
                col.members
                    .mapNotNull { showsById[it] }
                    .flatMap { episodesByShow[it.id].orEmpty() }
            }
            if (pool.size >= 2) {
                val cleanName = col.name.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), "").ifBlank { col.name }
                val playable = pool.filter { it.seconds >= 60 }
                if (playable.size >= 2) {
                    raw += RawChannel(
                        id = "studio-col-${col.id}",
                        name = cleanName,
                        kind = ChannelKind.COLLECTION,
                        blurb = col.summary?.takeIf { it.isNotBlank() }
                            ?: if (col.mediaType == "movie") "${playable.size} films, in order"
                            else "${col.members.size} curated shows",
                        pool = playable,
                        ordered = col.mediaType == "movie",
                    )
                }
            }
        }
    }

    // 8. Your playlists (up to 8)
    playlists.forEach { pl ->
        val playable = pl.tracks.filter { it.trackId != null }.map { t ->
            val art = api.artwork.albumArtwork(t.albumId)
            PoolItem(
                key = "track:${t.trackId}",
                itemType = "track",
                itemId = t.trackId.toString(),
                detailId = t.albumId,
                title = t.title,
                subtitle = t.artist,
                seconds = ((t.durationMs ?: 210_000L) / 1000L).toInt(),
                posterUrl = art,
                backdropUrl = art,
            )
        }
        addChannel(
            RawChannel(
                id = "playlist-${pl.id}",
                name = pl.name,
                kind = ChannelKind.PLAYLIST,
                blurb = "your playlist · ${playable.size} songs",
                pool = playable,
                isMusic = true,
            ),
        )
    }

    // 9. Server Music Channels (Vibes & Music Genres)
    musicChannels.forEach { mc ->
        val pool = mc.trackItems.map { musicChannelTrackItem(it, api) }
        val isVibe = mc.kind.equals("vibe", ignoreCase = true)
        addChannel(
            RawChannel(
                id = mc.id,
                name = mc.name,
                kind = if (isVibe) ChannelKind.VIBE else ChannelKind.MUSIC,
                blurb = "${mc.count} songs" + if (isVibe) " · by mood" else "",
                pool = pool,
                isMusic = true,
            ),
        )
    }

    // 10. Server & Personal Saved Channels (`made-<id>`)
    savedChannels.forEach { saved ->
        val spec = saved.spec
        val pool = buildSpecPool(spec, catalog, episodesByShow, nowMs, api)
        val blurb = buildSpecBlurb(spec, shows.firstOrNull { it.id == spec.show }?.title)
        addChannel(
            RawChannel(
                id = "made-${saved.id}",
                name = spec.name.ifBlank { "Custom Channel" },
                kind = if (saved.scope == "server") ChannelKind.SERVER else ChannelKind.RULE,
                blurb = blurb,
                pool = pool,
                ordered = spec.order == "in_order" || spec.show != null,
            ),
        )
    }

    // 11. Apply Profile Lineup (order, renames, hidden) + Desktop GROUP order + Channel numbering from 101
    val lineupPos = lineup.withIndex().associate { it.value.channelId to it.index }
    val lineupById = lineup.associateBy { it.channelId }

    // two names can make one id ("Disney+" and "Disney" are both network-disney):
    // the first keeps it, the same rule as the web guide, so numbers still match.
    // a second one with the same key crashed the guide's LazyColumn
    val lined = raw.distinctBy { it.id }.withIndex()
        .sortedWith { a, b ->
            val pa = lineupPos[a.value.id]
            val pb = lineupPos[b.value.id]
            when {
                pa != null && pb != null -> pa - pb
                pa != null -> -1
                pb != null -> 1
                else -> a.index - b.index
            }
        }
        .mapNotNull { (_, c) ->
            val entry = lineupById[c.id]
            if (entry?.hidden == true) return@mapNotNull null
            c.copy(name = entry?.name?.takeIf { it.isNotBlank() } ?: c.name)
        }

    val premiereSlots = premieresByChannel(premieres, raw.distinctBy { it.id }, api)

    val numbered = lined.withIndex()
        .sortedWith(compareBy<IndexedValue<RawChannel>> { it.value.kind.ordinal }.thenBy { it.index })
        .mapIndexed { idx, (_, c) ->
            val num = 101 + idx
            val mosaic = c.pool.mapNotNull { it.posterUrl }.distinct().take(4)
            val progs = if (c.isMusic) {
                scheduleMusicBlocks(c.id, c.name, c.blurb, c.pool, windowStartSec, windowEndSec)
            } else {
                scheduleVideoPrograms(
                    c.id, c.pool, c.ordered, windowStartSec, windowEndSec, commercialsConfig,
                    premieres = if (c.ordered) emptyList() else premiereSlots[c.id].orEmpty(),
                )
            }
            EpgChannel(
                id = c.id,
                number = num,
                name = c.name,
                kind = c.kind,
                blurb = c.blurb,
                isMusic = c.isMusic,
                mosaicArt = mosaic,
                programs = progs,
                musicPool = if (c.isMusic) c.pool else emptyList(),
            )
        }

    return numbered
}

private fun sameWord(list: List<String>, value: String?): Boolean {
    if (list.isEmpty()) return true
    if (value.isNullOrBlank()) return false
    return list.any { it.equals(value, ignoreCase = true) }
}

private fun buildSpecPool(
    spec: GuideChannelSpec,
    catalog: VideoCatalog,
    episodesByShow: Map<Int, List<PoolItem>>,
    nowMs: Long,
    api: PlayerApiClient,
): List<PoolItem> {
    if (spec.show != null) {
        return episodesByShow[spec.show].orEmpty()
    }
    val out = mutableListOf<PoolItem>()
    val addedCutoff = spec.addedDays?.let { nowMs - it.toLong() * 86_400_000L }
    val minScore = spec.minScore

    if ("movies" in spec.kinds) {
        out += catalog.movies.filter { m ->
            (spec.genres.isEmpty() || m.genres.any { sameWord(spec.genres, it) }) &&
                (spec.decades.isEmpty() || decadeOf(m.year) in spec.decades) &&
                (!spec.kids || isKidSafe(m.contentRating)) &&
                (minScore == null || (m.rating ?: 0.0) >= minScore) &&
                (addedCutoff == null || (parseIsoEpochMs(m.addedAt) ?: 0L) >= addedCutoff) &&
                !(spec.networks.isNotEmpty() && spec.studios.isEmpty()) &&
                sameWord(spec.studios, m.studio) &&
                (spec.collection == null || m.collection == spec.collection)
        }.map { moviePoolItem(it, api) }
    }
    if ("tv" in spec.kinds) {
        out += catalog.shows.filter { s ->
            (spec.genres.isEmpty() || s.genres.any { sameWord(spec.genres, it) }) &&
                (spec.decades.isEmpty() || decadeOf(s.year) in spec.decades) &&
                (!spec.kids || isKidSafe(s.contentRating)) &&
                (minScore == null || (s.rating ?: 0.0) >= minScore) &&
                (addedCutoff == null || (parseIsoEpochMs(s.addedAt) ?: 0L) >= addedCutoff) &&
                !(spec.studios.isNotEmpty() && spec.networks.isEmpty()) &&
                sameWord(spec.networks, s.network) &&
                spec.collection == null
        }.flatMap { episodesByShow[it.id].orEmpty() }
    }
    if ("youtube" in spec.kinds && spec.people.isEmpty()) {
        out += catalog.youtube.filter { y ->
            sameWord(spec.youtubeChannels, y.channelTitle) || sameWord(spec.youtubeChannels, y.channelId)
        }.map { youtubePoolItem(it, api) }
    }
    return out
}

private fun buildSpecBlurb(spec: GuideChannelSpec, showTitle: String?): String {
    if (spec.show != null) return "${showTitle ?: "one show"}, start to finish"
    return listOfNotNull(
        spec.people.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
        spec.kinds.joinToString(" + ") {
            when (it) {
                "tv" -> "TV"
                "youtube" -> "YouTube"
                else -> "movies"
            }
        },
        spec.genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", "),
        spec.decades.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it}s" },
        (spec.studios + spec.networks + spec.youtubeChannels).take(2).takeIf { it.isNotEmpty() }?.joinToString(", "),
        spec.collection,
        spec.minScore?.let { "★ $it+" },
        spec.addedDays?.let { "added in $it days" },
        if (spec.kids) "kids" else null,
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

// ── Home Live TV Snapshot Helper (exact same deterministic schedule as Guide) ──

/** a premiere still to come (or on now), for Home's "Premieres tonight" row */
internal data class HomePremiere(
    val key: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
)

/** "Tonight 8:00 PM", "On now", "Tomorrow 9:00 PM" (the web player's premiereWhen) */
internal fun premiereWhen(atMs: Long, nowMs: Long): String {
    if (atMs <= nowMs) return "On now"
    val at = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(atMs))
    val sameDay = at.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        at.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay && at.get(java.util.Calendar.HOUR_OF_DAY) >= 17 -> "Tonight $time"
        sameDay -> "Today $time"
        else -> "Tomorrow $time"
    }
}

/**
 * Asks the server what's new and when it airs, keeps it for every channel
 * build (GuideWarmCache), and returns the ones on now or airing in the next
 * day with the channel each airs on. Call before building Home's live row so
 * that row airs them too.
 */
internal suspend fun loadHomePremieres(
    catalog: VideoCatalog,
    studioCollections: List<StudioCollection>,
    api: PlayerApiClient,
    nowMs: Long = System.currentTimeMillis(),
): List<HomePremiere> {
    val list = (api.fetchGuidePremieres() as? MusicApiResult.Success)?.data ?: return emptyList()
    GuideWarmCache.baseUrl = api.baseUrl
    GuideWarmCache.premieres = list
    if (list.isEmpty() || (catalog.movies.isEmpty() && catalog.shows.isEmpty())) return emptyList()
    val nowSec = nowMs / 1000L
    val channels = buildDesktopParityChannels(
        catalog = catalog,
        musicPoolSongs = emptyList(),
        playlists = emptyList(),
        musicChannels = emptyList(),
        savedChannels = emptyList(),
        lineup = emptyList(),
        studioCollections = studioCollections,
        api = api,
        windowStartSec = nowSec,
        windowEndSec = nowSec + 60L,
        premieres = emptyList(),
    ).filter { !it.isMusic }.associateBy { it.id }
    return list
        .filter { it.seconds > 0 && it.at + it.seconds * 1000L > nowMs && it.at <= nowMs + 24L * 3600_000L }
        .sortedBy { it.at }
        .mapNotNull { p ->
            val ch = p.homes.firstNotNullOfOrNull { channels[it] } ?: return@mapNotNull null
            val show = p.kind == "episode"
            val owner = if (show) (p.showId ?: p.id) else p.id
            HomePremiere(
                key = p.key,
                title = if (show && !p.subtitle.isNullOrBlank()) "${p.title} · ${p.subtitle}" else p.title,
                subtitle = "${premiereWhen(p.at, nowMs)} · CH ${ch.number} ${ch.name}",
                artworkUrl = when {
                    !p.hasPoster -> null
                    show -> api.artwork.showPoster(owner)
                    else -> api.artwork.moviePoster(owner)
                },
            )
        }
}

internal data class HomeLiveHighlight(
    val channelId: String,
    val channelNumber: Int,
    val channelName: String,
    val programTitle: String,
    val programSubtitle: String,
    val artworkUrl: String?,
    val progressFraction: Float,
    val tune: (PlaybackCoordinator) -> Unit,
)

internal fun buildHomeLiveGuideHighlights(
    catalog: VideoCatalog,
    studioCollections: List<StudioCollection>,
    api: PlayerApiClient,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): List<HomeLiveHighlight> {
    if (catalog.movies.isEmpty() && catalog.shows.isEmpty()) return emptyList()
    val windowStart = nowSec - 1800L
    val windowEnd = nowSec + 4L * 3600L
    val channels = buildDesktopParityChannels(
        catalog = catalog,
        musicPoolSongs = emptyList(),
        playlists = emptyList(),
        musicChannels = emptyList(),
        savedChannels = emptyList(),
        lineup = emptyList(),
        studioCollections = studioCollections,
        api = api,
        windowStartSec = windowStart,
        windowEndSec = windowEnd,
        commercialsConfig = if (GuideWarmCache.baseUrl == api.baseUrl) GuideWarmCache.commercialsConfig else null,
    ).filter { !it.isMusic && it.programs.isNotEmpty() }

    val preferred = listOf("type-movies", "type-tv", "pick-top", "pick-comfort", "pick-new", "pick-late")
    val ordered = buildList {
        preferred.forEach { pid -> channels.firstOrNull { it.id == pid }?.let { add(it) } }
        channels.filter { it.id !in preferred }.take(12).forEach { add(it) }
    }.take(12)

    fun tuneEpg(ch: EpgChannel, coordinator: PlaybackCoordinator) {
        val currentSec = System.currentTimeMillis() / 1000L
        val active = ch.programs.firstOrNull { currentSec in it.startEpochSec until it.endEpochSec }
            ?: ch.programs.firstOrNull() ?: return
        val offsetMs = if (currentSec in active.startEpochSec until active.endEpochSec) {
            (currentSec - active.startEpochSec) * 1000L
        } else {
            0L
        }
        val durMs = ((active.endEpochSec - active.startEpochSec).coerceAtLeast(0L)) * 1000L
        val upcoming = ch.programs.filter { it.startEpochSec >= active.endEpochSec }.take(10).map { p ->
            ActivePlayable(
                itemType = p.itemType,
                itemId = p.itemId,
                title = p.title,
                subtitle = "CH ${ch.number} · ${ch.name}",
                artworkUrl = p.artworkUrl,
                streamUrl = api.streamUrl(p.itemType, p.itemId),
                isVideo = true,
                resumePositionMs = 0L,
                durationMs = ((p.endEpochSec - p.startEpochSec).coerceAtLeast(0L)) * 1000L,
                isLiveGuide = true,
                channelId = ch.id,
                channelName = ch.name,
                channelNumber = ch.number,
            )
        }
        coordinator.liveChannelSurfer = { curId, delta ->
            val idx = ordered.indexOfFirst { it.id == curId }
            if (idx >= 0 && ordered.size > 1) {
                val nextIdx = (idx + delta + ordered.size) % ordered.size
                tuneEpg(ordered[nextIdx], coordinator)
            }
        }
        coordinator.liveChannelRefiller = { chId ->
            val now = System.currentTimeMillis() / 1000L
            val fresh = buildDesktopParityChannels(
                catalog = catalog,
                musicPoolSongs = emptyList(),
                playlists = emptyList(),
                musicChannels = emptyList(),
                savedChannels = emptyList(),
                lineup = emptyList(),
                studioCollections = studioCollections,
                api = api,
                windowStartSec = now,
                windowEndSec = now + 8L * 3600L,
                commercialsConfig = if (GuideWarmCache.baseUrl == api.baseUrl) GuideWarmCache.commercialsConfig else null,
            ).firstOrNull { it.id == chId }
            fresh?.programs.orEmpty()
                .filter { it.endEpochSec > now + 5L }
                .take(12)
                .map { p ->
                    ActivePlayable(
                        itemType = p.itemType,
                        itemId = p.itemId,
                        title = p.title,
                        subtitle = "CH ${fresh!!.number} · ${fresh.name}",
                        artworkUrl = p.artworkUrl,
                        streamUrl = api.streamUrl(p.itemType, p.itemId),
                        isVideo = true,
                        resumePositionMs = 0L,
                        durationMs = ((p.endEpochSec - p.startEpochSec).coerceAtLeast(0L)) * 1000L,
                        isLiveGuide = true,
                        channelId = fresh.id,
                        channelName = fresh.name,
                        channelNumber = fresh.number,
                    )
                }
        }
        coordinator.playVideo(
            ActivePlayable(
                itemType = active.itemType,
                itemId = active.itemId,
                title = active.title,
                subtitle = "CH ${ch.number} · ${ch.name}",
                artworkUrl = active.artworkUrl,
                streamUrl = api.streamUrl(active.itemType, active.itemId),
                isVideo = true,
                resumePositionMs = offsetMs,
                durationMs = durMs,
                isLiveGuide = true,
                channelId = ch.id,
                channelName = ch.name,
                channelNumber = ch.number,
            ),
            upcoming = upcoming,
        )
    }

    return ordered.mapNotNull { ch ->
        val airing = ch.programs.firstOrNull { nowSec in it.startEpochSec until it.endEpochSec }
            ?: ch.programs.firstOrNull()
            ?: return@mapNotNull null
        val total = (airing.endEpochSec - airing.startEpochSec).coerceAtLeast(1L)
        val elapsed = (nowSec - airing.startEpochSec).coerceIn(0L, total)
        HomeLiveHighlight(
            channelId = ch.id,
            channelNumber = ch.number,
            channelName = ch.name,
            programTitle = airing.title,
            programSubtitle = "CH ${ch.number} · ${ch.name}",
            artworkUrl = airing.artworkUrl ?: ch.mosaicArt.firstOrNull(),
            progressFraction = (elapsed.toFloat() / total.toFloat()).coerceIn(0.03f, 0.99f),
            tune = { coord -> tuneEpg(ch, coord) },
        )
    }
}

// ── Guide Warm Cache (0ms repeat opens + instant seeding from HomeWarmCache) ──

private object GuideWarmCache {
    var baseUrl: String? = null
    var catalog: VideoCatalog = VideoCatalog()
    var musicPoolSongs: List<LibSong> = emptyList()
    var playlistDetails: List<PlaylistDetail> = emptyList()
    var musicChannels: List<MusicChannel> = emptyList()
    var savedChannels: List<SavedGuideChannel> = emptyList()
    var lineup: List<GuideLineupEntry> = emptyList()
    var studioCollections: List<StudioCollection> = emptyList()
    var favoritesList: List<String> = emptyList()
    var commercialsConfig: CommercialsConfig? = null
    var premieres: List<GuidePremiere> = emptyList()
    var allChannels: List<EpgChannel> = emptyList()
    var windowStartSec: Long = 0L
    var builtAtSec: Long = 0L
    var hasLoadedOnce: Boolean = false
}

internal fun seedGuideWarmMeta(
    baseUrl: String,
    favorites: List<String>? = null,
    saved: List<SavedGuideChannel>? = null,
) {
    if (GuideWarmCache.baseUrl != baseUrl) {
        GuideWarmCache.baseUrl = baseUrl
    }
    if (favorites != null) GuideWarmCache.favoritesList = favorites
    if (saved != null) GuideWarmCache.savedChannels = saved
}

// ── Guide Screen Composable ──────────────────────────────────────────────────

@Composable
fun GuideScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    var offsetHours by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            nowSec = System.currentTimeMillis() / 1000L
        }
    }
    val windowMinutes = 240
    val windowStartSec = ((nowSec - 1800L) / 1800L) * 1800L + offsetHours * 3600L
    val windowEndSec = windowStartSec + windowMinutes * 60L
    val nowVisible = nowSec in windowStartSec until windowEndSec
    val nowOffsetMin = ((nowSec - windowStartSec).coerceAtLeast(0L) / 60f)

    val warmHit = GuideWarmCache.hasLoadedOnce &&
        GuideWarmCache.baseUrl == api.baseUrl &&
        GuideWarmCache.allChannels.isNotEmpty() &&
        GuideWarmCache.windowStartSec == windowStartSec
    val homeSeedHit = !warmHit && HomeWarmCache.hasLoadedOnce && HomeWarmCache.baseUrl == api.baseUrl &&
        (HomeWarmCache.catalog.movies.isNotEmpty() || HomeWarmCache.catalog.shows.isNotEmpty())

    var catalog by remember(api.baseUrl) {
        mutableStateOf(
            when {
                warmHit -> GuideWarmCache.catalog
                homeSeedHit -> HomeWarmCache.catalog
                else -> VideoCatalog()
            },
        )
    }
    var musicPoolSongs by remember(api.baseUrl) { mutableStateOf(if (warmHit) GuideWarmCache.musicPoolSongs else emptyList()) }
    var playlistDetails by remember(api.baseUrl) { mutableStateOf(if (warmHit) GuideWarmCache.playlistDetails else emptyList()) }
    var musicChannels by remember(api.baseUrl) { mutableStateOf(if (warmHit) GuideWarmCache.musicChannels else emptyList()) }
    val cacheSameHost = GuideWarmCache.baseUrl == api.baseUrl
    var savedChannels by remember(api.baseUrl) { mutableStateOf(if (cacheSameHost) GuideWarmCache.savedChannels else emptyList()) }
    var lineup by remember(api.baseUrl) { mutableStateOf(if (cacheSameHost) GuideWarmCache.lineup else emptyList()) }
    var studioCollections by remember(api.baseUrl) {
        mutableStateOf(
            when {
                warmHit -> GuideWarmCache.studioCollections
                homeSeedHit -> HomeWarmCache.studioCollections
                else -> emptyList()
            },
        )
    }
    var favoritesList by remember(api.baseUrl) { mutableStateOf(if (cacheSameHost) GuideWarmCache.favoritesList else emptyList()) }
    var commercialsConfig by remember(api.baseUrl) { mutableStateOf(if (cacheSameHost) GuideWarmCache.commercialsConfig else null) }
    val favorites = remember(favoritesList) { favoritesList.toSet() }
    var viewMode by remember { mutableStateOf(GuideViewMode.GRID) }
    var filter by remember { mutableStateOf(GuideFilter.ALL) }
    var channelQuery by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<EpgChannel, EpgProgram>?>(null) }
    var isLoading by remember(api.baseUrl) { mutableStateOf(!warmHit) }
    var allChannels by remember(api.baseUrl) { mutableStateOf(if (warmHit) GuideWarmCache.allChannels else emptyList()) }
    var liveSessions by remember { mutableStateOf<List<WatchingSession>>(emptyList()) }
    var showMakeChannelDialog by remember { mutableStateOf(false) }
    val viewersByChannel = remember(liveSessions) {
        liveSessions
            .filter { it.channel != null && it.channel!!.id.isNotBlank() }
            .groupBy { it.channel!!.id }
    }

    val onScreen = rememberOnScreen()
    LaunchedEffect(api) {
        while (true) {
            if (onScreen.value) (api.fetchPresence() as? MusicApiResult.Success)?.let {
                liveSessions = it.data.sessions
            }
            delay(8_000L)
        }
    }

    fun saveGuideWarmCache(channels: List<EpgChannel>) {
        GuideWarmCache.baseUrl = api.baseUrl
        GuideWarmCache.catalog = catalog
        GuideWarmCache.musicPoolSongs = musicPoolSongs
        GuideWarmCache.playlistDetails = playlistDetails
        GuideWarmCache.musicChannels = musicChannels
        GuideWarmCache.savedChannels = savedChannels
        GuideWarmCache.lineup = lineup
        GuideWarmCache.studioCollections = studioCollections
        GuideWarmCache.favoritesList = favoritesList
        GuideWarmCache.commercialsConfig = commercialsConfig
        GuideWarmCache.allChannels = channels
        GuideWarmCache.windowStartSec = windowStartSec
        GuideWarmCache.builtAtSec = nowSec
        GuideWarmCache.hasLoadedOnce = channels.isNotEmpty()
    }

    // Rebuild channel program slices whenever the user browses Earlier/Later or a new 30-min boundary ticks
    LaunchedEffect(windowStartSec) {
        if (catalog.movies.isNotEmpty() || catalog.shows.isNotEmpty()) {
            // each half hour: what landed since airs tonight. the server gives a
            // premiere its slot at least half an hour ahead, so this is in time
            (api.fetchGuidePremieres() as? MusicApiResult.Success)?.let {
                GuideWarmCache.baseUrl = api.baseUrl
                GuideWarmCache.premieres = it.data
            }
            val rebuilt = withContext(Dispatchers.Default) {
                buildDesktopParityChannels(
                    catalog = catalog,
                    musicPoolSongs = musicPoolSongs,
                    playlists = playlistDetails,
                    musicChannels = musicChannels,
                    savedChannels = savedChannels,
                    lineup = lineup,
                    studioCollections = studioCollections,
                    api = api,
                    windowStartSec = windowStartSec,
                    windowEndSec = windowEndSec,
                    commercialsConfig = commercialsConfig,
                )
            }
            if (rebuilt.isNotEmpty()) {
                allChannels = rebuilt
                saveGuideWarmCache(rebuilt)
                isLoading = false
            }
        }
    }

    LaunchedEffect(api) {
        // Instant paint (<15ms) when HomeWarmCache already has the catalog: build base channels
        // immediately on Dispatchers.Default so the Guide opens with ZERO network wait!
        if (!warmHit && homeSeedHit) {
            val seedChannels = withContext(Dispatchers.Default) {
                buildDesktopParityChannels(
                    catalog = catalog,
                    musicPoolSongs = musicPoolSongs,
                    playlists = playlistDetails,
                    musicChannels = musicChannels,
                    savedChannels = savedChannels,
                    lineup = lineup,
                    studioCollections = studioCollections,
                    api = api,
                    windowStartSec = windowStartSec,
                    windowEndSec = windowEndSec,
                    commercialsConfig = commercialsConfig,
                )
            }
            if (seedChannels.isNotEmpty()) {
                allChannels = seedChannels
                saveGuideWarmCache(seedChannels)
                isLoading = false
            }
        } else if (!warmHit) {
            isLoading = true
        }

        // Phase 1: fetch catalog (cached 5m in PlayerApiClient), saved channels, lineup, favorites, collections, shared commercials config
        val revoked = coroutineScope {
            // the day's frozen pool, the same one the web guide schedules from
            val vDef = async { api.fetchGuideCatalog() }
            val sDef = async { api.fetchSavedGuideChannels() }
            val lDef = async { api.fetchGuideLineup() }
            val fDef = async { api.fetchGuideFavoritesList() }
            val cDef = async { api.fetchCollections() }
            val commDef = async { api.fetchCommercialsConfig(forGuide = true) }
            val premDef = async { api.fetchGuidePremieres() }

            val v = vDef.await()
            val s = sDef.await()
            val l = lDef.await()
            val f = fDef.await()
            val c = cDef.await()
            val comm = commDef.await()

            if (v is MusicApiResult.DeviceRevoked || s is MusicApiResult.DeviceRevoked) {
                return@coroutineScope true
            }
            (v as? MusicApiResult.Success)?.let { catalog = it.data }
            (s as? MusicApiResult.Success)?.let { savedChannels = it.data }
            (l as? MusicApiResult.Success)?.let { lineup = it.data }
            (f as? MusicApiResult.Success)?.let { favoritesList = it.data }
            (c as? MusicApiResult.Success)?.let { studioCollections = it.data }
            (comm as? MusicApiResult.Success)?.let { commercialsConfig = it.data }
            // read by every channel build from here on (buildDesktopParityChannels)
            (premDef.await() as? MusicApiResult.Success)?.let {
                GuideWarmCache.baseUrl = api.baseUrl
                GuideWarmCache.premieres = it.data
            }
            false
        }
        if (revoked) {
            onDeviceRevoked()
            return@LaunchedEffect
        }
        val phase1Channels = withContext(Dispatchers.Default) {
            buildDesktopParityChannels(
                catalog = catalog,
                musicPoolSongs = musicPoolSongs,
                playlists = playlistDetails,
                musicChannels = musicChannels,
                savedChannels = savedChannels,
                lineup = lineup,
                studioCollections = studioCollections,
                api = api,
                windowStartSec = windowStartSec,
                windowEndSec = windowEndSec,
                commercialsConfig = commercialsConfig,
            )
        }
        allChannels = phase1Channels
        saveGuideWarmCache(phase1Channels)
        isLoading = false

        // Phase 2 (progressive, non-blocking): stream in music channels, songs & playlists
        // in parallel, then refresh once when ready so we don't rebuild 4 times.
        val phase2 = coroutineScope {
            val mcDef = async { (api.fetchMusicChannels() as? MusicApiResult.Success)?.data }
            val songsDef = async { (api.fetchSongs(limit = 150, page = 1) as? MusicApiResult.Success)?.data }
            val plsDef = async {
                val pls = api.fetchPlaylists()
                val candidatePlaylists = (pls as? MusicApiResult.Success)?.data
                    ?.filter { it.kind == "yours" && it.trackCount > 2 }
                    ?.take(8)
                    .orEmpty()
                if (candidatePlaylists.isNotEmpty()) {
                    coroutineScope {
                        candidatePlaylists.mapNotNull { p ->
                            p.idString.toIntOrNull()?.let { pid ->
                                async { (api.fetchPlaylistDetail(pid) as? MusicApiResult.Success)?.data }
                            }
                        }.awaitAll().filterNotNull()
                    }
                } else {
                    emptyList()
                }
            }
            Triple(mcDef.await(), songsDef.await(), plsDef.await())
        }
        phase2.first?.let { musicChannels = it }
        phase2.second?.let { musicPoolSongs = it }
        if (phase2.third.isNotEmpty()) playlistDetails = phase2.third
        val phase2Channels = withContext(Dispatchers.Default) {
            buildDesktopParityChannels(
                catalog = catalog,
                musicPoolSongs = musicPoolSongs,
                playlists = playlistDetails,
                musicChannels = musicChannels,
                savedChannels = savedChannels,
                lineup = lineup,
                studioCollections = studioCollections,
                api = api,
                windowStartSec = windowStartSec,
                windowEndSec = windowEndSec,
                commercialsConfig = commercialsConfig,
            )
        }
        allChannels = phase2Channels
        saveGuideWarmCache(phase2Channels)
    }

    if (isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingBox()
            Spacer(modifier = Modifier.height(10.dp))
            Text("Tuning channels…", color = SoulSyncColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        return
    }

    val groupedSections = remember(allChannels, filter, favoritesList, favorites, channelQuery) {
        val q = channelQuery.trim().lowercase()
        fun matchesQuery(ch: EpgChannel): Boolean {
            if (q.isEmpty()) return true
            if (q.all { it.isDigit() }) return ch.number.toString().startsWith(q)
            return ch.name.lowercase().contains(q) ||
                ch.blurb.lowercase().contains(q) ||
                ch.programs.any { it.title.lowercase().contains(q) }
        }
        fun matchesFilter(ch: EpgChannel): Boolean = when (filter) {
            GuideFilter.ALL -> true
            GuideFilter.FAVORITES -> ch.id in favorites
            GuideFilter.VIDEO -> !ch.isMusic
            GuideFilter.MUSIC -> ch.isMusic
            GuideFilter.KIDS -> ch.kind == ChannelKind.KIDS || (ch.kind == ChannelKind.RULE && ch.blurb.contains("kids", ignoreCase = true))
        }

        val shown = allChannels.filter { matchesFilter(it) && matchesQuery(it) }
        val byId = allChannels.associateBy { it.id }
        val sections = mutableListOf<ChannelGroupSection>()

        if (filter == GuideFilter.ALL && favoritesList.isNotEmpty()) {
            val favChs = favoritesList.mapNotNull { byId[it] }.filter { matchesQuery(it) }
            if (favChs.isNotEmpty()) {
                sections += ChannelGroupSection("favorites", "Favorites", favChs)
            }
        }
        for (kind in ChannelKind.entries) {
            val chs = shown.filter { it.kind == kind }
            if (chs.isNotEmpty()) {
                sections += ChannelGroupSection(kind.name, kind.groupLabel, chs)
            }
        }
        sections
    }

    val shownCount = remember(groupedSections) {
        groupedSections.filterNot { it.key == "favorites" }.sumOf { it.channels.size }
    }

    fun tuneInChannel(ch: EpgChannel, prog: EpgProgram? = null) {
        val videoChannels = allChannels.filter { !it.isMusic && it.programs.isNotEmpty() }
        if (videoChannels.size > 1) {
            coordinator.liveChannelSurfer = { curId, delta ->
                val idx = videoChannels.indexOfFirst { it.id == curId }
                if (idx >= 0) {
                    val nextIdx = (idx + delta + videoChannels.size) % videoChannels.size
                    tuneInChannel(videoChannels[nextIdx])
                }
            }
        }
        coordinator.liveChannelRefiller = { chId ->
            val now = System.currentTimeMillis() / 1000L
            val fresh = buildDesktopParityChannels(
                catalog = catalog,
                musicPoolSongs = musicPoolSongs,
                playlists = playlistDetails,
                musicChannels = musicChannels,
                savedChannels = savedChannels,
                lineup = lineup,
                studioCollections = studioCollections,
                api = api,
                windowStartSec = now,
                windowEndSec = now + 8L * 3600L,
                commercialsConfig = commercialsConfig,
            ).firstOrNull { it.id == chId && !it.isMusic }
            fresh?.programs.orEmpty()
                .filter { it.endEpochSec > now + 5L }
                .take(12)
                .map { p ->
                    ActivePlayable(
                        itemType = p.itemType,
                        itemId = p.itemId,
                        title = p.title,
                        subtitle = if (p.itemType == "commercial") "Commercial Break · CH ${fresh!!.number} ${fresh.name}" else "CH ${fresh!!.number} · ${fresh.name}",
                        artworkUrl = p.artworkUrl,
                        streamUrl = api.streamUrl(p.itemType, p.itemId),
                        isVideo = true,
                        resumePositionMs = 0L,
                        durationMs = ((p.endEpochSec - p.startEpochSec).coerceAtLeast(0L)) * 1000L,
                        isLiveGuide = true,
                        channelId = fresh.id,
                        channelName = fresh.name,
                        channelNumber = fresh.number,
                    )
                }
        }
        val active = prog ?: ch.programs.firstOrNull { nowSec in it.startEpochSec until it.endEpochSec }
            ?: ch.programs.firstOrNull() ?: return
        if (ch.isMusic && ch.musicPool.isNotEmpty()) {
            val startIdx = ch.musicPool.indexOfFirst { it.itemId == active.itemId }.coerceAtLeast(0)
            val ordered = ch.musicPool.drop(startIdx) + ch.musicPool.take(startIdx)
            val queue = ordered.take(60).map { t ->
                ActivePlayable(
                    itemType = "track",
                    itemId = t.itemId,
                    title = t.title,
                    subtitle = "${t.subtitle} · CH ${ch.number} ${ch.name}",
                    artworkUrl = t.posterUrl,
                    streamUrl = api.streamUrl("track", t.itemId),
                    isLiveGuide = true,
                    channelId = ch.id,
                    channelName = ch.name,
                    channelNumber = ch.number,
                )
            }
            if (queue.isNotEmpty()) coordinator.playAudio(queue.first(), queue.drop(1))
        } else {
            val offsetMs = if (nowSec in active.startEpochSec until active.endEpochSec) {
                (nowSec - active.startEpochSec) * 1000L
            } else {
                0L
            }
            val progDurMs = ((active.endEpochSec - active.startEpochSec).coerceAtLeast(0L)) * 1000L
            val baseUpcoming = ch.programs
                .filter { it.startEpochSec >= active.endEpochSec }
                .take(18)
                .map { p ->
                    ActivePlayable(
                        itemType = p.itemType,
                        itemId = p.itemId,
                        title = p.title,
                        subtitle = if (p.itemType == "commercial") "Commercial Break · CH ${ch.number} ${ch.name}" else "CH ${ch.number} · ${ch.name}",
                        artworkUrl = p.artworkUrl,
                        streamUrl = api.streamUrl(p.itemType, p.itemId),
                        isVideo = true,
                        resumePositionMs = 0L,
                        durationMs = ((p.endEpochSec - p.startEpochSec).coerceAtLeast(0L)) * 1000L,
                        isLiveGuide = true,
                        channelId = ch.id,
                        channelName = ch.name,
                        channelNumber = ch.number,
                    )
                }
            val primaryPlayable = ActivePlayable(
                itemType = active.itemType,
                itemId = active.itemId,
                title = active.title,
                subtitle = if (active.itemType == "commercial") "Commercial Break · CH ${ch.number} ${ch.name}" else "CH ${ch.number} · ${ch.name}",
                artworkUrl = active.artworkUrl,
                streamUrl = api.streamUrl(active.itemType, active.itemId),
                isVideo = true,
                resumePositionMs = offsetMs,
                durationMs = progDurMs,
                isLiveGuide = true,
                channelId = ch.id,
                channelName = ch.name,
                channelNumber = ch.number,
            )
            // Retro commercials are natively scheduled on the shared deterministic broadcast clock in ch.programs
            coordinator.playVideo(primaryPlayable, upcoming = baseUpcoming)
        }
    }

    val hScroll = rememberScrollState()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val isTv = LocalIsTv.current
    var showTvFilterDialog by remember { mutableStateOf(false) }
    val firstChannelFocus = remember { FocusRequester() }
    val tuneInFocus = remember { FocusRequester() }
    val firstChannelId = groupedSections.firstOrNull()?.channels?.firstOrNull()?.id

    LaunchedEffect(isTv, firstChannelId) {
        if (isTv && firstChannelId != null && picked == null) {
            delay(120L)
            runCatching { firstChannelFocus.requestFocus() }
        }
    }
    LaunchedEffect(picked, isTv) {
        if (isTv && picked != null) {
            delay(80L)
            runCatching { tuneInFocus.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header + Grid / On now switcher + Desktop-parity filter pills
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Guide", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text("$shownCount of ${allChannels.size} channels", color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isTv) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (channelQuery.isNotBlank()) SoulSyncColors.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (channelQuery.isNotBlank()) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                                .dpadFocusable(CircleShape)
                                .clickable { showTvFilterDialog = true }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = if (channelQuery.isNotBlank()) "Filter: $channelQuery" else "Filter",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
                            .border(1.dp, SoulSyncColors.AccentLight.copy(alpha = 0.65f), CircleShape)
                            .dpadFocusable(CircleShape)
                            .clickable { showMakeChannelDialog = true }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "+ Make Channel",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f))
                            .border(1.dp, SoulSyncColors.Line, CircleShape)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        GuideViewMode.entries.forEach { mode ->
                            val on = viewMode == mode
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.Transparent)
                                    .dpadFocusable(CircleShape)
                                    .clickable { viewMode = mode }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (mode == GuideViewMode.GRID) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = mode.label,
                                    tint = if (on) Color.White else SoulSyncColors.TextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = mode.label,
                                    color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(GuideFilter.entries, key = { it.name }) { f ->
                    val on = filter == f
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (on) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line, CircleShape)
                            .dpadFocusable(CircleShape)
                            .clickable { filter = f }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(f.label, color = if (on) Color.White else SoulSyncColors.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Never place a bare OutlinedTextField in the TV vertical D-pad path (auto-pops soft keyboard and traps navigation)
            if (!isTv) {
                OutlinedTextField(
                    value = channelQuery,
                    onValueChange = { channelQuery = it },
                    placeholder = { Text("Filter ${allChannels.size} channels or shows…", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                )
            }
        }

        if (showTvFilterDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTvFilterDialog = false },
                containerColor = SoulSyncColors.BgSecondary,
                title = { Text("Filter channels or shows", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = channelQuery,
                        onValueChange = { channelQuery = it },
                        placeholder = { Text("Type channel or show name…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showTvFilterDialog = false },
                        modifier = Modifier.dpadFocusable(CircleShape),
                    ) { Text("Done") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            channelQuery = ""
                            showTvFilterDialog = false
                        },
                        modifier = Modifier.dpadFocusable(CircleShape),
                    ) { Text("Clear") }
                },
            )
        }

        // Top Picked-Program Preview Panel
        val selected = picked
        if (selected != null) {
            val (ch, prog) = selected
            val isLive = nowSec in prog.startEpochSec until prog.endEpochSec
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(148.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoulSyncColors.CardRaised)
                    .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
            ) {
                SoulSyncArtwork(url = prog.artworkUrl, contentDescription = prog.title, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.94f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.72f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.35f),
                                ),
                            ),
                        ),
                )
                IconButton(
                    onClick = { picked = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(30.dp).clip(CircleShape).dpadFocusable(CircleShape),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${if (isLive) "● ON NOW · " else ""}CH ${ch.number} ${ch.name} · ${timeFmt.format(Date(prog.startEpochSec * 1000L))}–${timeFmt.format(Date(prog.endEpochSec * 1000L))}",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                        Text(
                            text = prog.title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (prog.subtitle.isNotBlank()) {
                            Text(
                                text = prog.subtitle,
                                color = SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { tuneInChannel(ch, prog) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .focusRequester(tuneInFocus)
                                .dpadFocusable(CircleShape, scaleOnFocus = true, onLightSurface = true),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isLive) "Tune in" else "Play now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (prog.detailId != null && (prog.itemType == "movie" || prog.itemType == "episode" || prog.itemType == "track")) {
                            OutlinedButton(
                                onClick = {
                                    when (prog.itemType) {
                                        "movie" -> onNavigate(PlayerDestination.MovieDetail(prog.detailId))
                                        "episode" -> onNavigate(PlayerDestination.ShowDetail(prog.detailId))
                                        "track" -> onNavigate(PlayerDestination.AlbumDetail(prog.detailId))
                                    }
                                },
                                shape = CircleShape,
                                border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp).dpadFocusable(CircleShape),
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Details", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        if (groupedSections.isEmpty()) {
            EmptyBox("No channels match this filter.")
            return
        }

        if (viewMode == GuideViewMode.GRID) {
            val totalGridWidth = PX_PER_MIN * windowMinutes
            val nowNeedleX = PX_PER_MIN * nowOffsetMin

            // Time-Browse Bar (‹ Earlier · Now · Later ›) matching Desktop Guide 1:1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, SoulSyncColors.Line, CircleShape)
                        .dpadFocusable(CircleShape)
                        .clickable(enabled = offsetHours > -24) { offsetHours = (offsetHours - 2).coerceAtLeast(-24) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("‹ Earlier", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (offsetHours == 0) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (offsetHours == 0) SoulSyncColors.Accent else SoulSyncColors.Line, CircleShape)
                        .dpadFocusable(CircleShape)
                        .clickable { offsetHours = 0 }
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                ) {
                    Text("Now", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, SoulSyncColors.Line, CircleShape)
                        .dpadFocusable(CircleShape)
                        .clickable(enabled = offsetHours < 24) { offsetHours = (offsetHours + 2).coerceAtMost(24) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Later ›", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = timeFmt.format(Date(nowSec * 1000L)),
                    color = SoulSyncColors.AccentLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Time Ruler Header Row with live NOW clock pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(BorderStroke(1.dp, SoulSyncColors.Line)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(CHANNEL_COL_WIDTH)
                        .height(32.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("CHANNELS", color = SoulSyncColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll)
                        .height(32.dp),
                ) {
                    Box(modifier = Modifier.width(totalGridWidth).fillMaxHeight()) {
                        val slots = windowMinutes / 30
                        for (i in 0 until slots) {
                            val slotTime = windowStartSec + i * 1800L
                            val slotX = PX_PER_MIN * (i * 30f)
                            Box(
                                modifier = Modifier
                                    .offset(x = slotX)
                                    .width(PX_PER_MIN * 30)
                                    .fillMaxHeight()
                                    .padding(start = 6.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = timeFmt.format(Date(slotTime * 1000L)),
                                    color = SoulSyncColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (nowVisible) {
                            Box(
                                modifier = Modifier
                                    .offset(x = (nowNeedleX - 28.dp).coerceAtLeast(0.dp))
                                    .align(Alignment.CenterStart)
                                    .clip(CircleShape)
                                    .background(SoulSyncColors.Accent)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = timeFmt.format(Date(nowSec * 1000L)),
                                    color = SoulSyncColors.OnAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }
                    }
                }
            }

            val gridListState = androidx.compose.foundation.lazy.rememberLazyListState()
            val favSectionCount = groupedSections.firstOrNull()?.takeIf { it.key == "favorites" }?.channels?.size ?: 0
            // When Favorites hydrate asynchronously and prepend above the first visible section,
            // LazyColumn anchors to the old first item and pushes Favorites off-screen above index 0.
            // Keep Favorites visible at the top whenever the user is still near the top of the grid!
            LaunchedEffect(favSectionCount, filter) {
                if (favSectionCount > 0 && gridListState.firstVisibleItemIndex <= favSectionCount + 2) {
                    gridListState.scrollToItem(0)
                }
            }

            LazyColumn(
                state = gridListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                groupedSections.forEach { section ->
                    item(key = "hdr-${section.key}") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SoulSyncColors.CardRaised.copy(alpha = 0.65f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = section.label.uppercase(),
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.9.sp,
                            )
                        }
                    }
                    items(section.channels, key = { "${section.key}:${it.id}" }) { ch ->
                        val chViewers = viewersByChannel[ch.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT),
                        ) {
                            Row(
                                modifier = Modifier
                                    .width(CHANNEL_COL_WIDTH)
                                    .fillMaxHeight()
                                    .background(SoulSyncColors.PanelSolid)
                                    .then(if (ch.id == firstChannelId) Modifier.focusRequester(firstChannelFocus) else Modifier)
                                    .dpadFocusable(RoundedCornerShape(8.dp))
                                    .clickable { tuneInChannel(ch) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                ChannelMosaicLogo(urls = ch.mosaicArt, isMusic = ch.isMusic)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = ch.number.toString(),
                                            color = SoulSyncColors.AccentLight,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (chViewers.isNotEmpty()) {
                                            ChannelViewerAvatars(sessions = chViewers, api = api)
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        val isFav = ch.id in favorites
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "Favorite",
                                            tint = if (isFav) SoulSyncColors.Warning else SoulSyncColors.TextSecondary.copy(alpha = 0.35f),
                                            modifier = Modifier
                                                .size(13.dp)
                                                .clickable {
                                                    val updated = if (isFav) favoritesList.filterNot { it == ch.id } else favoritesList + ch.id
                                                    favoritesList = updated
                                                    scope.launch { api.saveGuideFavorites(updated) }
                                                },
                                        )
                                    }
                                    Text(
                                        text = ch.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        lineHeight = 13.sp,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .horizontalScroll(hScroll),
                            ) {
                                Box(modifier = Modifier.width(totalGridWidth).fillMaxHeight()) {
                                    // Subtle 30-minute vertical gridlines aligned with the time ruler
                                    val slotCount = windowMinutes / 30
                                    for (i in 1 until slotCount) {
                                        Box(
                                            modifier = Modifier
                                                .offset(x = PX_PER_MIN * (i * 30f))
                                                .width(1.dp)
                                                .fillMaxHeight()
                                                .background(Color.White.copy(alpha = 0.05f)),
                                        )
                                    }

                                    ch.programs.forEach { prog ->
                                        val visStart = prog.startEpochSec.coerceAtLeast(windowStartSec)
                                        val visEnd = prog.endEpochSec.coerceAtMost(windowEndSec)
                                        if (visEnd > visStart) {
                                            val startX = PX_PER_MIN * ((visStart - windowStartSec) / 60f)
                                            // Exact truthful block width (never inflated to overlap the next program!)
                                            val blockW = (PX_PER_MIN * ((visEnd - visStart) / 60f)).coerceAtLeast(4.dp)
                                            val clippedLeft = prog.startEpochSec < windowStartSec
                                            val isNow = nowSec in prog.startEpochSec until prog.endEpochSec
                                            val visibleSpan = (visEnd - visStart).coerceAtLeast(1L).toFloat()
                                            val liveFrac = if (isNow) {
                                                ((nowSec - visStart).toFloat() / visibleSpan).coerceIn(0f, 1f)
                                            } else 0f
                                            val isCommercial = prog.itemType == "commercial"
                                            val isSelectable = !isCommercial && blockW >= 26.dp
                                            val isPicked = picked?.second == prog && picked?.first?.id == ch.id
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = startX)
                                                    .width(blockW)
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 1.5.dp, vertical = 4.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        when {
                                                            isCommercial -> Color.White.copy(alpha = 0.015f)
                                                            isPicked -> SoulSyncColors.Accent.copy(alpha = 0.28f)
                                                            isNow -> Color.White.copy(alpha = 0.08f)
                                                            else -> Color.White.copy(alpha = 0.03f)
                                                        },
                                                    )
                                                    .border(
                                                        1.dp,
                                                        when {
                                                            isCommercial -> Color.White.copy(alpha = 0.04f)
                                                            isPicked -> SoulSyncColors.AccentLight
                                                            isNow -> SoulSyncColors.Accent.copy(alpha = 0.45f)
                                                            else -> SoulSyncColors.Line
                                                        },
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .then(
                                                        if (isSelectable) {
                                                            Modifier
                                                                .dpadFocusable(RoundedCornerShape(8.dp))
                                                                .clickable { picked = ch to prog }
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                            ) {
                                                if (blockW >= 26.dp) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 7.dp, vertical = 6.dp),
                                                        verticalArrangement = Arrangement.Center,
                                                    ) {
                                                        Text(
                                                            text = (if (prog.premiere) "NEW · " else "") +
                                                                (if (clippedLeft) "◀ ${prog.title}" else prog.title),
                                                            color = if (isNow || isPicked) Color.White else SoulSyncColors.TextSoft,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isNow || isPicked) FontWeight.Bold else FontWeight.Medium,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        val subLine = buildString {
                                                            if (prog.subtitle.isNotBlank()) {
                                                                append(prog.subtitle)
                                                                append(" · ")
                                                            }
                                                            if (clippedLeft) append("Started ")
                                                            append(timeFmt.format(Date(prog.startEpochSec * 1000L)))
                                                        }
                                                        Text(
                                                            text = subLine,
                                                            color = SoulSyncColors.TextSecondary,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (isNow && liveFrac > 0f) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomStart)
                                                            .fillMaxWidth(liveFrac)
                                                            .height(2.5.dp)
                                                            .background(SoulSyncColors.Accent),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (nowVisible) {
                                        Box(
                                            modifier = Modifier
                                                .offset(x = nowNeedleX)
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(SoulSyncColors.AccentLight),
                                        )
                                    }

                                    if (!ch.isMusic && chViewers.isNotEmpty()) {
                                        chViewers.forEach { sess ->
                                            val activeProg = ch.programs.lastOrNull { p ->
                                                p.itemType == sess.itemType &&
                                                    p.itemId == sess.itemId &&
                                                    p.startEpochSec <= nowSec
                                            }
                                            if (activeProg != null) {
                                                val pinEpochSec = (activeProg.startEpochSec + sess.resolvedPosition.toLong())
                                                    .coerceAtMost(activeProg.endEpochSec)
                                                if (pinEpochSec in windowStartSec..windowEndSec) {
                                                    val pinX = (PX_PER_MIN * ((pinEpochSec - windowStartSec) / 60f) - 10.dp)
                                                        .coerceAtLeast(0.dp)
                                                    Box(
                                                        modifier = Modifier
                                                            .offset(x = pinX, y = 4.dp),
                                                    ) {
                                                        ViewerAvatarDot(session = sess, api = api, size = 20.dp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = SoulSyncColors.Line, thickness = 1.dp)
                    }
                }
            }
        } else {
            // "On now" Compact View grouped by desktop sections
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groupedSections.forEach { section ->
                    item(key = "now-hdr-${section.key}") {
                        Text(
                            text = section.label.uppercase(),
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(section.channels, key = { "now:${section.key}:${it.id}" }) { ch ->
                        val cur = ch.programs.firstOrNull { nowSec in it.startEpochSec until it.endEpochSec }
                            ?: ch.programs.firstOrNull()
                        val next = cur?.let { c -> ch.programs.firstOrNull { it.startEpochSec >= c.endEpochSec } }
                        if (cur != null) {
                            val progFrac = ((nowSec - cur.startEpochSec).toFloat() / (cur.endEpochSec - cur.startEpochSec).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dpadFocusable(RoundedCornerShape(12.dp))
                                    .clickable { picked = ch to cur },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ChannelMosaicLogo(urls = ch.mosaicArt, isMusic = ch.isMusic)
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = "CH ${ch.number} · ${ch.name}",
                                                color = SoulSyncColors.AccentLight,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                            )
                                            Text(cur.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (next != null) {
                                                Text("Next: ${next.title}", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(onClick = { tuneInChannel(ch, cur) }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Tune in", tint = SoulSyncColors.AccentLight)
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = { progFrac },
                                        color = SoulSyncColors.Accent,
                                        trackColor = Color.White.copy(alpha = 0.08f),
                                        modifier = Modifier.fillMaxWidth().height(2.5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showMakeChannelDialog) {
            MakeGuideChannelDialog(
                catalog = catalog,
                api = api,
                onDismiss = { showMakeChannelDialog = false },
                onCreated = { created ->
                    showMakeChannelDialog = false
                    val updatedSaved = savedChannels + created
                    savedChannels = updatedSaved
                    scope.launch {
                        allChannels = withContext(Dispatchers.Default) {
                            buildDesktopParityChannels(
                                catalog = catalog,
                                musicPoolSongs = musicPoolSongs,
                                playlists = playlistDetails,
                                musicChannels = musicChannels,
                                savedChannels = updatedSaved,
                                lineup = lineup,
                                studioCollections = studioCollections,
                                api = api,
                                windowStartSec = windowStartSec,
                                windowEndSec = windowEndSec,
                                commercialsConfig = commercialsConfig,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun MakeGuideChannelDialog(
    catalog: VideoCatalog,
    api: PlayerApiClient,
    onDismiss: () -> Unit,
    onCreated: (SavedGuideChannel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(listOf("movies", "tv")) }
    var selectedGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDecades by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedYoutubeChannels by remember { mutableStateOf<List<String>>(emptyList()) }
    var kidsOnly by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf("shuffle") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val availableGenres = remember(catalog) {
        (catalog.movies.flatMap { it.genres } + catalog.shows.flatMap { it.genres })
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(18)
    }
    val availableYoutubeChannels = remember(catalog) {
        catalog.youtube.mapNotNull { it.channelTitle?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(20)
    }
    val decades = remember { listOf(1960, 1970, 1980, 1990, 2000, 2010, 2020) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SoulSyncColors.BgPrimary),
            border = BorderStroke(1.dp, SoulSyncColors.Line),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Make a Channel", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Channel name (e.g. Saturday Matinee)", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("SOURCES", color = SoulSyncColors.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("movies" to "Movies", "tv" to "TV", "youtube" to "YouTube").forEach { (key, label) ->
                        val on = key in kinds
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                                .clickable {
                                    kinds = if (on) kinds - key else kinds + key
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if ("youtube" in kinds && availableYoutubeChannels.isNotEmpty()) {
                    Text("YOUTUBE CHANNELS (OPTIONAL)", color = SoulSyncColors.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availableYoutubeChannels, key = { "yt-$it" }) { chName ->
                            val on = chName in selectedYoutubeChannels
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                                    .clickable {
                                        selectedYoutubeChannels = if (on) selectedYoutubeChannels - chName else selectedYoutubeChannels + chName
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(chName, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (("movies" in kinds || "tv" in kinds) && availableGenres.isNotEmpty()) {
                    Text("GENRES (OPTIONAL)", color = SoulSyncColors.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availableGenres, key = { "g-$it" }) { g ->
                            val on = g in selectedGenres
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                                    .clickable {
                                        selectedGenres = if (on) selectedGenres - g else selectedGenres + g
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(g, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Text("DECADES (OPTIONAL)", color = SoulSyncColors.TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(decades, key = { "d-$it" }) { d ->
                            val on = d in selectedDecades
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (on) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                                    .clickable {
                                        selectedDecades = if (on) selectedDecades - d else selectedDecades + d
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("${d}s", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (order == "in_order") SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (order == "in_order") SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                            .clickable { order = if (order == "shuffle") "in_order" else "shuffle" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (order == "shuffle") "Order: Shuffled" else "Order: In Order",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (kidsOnly) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (kidsOnly) SoulSyncColors.AccentLight else SoulSyncColors.Line, CircleShape)
                            .clickable { kidsOnly = !kidsOnly }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (kidsOnly) "✓ Kids Only" else "Kids Only",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg.orEmpty(), color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (name.trim().isEmpty()) {
                            errorMsg = "Give the channel a name."
                            return@Button
                        }
                        if (kinds.isEmpty()) {
                            errorMsg = "Pick Movies, TV, YouTube, or any combination."
                            return@Button
                        }
                        saving = true
                        errorMsg = null
                        val spec = GuideChannelSpec(
                            name = name.trim(),
                            kinds = kinds,
                            genres = selectedGenres,
                            decades = selectedDecades,
                            youtubeChannels = selectedYoutubeChannels,
                            kids = kidsOnly,
                            order = order,
                        )
                        scope.launch {
                            when (val r = api.createSavedGuideChannel("mine", spec)) {
                                is MusicApiResult.Success -> {
                                    saving = false
                                    onCreated(r.data)
                                }
                                is MusicApiResult.Error -> {
                                    saving = false
                                    errorMsg = r.message
                                }
                                MusicApiResult.DeviceRevoked -> {
                                    saving = false
                                }
                            }
                        }
                    },
                    enabled = !saving,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SoulSyncColors.Accent, contentColor = SoulSyncColors.OnAccent),
                    modifier = Modifier.fillMaxWidth().height(44.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                ) {
                    Text(if (saving) "Saving…" else "Make Channel", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 4-tile poster mosaic channel logo (1:1 with desktop /player/guide ChannelLogo) */
@Composable
private fun ChannelViewerAvatars(sessions: List<WatchingSession>, api: PlayerApiClient) {
    Row(
        horizontalArrangement = Arrangement.spacedBy((-5).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.take(3).forEach { s ->
            ViewerAvatarDot(session = s, api = api, size = 16.dp)
        }
    }
}

@Composable
private fun ViewerAvatarDot(
    session: WatchingSession,
    api: PlayerApiClient,
    size: Dp = 18.dp,
) {
    val name = if (session.you) "You" else session.resolvedName
    val avatarUrl = api.artwork.resolve(session.profile?.avatarUrl)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(SoulSyncColors.Accent)
            .border(1.5.dp, SoulSyncColors.BgPrimary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            SoulSyncArtwork(url = avatarUrl, contentDescription = name, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun ChannelMosaicLogo(urls: List<String>, isMusic: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(SoulSyncColors.CardRaised)
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (urls.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    SoulSyncArtwork(url = urls[0], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                    SoulSyncArtwork(url = urls[1], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(modifier = Modifier.weight(1f)) {
                    SoulSyncArtwork(url = urls[2], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                    SoulSyncArtwork(url = urls[3], contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else if (urls.isNotEmpty()) {
            SoulSyncArtwork(url = urls.first(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                imageVector = if (isMusic) Icons.Default.Radio else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = SoulSyncColors.AccentLight,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

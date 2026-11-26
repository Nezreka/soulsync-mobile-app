package net.ssync.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// home videos: a player library of kind "home" (scrap/HOME_VIDEOS_CONTRACT.md).
// no metadata lookups: what a clip is comes from the file, and the person's edits.

/** one clip, as the /api/player/home routes send it */
data class HomeVideo(
    val id: String,
    val title: String = "",
    /** iso, the person's date or the camera's */
    val filmedAt: String? = null,
    val durationS: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val album: String = "",
    val albumKey: String = "",
    val thumbUrl: String? = null,
    val positionS: Double = 0.0,
    val watched: Boolean = false,
    val missing: Boolean = false,
    // only on the one-video route (edit sheet)
    val notes: String = "",
    val fileName: String = "",
    val thumbAtS: Double? = null,
    val userTitle: Boolean = false,
    val userFilmedAt: Boolean = false,
    /** the name from the file, what a reset goes back to */
    val autoTitle: String = "",
    /** may this profile edit it; null when the server didn't say */
    val canEdit: Boolean? = null,
) {
    /** how far in, 0..1, null when not started or done */
    val progressFraction: Float?
        get() = if (!watched && durationS > 0 && positionS > 5) (positionS / durationS).toFloat().coerceIn(0f, 1f) else null
}

data class HomeFrame(val atS: Double, val url: String)

data class HomeAlbum(
    val key: String,
    val name: String,
    val count: Int = 0,
    val first: String? = null,
    val last: String? = null,
    /** ids of up to three clips for the stacked cover */
    val covers: List<String> = emptyList(),
    /** the folder's path under its root, when it's nested */
    val path: String = "",
)

data class HomeMonth(val month: String, val count: Int)

data class HomeOverview(
    val libraryName: String = "",
    val videos: Int = 0,
    val firstYear: Int? = null,
    val lastYear: Int? = null,
    val onThisDay: List<HomeVideo> = emptyList(),
    val albums: List<HomeAlbum> = emptyList(),
    val months: List<HomeMonth> = emptyList(),
    /** where they come from: a server library's name, a folder */
    val sources: List<String> = emptyList(),
    /** may this profile edit clips; null when the server didn't say */
    val canEdit: Boolean? = null,
    val scanning: Boolean = false,
)

data class HomeVideoPage(val videos: List<HomeVideo>, val total: Int)

data class HomeVideoDetail(val video: HomeVideo, val frames: List<HomeFrame>)

/** the server's json, read leniently: a missing or odd field never fails a page */
object HomeVideoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun overview(body: String): HomeOverview {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return HomeOverview()
        val lib = root["library"]
        val counts = root["counts"] as? JsonObject
        val onThisDay = (root["on_this_day"] as? JsonArray)?.mapNotNull { video(it) }.orEmpty()
        val albums = (root["albums"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.str("key") ?: return@mapNotNull null
            HomeAlbum(
                key = key,
                name = o.str("name") ?: key.substringAfterLast('/').ifBlank { key },
                count = o.int("count") ?: 0,
                first = o.str("first"),
                last = o.str("last"),
                covers = (o["covers"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
                path = o.str("path").orEmpty(),
            )
        }.orEmpty()
        val months = (root["months"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val m = o.str("month")?.takeIf { MONTH_KEY.matches(it) } ?: return@mapNotNull null
            HomeMonth(m, o.int("count") ?: 0)
        }.orEmpty().filter { it.count > 0 }.sortedByDescending { it.month }
        return HomeOverview(
            libraryName = (lib as? JsonObject)?.str("name") ?: lib.str().orEmpty(),
            videos = counts?.int("videos") ?: months.sumOf { it.count },
            firstYear = counts?.int("first_year"),
            lastYear = counts?.int("last_year"),
            onThisDay = onThisDay,
            albums = albums,
            months = months,
            sources = (root["sources"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            canEdit = root.bool("can_edit"),
            scanning = root.bool("scanning") ?: false,
        )
    }

    fun page(body: String): HomeVideoPage {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return HomeVideoPage(emptyList(), 0)
        val videos = (root["videos"] as? JsonArray)?.mapNotNull { video(it) }.orEmpty()
        return HomeVideoPage(videos, root.int("total") ?: videos.size)
    }

    fun detail(body: String): HomeVideoDetail? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        // the video either at the top level or under "video"
        val v = video(root["video"] ?: root) ?: return null
        val frames = (root["frames"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o.str("url") ?: return@mapNotNull null
            HomeFrame(o.dbl("at_s") ?: 0.0, url)
        }.orEmpty()
        return HomeVideoDetail(v, frames)
    }

    /** a video from the put route's answer, if it sent one back */
    fun videoOrNull(body: String): HomeVideo? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        video(root["video"] ?: root)
    }.getOrNull()

    fun video(el: JsonElement?): HomeVideo? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val progress = o["progress"] as? JsonObject
        val path = o.str("path") ?: o.str("file")
        val edited = o["edited"] as? JsonObject
        return HomeVideo(
            id = id,
            title = o.str("title").orEmpty(),
            filmedAt = o.str("filmed_at"),
            durationS = o.dbl("duration_s") ?: 0.0,
            width = o.int("width") ?: 0,
            height = o.int("height") ?: 0,
            album = o.str("album").orEmpty(),
            albumKey = o.str("album_key").orEmpty(),
            thumbUrl = o.str("thumb_url"),
            positionS = progress?.dbl("position_s") ?: 0.0,
            watched = progress?.bool("watched") ?: false,
            missing = o.bool("missing") ?: false,
            notes = o.str("notes").orEmpty(),
            fileName = o.str("file_name") ?: path?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty(),
            thumbAtS = o.dbl("thumb_at_s"),
            userTitle = edited?.bool("title") ?: !o.str("user_title").isNullOrBlank(),
            userFilmedAt = edited?.bool("filmed_at") ?: !o.str("user_filmed_at").isNullOrBlank(),
            autoTitle = o.str("auto_title").orEmpty(),
            canEdit = o.bool("can_edit"),
        )
    }

    private val MONTH_KEY = Regex("""^\d{4}-\d{2}$""")

    private fun JsonElement?.str(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    private fun JsonObject.str(k: String): String? = this[k].str()
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.int(k: String): Int? = dbl(k)?.toInt()
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
        it.booleanOrNull ?: it.content.toIntOrNull()?.let { n -> n != 0 }
    }
}

/** the words and dates a clip is shown with. plain kotlin, unit tested. */
object HomeVideoText {
    private val dayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val fullFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val longFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val shortMonthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val monthDayFmt = DateTimeFormatter.ofPattern("MMMM d", Locale.US)

    /**
     * when it was filmed, on this device's clock. a time with an offset is
     * moved to [zone]; a bare time is the camera's wall clock and kept as is.
     */
    fun filmed(iso: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
        val s = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s.replace(' ', 'T').replace("Z", "+00:00")).atZoneSameInstant(zone).toLocalDateTime() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T').take(26)) }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T').take(19)) }
        runCatching { return LocalDate.parse(s.take(10)).atStartOfDay() }
        return null
    }

    /** the name to show: the clip's, else its date, else a plain word */
    fun title(v: HomeVideo, zone: ZoneId = ZoneId.systemDefault()): String =
        v.title.ifBlank { filmed(v.filmedAt, zone)?.let { "${fullFmt.format(it)}, ${timeFmt.format(it)}" } ?: "Home video" }

    /** "Nov 22": the corner badge in a month */
    fun day(v: HomeVideo, zone: ZoneId = ZoneId.systemDefault()): String =
        filmed(v.filmedAt, zone)?.let { dayFmt.format(it) }.orEmpty()

    /** "Nov 25, 2023" */
    fun date(v: HomeVideo, zone: ZoneId = ZoneId.systemDefault()): String =
        filmed(v.filmedAt, zone)?.let { fullFmt.format(it) }.orEmpty()

    /** "October 31, 2026 · 6:30 PM": the edit sheet's date */
    fun dateTime(at: LocalDateTime?): String = at?.let { "${longFmt.format(it)} · ${timeFmt.format(it)}" } ?: "Not known"

    /** "4:12", "1:02:07"; blank when it isn't known */
    fun duration(seconds: Double): String {
        if (seconds <= 0.0) return ""
        val t = seconds.toLong().coerceAtLeast(1)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "2026-11" for grouping, on this device's clock */
    fun monthKey(v: HomeVideo, zone: ZoneId = ZoneId.systemDefault()): String =
        filmed(v.filmedAt, zone)?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: ""

    /** "November 2026" from "2026-11"; a key it can't read comes back as "Undated" */
    fun monthTitle(key: String): String = runCatching {
        monthFmt.format(LocalDate.parse("$key-01"))
    }.getOrElse { "Undated" }

    /** "Nov 2026" for the scrubber bubble */
    fun monthShort(key: String): String = runCatching {
        shortMonthFmt.format(LocalDate.parse("$key-01"))
    }.getOrElse { key }

    /** "November 25": the On this day heading */
    fun today(now: LocalDate = LocalDate.now()): String = monthDayFmt.format(now)

    /** "3 years ago", "Last year"; null when it was filmed this year or the date isn't known */
    fun yearsAgo(v: HomeVideo, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = filmed(v.filmedAt, zone)?.toLocalDate() ?: return null
        val years = ChronoUnit.YEARS.between(at, now).toInt()
        return when {
            years <= 0 -> null
            years == 1 -> "1 year ago"
            else -> "$years years ago"
        }
    }

    /** "from Plex “Home Videos” and /media/family" */
    fun sourcesLine(o: HomeOverview): String? = o.sources.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { s ->
        "from " + if (s.size == 1) s[0] else s.dropLast(1).joinToString(", ") + " and " + s.last()
    }

    fun count(n: Int): String = if (n == 1) "1 video" else "%,d videos".format(Locale.US, n)

    /** "412 videos · 2009 – 2026" */
    fun libraryLine(o: HomeOverview): String {
        val years = when {
            o.firstYear != null && o.lastYear != null && o.firstYear != o.lastYear -> "${o.firstYear} – ${o.lastYear}"
            o.firstYear != null -> "${o.firstYear}"
            o.lastYear != null -> "${o.lastYear}"
            else -> null
        }
        return listOfNotNull(count(o.videos), years).joinToString(" · ")
    }

    /** "86 videos · 2010 – 2025", "23 videos · Jul 2024" */
    fun albumLine(a: HomeAlbum, zone: ZoneId = ZoneId.systemDefault()): String {
        val first = filmed(a.first, zone)
        val last = filmed(a.last, zone)
        val a1 = first ?: last
        val b1 = last ?: first
        val span = when {
            a1 == null || b1 == null -> null
            a1.year == b1.year && a1.monthValue == b1.monthValue -> shortMonthFmt.format(a1)
            a1.year == b1.year -> "${a1.year}"
            else -> "${minOf(a1.year, b1.year)} – ${maxOf(a1.year, b1.year)}"
        }
        return listOfNotNull(count(a.count), span).joinToString(" · ")
    }

    /** an album's name: its folder's last part */
    fun albumName(a: HomeAlbum): String = a.name.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { a.name }

    /** what the date field sends: the wall-clock time, no zone */
    fun isoLocal(at: LocalDateTime): String = at.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /** where to resume: from the start when watched or barely begun */
    fun resumeMs(v: HomeVideo): Long = if (v.watched || v.positionS < 5) 0L else (v.positionS * 1000).toLong()
}

/** clips of one list (a month, an album, a search) as pages arrive, in any order */
object HomePaging {
    const val PAGE = 60

    /**
     * puts [page] at [offset] into a sparse list sized to [total]. a clip that
     * moved (an edit changed its date) is cleared from where it was, so that
     * spot loads again instead of showing it twice.
     */
    fun merge(current: List<HomeVideo?>, total: Int, offset: Int, page: List<HomeVideo>): List<HomeVideo?> {
        val size = maxOf(total, 0).coerceAtLeast(if (page.isEmpty()) 0 else offset + page.size)
        val out = ArrayList<HomeVideo?>(size)
        for (i in 0 until size) out.add(current.getOrNull(i))
        val ids = page.map { it.id }.toHashSet()
        for (i in out.indices) {
            val v = out[i]
            if (v != null && v.id in ids && (i < offset || i >= offset + page.size)) out[i] = null
        }
        page.forEachIndexed { i, v -> if (offset + i < out.size) out[offset + i] = v }
        return out
    }

    /** the page start to fetch so [index] shows, or null when it's there already */
    fun pageFor(slots: List<HomeVideo?>, index: Int, pageSize: Int = PAGE): Int? {
        if (index < 0 || index >= slots.size) return null
        val start = (index / pageSize) * pageSize
        val end = minOf(start + pageSize, slots.size)
        return if ((start until end).any { slots[it] == null }) start else null
    }

    /** the clips that are there, in order: what plays next */
    fun loaded(slots: List<HomeVideo?>): List<HomeVideo> = slots.filterNotNull()

    /** a changed clip put back in every place it shows */
    fun replace(slots: List<HomeVideo?>, v: HomeVideo): List<HomeVideo?> =
        if (slots.none { it?.id == v.id }) slots else slots.map { if (it?.id == v.id) v else it }
}

/** a flat list of clips folded into months, newest month first (search results, an undated fallback) */
object HomeMonths {
    fun group(videos: List<HomeVideo>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<HomeVideo>>> =
        videos.groupBy { HomeVideoText.monthKey(it, zone) }
            .entries
            .sortedWith(compareBy<Map.Entry<String, List<HomeVideo>>> { it.key.isEmpty() }.thenByDescending { it.key })
            .map { it.key to it.value }

    /** years, newest first, each with its months: the jump list and the scrubber */
    fun years(months: List<HomeMonth>): List<Pair<Int, List<HomeMonth>>> =
        months.groupBy { it.month.take(4).toIntOrNull() ?: 0 }
            .entries.sortedByDescending { it.key }
            .map { (y, ms) -> y to ms.sortedByDescending { it.month } }
}

/**
 * the phone timeline as list rows: a sticky header per month, then rows of
 * [columns] cells. a row knows which cells of its month it holds.
 */
sealed interface HomeTimelineRow {
    val key: String
    data class Header(val month: String, val count: Int) : HomeTimelineRow {
        override val key: String get() = "h-$month"
    }
    data class Cells(val month: String, val start: Int, val end: Int) : HomeTimelineRow {
        override val key: String get() = "r-$month-$start"
    }
}

object HomeTimeline {
    fun rows(months: List<HomeMonth>, columns: Int): List<HomeTimelineRow> {
        val cols = columns.coerceAtLeast(1)
        val out = ArrayList<HomeTimelineRow>()
        for (m in months) {
            if (m.count <= 0) continue
            out += HomeTimelineRow.Header(m.month, m.count)
            var i = 0
            while (i < m.count) {
                out += HomeTimelineRow.Cells(m.month, i, minOf(i + cols, m.count))
                i += cols
            }
        }
        return out
    }

    /** where each month's header sits in [rows] */
    fun headerIndex(rows: List<HomeTimelineRow>): Map<String, Int> =
        rows.withIndex().filter { it.value is HomeTimelineRow.Header }.associate { (it.value as HomeTimelineRow.Header).month to it.index }

    /** the month a row belongs to */
    fun monthAt(rows: List<HomeTimelineRow>, index: Int): String? = when (val r = rows.getOrNull(index)) {
        is HomeTimelineRow.Header -> r.month
        is HomeTimelineRow.Cells -> r.month
        null -> null
    }
}

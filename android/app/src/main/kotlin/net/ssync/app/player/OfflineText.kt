package net.ssync.app.player

import net.ssync.player.DownloadTask
import net.ssync.player.OfflineFormat
import net.ssync.player.OfflineDeviceItem
import net.ssync.player.OfflineGroup
import net.ssync.player.OfflineRule
import net.ssync.player.TaskState

// the words the downloads screens use. plain kotlin, unit tested.

/** a task belongs to a group button on a detail page */
internal fun inGroup(t: DownloadTask, type: String, id: String): Boolean = when (type) {
    "show" -> (t.groupType == "show" && t.groupId == id) || (t.itemType == "episode" && t.parentId == id)
    "season" -> t.groupType == "season" && t.groupId == id ||
        (t.groupType == "show" && t.groupId == id.substringBefore(':') && t.sortIndex / 1000 == id.substringAfter(':').toIntOrNull())
    else -> t.groupType == type && t.groupId == id
}

/** "720p · 412 of 690 MB · 6.1 MB/s", "Waiting", "Server preparing 40%" */
internal fun statusLine(t: DownloadTask, live: LiveProgress?): String {
    val q = OfflineFormat.qualityTag(t.quality)
    return when (t.state) {
        TaskState.DOWNLOADED -> listOfNotNull(
            q,
            OfflineFormat.bytes(t.diskBytes),
            if (t.watched) "watched" else if (t.durationS > 0 && t.positionS > 30) "watched ${(t.positionS * 100 / t.durationS).toInt()}%" else null,
        ).joinToString(" · ")
        TaskState.DOWNLOADING -> {
            val done = live?.bytesDone ?: t.bytesDone
            val total = live?.totalBytes?.takeIf { it > 0 } ?: t.totalBytes.takeIf { it > 0 } ?: t.expectedBytes
            listOfNotNull(
                q,
                if (total > 0) "${OfflineFormat.bytes(done)} of ${OfflineFormat.bytes(total)}" else OfflineFormat.bytes(done),
                live?.bytesPerSecond?.let { OfflineFormat.speed(it) }?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
        TaskState.PREPARING -> "Server preparing $q · ${(t.convertProgress * 100).toInt()}%"
        TaskState.QUEUED -> t.error?.takeIf { it.startsWith("Retrying") } ?: if (t.bytesDone > 0) "Waiting · ${OfflineFormat.bytes(t.bytesDone)} done" else "Waiting"
        TaskState.PAUSED -> if (t.bytesDone > 0) "Paused · ${OfflineFormat.bytes(t.bytesDone)} done" else "Paused"
        TaskState.FAILED -> t.error ?: "Didn't finish"
    }
}

internal fun groupNoun(type: String, count: Int, video: Boolean): String = when {
    type == "season" || type == "show" || (video && type != "movie") -> if (count == 1) "episode" else "episodes"
    type == "podcast" -> if (count == 1) "episode" else "episodes"
    type == "audiobook" -> if (count == 1) "part" else "parts"
    video -> if (count == 1) "video" else "videos"
    else -> if (count == 1) "song" else "songs"
}

/** "2 h ago" from the server's iso time */
internal fun seenAgo(iso: String): String {
    val at = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(iso.take(19)).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        ?: return iso
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / 1440} days ago"
    }
}

/** "Smart · next 3 unwatched", "Stays in step" for a group with a rule */
internal fun smartTag(g: OfflineGroup, rules: List<OfflineRule>): String? {
    val type = when (g.groupType) { "season" -> "show"; else -> g.groupType }
    val r = rules.firstOrNull { it.type == type && it.id == g.groupId } ?: return null
    return ruleTag(r)
}

/** "Smart · next 3 unwatched · deletes watched · 720p", like the web player says it */
internal fun ruleTag(r: OfflineRule): String {
    val keep = r.keepNext ?: 0
    val parts = listOfNotNull(
        "Smart",
        when {
            r.type == "playlist" && r.follow -> "stays in step"
            r.type == "podcast" && keep > 0 -> "newest $keep"
            r.type != "playlist" && keep > 0 -> "next $keep unwatched"
            else -> null
        },
        if (r.deleteWatched && r.type != "playlist") (if (r.type == "podcast") "deletes played" else "deletes watched") else null,
        OfflineFormat.qualityTag(r.quality),
    )
    return parts.joinToString(" · ")
}

/** one remote device's group of items: a show (season), an album, a playlist, a movie */
internal data class RemoteGroup(val type: String, val id: String, val title: String, val artwork: String?, val items: List<OfflineDeviceItem>)

/** a device's items folded by the group the server says they belong to */
internal fun remoteGroups(items: List<OfflineDeviceItem>): List<RemoteGroup> =
    items.groupBy { it.groupRef()?.let { g -> "${g.type}|${g.id}" } ?: "${it.itemType}|${it.itemId}" }
        .map { (k, list) ->
            val g = list.first().groupRef()
            RemoteGroup(
                type = g?.type ?: k.substringBefore('|'),
                id = g?.id ?: k.substringAfter('|'),
                title = g?.title?.takeIf { it.isNotBlank() } ?: list.first().title ?: k.substringAfter('|'),
                artwork = g?.artwork ?: list.first().artwork,
                items = list,
            )
        }

/** the rule (if any) behind a remote group: a season's rule is its show's */
internal fun ruleFor(type: String, id: String, rules: List<OfflineRule>): OfflineRule? {
    val rt = if (type == "season") "show" else type
    val rid = if (type == "season") id.substringBefore(':') else id
    return rules.firstOrNull { it.type == rt && it.id == rid }
}

private val SEASON_EPISODE = Regex("""\bS(\d{1,3})\s*[ .:x-]?\s*E(\d{1,4})\b""", RegexOption.IGNORE_CASE)

/**
 * where an item sorts in its group: an episode's season * 1000 + number when
 * its title says ("S2 E5"), else its place in the plan. a whole show's
 * places start past any season so they never read as specials (season 0)
 */
internal fun sortIndexFor(title: String, subtitle: String?, target: DownloadTarget, seasonBase: Int, index: Int, count: Int): Int {
    if (count == 1 && target.sortIndex > 0) return target.sortIndex
    val m = SEASON_EPISODE.find(subtitle.orEmpty()) ?: SEASON_EPISODE.find(title)
    if (m != null) return m.groupValues[1].toInt() * 1000 + m.groupValues[2].toInt()
    return when (target.type) {
        "season" -> seasonBase + index + 1
        "show" -> 900_000 + index
        else -> index + 1
    }
}

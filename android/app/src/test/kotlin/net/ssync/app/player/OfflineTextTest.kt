package net.ssync.app.player

import net.ssync.player.DownloadTask
import net.ssync.player.OfflineGrouping
import net.ssync.player.OfflineRule
import net.ssync.player.TaskState
import net.ssync.player.offlineKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTextTest {
    private val mb = 1024L * 1024

    private fun ep(id: String, sort: Int, state: TaskState = TaskState.DOWNLOADED, watched: Boolean = false) = DownloadTask(
        key = offlineKey("episode", id), itemType = "episode", itemId = id, title = "E$id",
        groupType = "show", groupId = "12", groupTitle = "Severance", parentId = "12",
        sortIndex = sort, state = state, quality = "phone", bytesDone = 690 * mb, totalBytes = 690 * mb, watched = watched,
    )

    @Test
    fun groupButtonsFindTheirEpisodes() {
        val s2e5 = ep("5", 2005)
        assertTrue(inGroup(s2e5, "show", "12"))
        assertTrue(inGroup(s2e5, "season", "12:2"))
        assertFalse(inGroup(s2e5, "season", "12:1"))
        assertFalse(inGroup(s2e5, "show", "13"))
        // a whole-show download sorts past every season: never mistaken for specials
        assertFalse(inGroup(ep("9", 900_004), "season", "12:0"))
        val track = DownloadTask(key = "track:1", itemType = "track", itemId = "1", title = "t", groupType = "album", groupId = "55")
        assertTrue(inGroup(track, "album", "55"))
        assertFalse(inGroup(track, "playlist", "55"))
    }

    @Test
    fun sortIndexReadsSeasonAndEpisodeFromTitles() {
        val season = DownloadTarget(type = "season", id = "12:2", title = "Severance")
        assertEquals(2005, sortIndexFor("Trojan's Horse", "S2 E5", season, 2000, 0, 10))
        assertEquals(1003, sortIndexFor("S01E03 · Woe", null, season, 2000, 0, 10))
        assertEquals(2004, sortIndexFor("No code", null, season, 2000, 3, 10))
        assertEquals(900_003, sortIndexFor("No code", null, DownloadTarget("show", "12", "S"), 0, 3, 10))
        assertEquals(7, sortIndexFor("Song", null, DownloadTarget("album", "1", "A"), 0, 6, 10))
        assertEquals(2006, sortIndexFor("x", null, DownloadTarget("episode", "1", "x", sortIndex = 2006), 0, 0, 1))
    }

    @Test
    fun statusLinesReadLikeTheMockup() {
        val live = LiveProgress(bytesDone = 412 * mb, totalBytes = 690 * mb, bytesPerSecond = (6.1 * mb).toLong())
        assertEquals("720p · 412 MB of 690 MB · 6.1 MB/s", statusLine(ep("5", 2005, TaskState.DOWNLOADING), live))
        assertEquals("Waiting", statusLine(ep("6", 2006, TaskState.QUEUED).copy(bytesDone = 0), null))
        assertEquals("720p · 690 MB · watched", statusLine(ep("3", 2003, watched = true), null))
        val converting = ep("7", 2007, TaskState.PREPARING).copy(convertProgress = 0.4)
        assertEquals("Server preparing 720p · 40%", statusLine(converting, null))
        assertEquals("Over your storage limit", statusLine(ep("8", 2008, TaskState.FAILED).copy(error = "Over your storage limit"), null))
        val halfway = ep("9", 2009).copy(positionS = 600.0, durationS = 1500.0)
        assertTrue(statusLine(halfway, null).endsWith("watched 40%"))
    }

    @Test
    fun smartTagsComeFromThisPhonesRules() {
        val group = OfflineGrouping.groups(listOf(ep("5", 2005), ep("6", 2006))).single()
        assertNull(smartTag(group, emptyList()))
        assertEquals("Smart · next 3 unwatched", smartTag(group, listOf(OfflineRule(type = "show", id = "12", keepNext = 3))))
        assertEquals("Smart · deletes watched", smartTag(group, listOf(OfflineRule(type = "show", id = "12", deleteWatched = true))))
        val list = DownloadTask(key = "track:1", itemType = "track", itemId = "1", title = "t", groupType = "playlist", groupId = "user:4", state = TaskState.DOWNLOADED)
        val pl = OfflineGrouping.groups(listOf(list)).single()
        assertEquals("Smart · stays in step", smartTag(pl, listOf(OfflineRule(type = "playlist", id = "user:4", follow = true))))
    }

    @Test
    fun nounsAndAges() {
        assertEquals("episodes", groupNoun("season", 6, true))
        assertEquals("song", groupNoun("album", 1, false))
        assertEquals("just now", seenAgo(java.time.Instant.now().toString()))
        assertEquals("2 h ago", seenAgo(java.time.Instant.now().minusSeconds(2 * 3600 + 30).toString()))
        assertEquals("not a date", seenAgo("not a date"))
    }
}

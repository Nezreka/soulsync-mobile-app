package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLogicTest {

    private fun task(
        id: String,
        type: String = "episode",
        state: TaskState = TaskState.QUEUED,
        order: Long = 0,
        bytes: Long = 0,
        total: Long = 0,
        expected: Long = 0,
        watched: Boolean = false,
        completed: Long = 0,
        group: String = "",
        attempts: Int = 0,
    ) = DownloadTask(
        key = offlineKey(type, id), itemType = type, itemId = id, title = "t$id",
        state = state, order = order, bytesDone = bytes, totalBytes = total, expectedBytes = expected,
        watched = watched, completedAtMs = completed, groupType = if (group.isBlank()) "" else "show",
        groupId = group, attempts = attempts,
    )

    // ── queue ──

    @Test
    fun runnableSkipsPausedFailedAndDoneInOrder() {
        val tasks = listOf(
            task("1", order = 3),
            task("2", order = 1, state = TaskState.PAUSED),
            task("3", order = 2, state = TaskState.PREPARING),
            task("4", order = 0, state = TaskState.DOWNLOADED),
            task("5", order = 4, state = TaskState.FAILED),
        )
        assertEquals(listOf("3", "1"), DownloadQueue.runnable(tasks).map { it.itemId })
        assertEquals(listOf("2", "3", "1", "5"), DownloadQueue.pending(tasks).map { it.itemId })
    }

    @Test
    fun reorderMovesAndRenumbersOnlyPending() {
        val tasks = listOf(task("a", order = 1), task("b", order = 2), task("c", order = 3), task("d", state = TaskState.DOWNLOADED, order = 9))
        val top = DownloadQueue.moveToTop(tasks, "episode:c")
        assertEquals(listOf("c", "a", "b"), DownloadQueue.pending(top).map { it.itemId })
        assertEquals(9L, top.first { it.itemId == "d" }.order)
        val down = DownloadQueue.moveDown(top, "episode:c")
        assertEquals(listOf("a", "c", "b"), DownloadQueue.pending(down).map { it.itemId })
        // the edges stay put
        assertEquals(tasks, DownloadQueue.moveUp(tasks, "episode:a"))
        assertEquals(tasks, DownloadQueue.moveDown(tasks, "episode:c"))
        assertEquals(tasks, DownloadQueue.move(tasks, "episode:zzz", 0))
    }

    @Test
    fun restartRequeuesDownloadsButKeepsBytesAndServerJobs() {
        val tasks = listOf(
            task("1", state = TaskState.DOWNLOADING, bytes = 500, total = 1000),
            task("2", state = TaskState.PREPARING).copy(prepareId = "p9"),
            task("3", state = TaskState.PAUSED),
        )
        val after = DownloadQueue.afterRestart(tasks)
        assertEquals(TaskState.QUEUED, after[0].state)
        assertEquals(500L, after[0].bytesDone)
        assertEquals(TaskState.PREPARING, after[1].state)
        assertEquals("p9", after[1].prepareId)
        assertEquals(TaskState.PAUSED, after[2].state)
    }

    @Test
    fun retryBacksOffAndCaps() {
        assertEquals(5_000L, DownloadQueue.retryDelayMs(1))
        assertEquals(15_000L, DownloadQueue.retryDelayMs(2))
        assertEquals(45_000L, DownloadQueue.retryDelayMs(3))
        assertEquals(300_000L, DownloadQueue.retryDelayMs(20))
    }

    @Test
    fun nextOrderGoesToTheBack() {
        assertEquals(1L, DownloadQueue.nextOrder(emptyList()))
        assertEquals(8L, DownloadQueue.nextOrder(listOf(task("1", order = 7), task("2", order = 2))))
    }

    // ── wanted / report ──

    @Test
    fun reconcileAddsWhatsMissingAndRemovesWhatsHere() {
        val local = listOf(
            task("1", state = TaskState.DOWNLOADED),
            task("2", state = TaskState.QUEUED),
            task("3", state = TaskState.FAILED, attempts = 1),
            task("4", state = TaskState.FAILED, attempts = 9),
        )
        val wanted = OfflineWanted(
            add = listOf(
                OfflineWantedAdd("episode", "1", quality = "high"), // here already, other quality: leave it
                OfflineWantedAdd("episode", "2"), // queued already
                OfflineWantedAdd("episode", "3"), // failed once: try again
                OfflineWantedAdd("episode", "4"), // failed for good: leave it
                OfflineWantedAdd("episode", "5"),
                OfflineWantedAdd("episode", "5"), // duplicate
                OfflineWantedAdd("episode", "6"), // also removed below: remove wins
            ),
            remove = listOf(
                OfflineWantedRemove("episode", "1", "watched"),
                OfflineWantedRemove("episode", "6", "rule"),
                OfflineWantedRemove("episode", "99", "wish"), // not here: nothing to do
            ),
        )
        val plan = OfflineReconcile.plan(wanted, local + task("6", state = TaskState.DOWNLOADED))
        assertEquals(listOf("3", "5"), plan.add.map { it.itemId })
        assertEquals(listOf("episode:1", "episode:6"), plan.remove)
    }

    @Test
    fun reportCarriesEveryTaskWithServerStates() {
        val items = OfflineReconcile.reportItems(
            listOf(
                task("1", state = TaskState.DOWNLOADED, bytes = 700, total = 700),
                task("2", state = TaskState.DOWNLOADING, bytes = 250, total = 1000),
                task("3", state = TaskState.PREPARING, expected = 400),
                task("4", state = TaskState.PAUSED, bytes = 10, total = 100),
            ),
        )
        assertEquals(listOf("downloaded", "downloading", "downloading", "paused"), items.map { it.state })
        assertEquals(700L, items[0].sizeBytes)
        assertEquals(0.25, items[1].progress, 0.0001)
        assertEquals(400L, items[2].sizeBytes)
        assertEquals(1.0, items[0].progress, 0.0)
    }

    @Test
    fun deviceInfoFillsMigratedTitlesAndGroups() {
        val local = listOf(task("7", state = TaskState.DOWNLOADED).copy(title = "Episode 7", needsMeta = true))
        val device = OfflineDevice(
            deviceId = "d",
            items = listOf(
                OfflineDeviceItem(
                    "episode", "7", title = "Woe's Hollow", subtitle = "Severance",
                    group = kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("show"))
                        put("id", kotlinx.serialization.json.JsonPrimitive(12))
                        put("title", kotlinx.serialization.json.JsonPrimitive("Severance"))
                    },
                ),
            ),
        )
        val merged = OfflineReconcile.mergeDeviceInfo(local, device).single()
        assertEquals("Woe's Hollow", merged.title)
        assertEquals("show", merged.groupType)
        assertEquals("12", merged.groupId)
        assertEquals("Severance", merged.groupTitle)
        assertFalse(merged.needsMeta)
    }

    @Test
    fun serverSeasonGroupsFileUnderTheirShowAndCarryWatchState() {
        val local = listOf(task("8", state = TaskState.DOWNLOADED).copy(reason = "rule"))
        val device = OfflineDevice(
            deviceId = "d",
            items = listOf(
                OfflineDeviceItem(
                    "episode", "8", title = "Hello, Ms. Cobel", watched = true, positionS = 0.0, durationS = 3000.0,
                    group = kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("season"))
                        put("id", kotlinx.serialization.json.JsonPrimitive("12:2"))
                        put("title", kotlinx.serialization.json.JsonPrimitive("Severance"))
                        put("subtitle", kotlinx.serialization.json.JsonPrimitive("Season 2"))
                    },
                ),
            ),
        )
        val merged = OfflineReconcile.mergeDeviceInfo(local, device).single()
        assertEquals("show", merged.groupType)
        assertEquals("12", merged.groupId)
        assertEquals(2000, merged.sortIndex)
        assertTrue(merged.watched)
        assertEquals(3000.0, merged.durationS, 0.0)
        val ref = parseGroupRef(device.items.single().group)!!
        assertEquals(2, ref.seasonNumber)
        assertEquals("Season 2", ref.subtitle)
        assertNull(parseGroupRef(null))
    }

    // ── offline event queue ──

    private fun ev(kind: String, id: String, pos: Double = 0.0, at: String = "2026-11-25T10:00:00Z") =
        OfflineSyncEvent(kind, "episode", id, positionS = pos, durationS = 100.0, at = at)

    @Test
    fun eventQueueKeepsNewestProgressOneWatchedAndDistinctPlays() {
        var q = emptyList<OfflineSyncEvent>()
        q = OfflineEventQueue.add(q, ev("progress", "1", 10.0, "t1"))
        q = OfflineEventQueue.add(q, ev("progress", "1", 50.0, "t2"))
        q = OfflineEventQueue.add(q, ev("progress", "2", 5.0, "t2"))
        q = OfflineEventQueue.add(q, ev("watched", "1", at = "t3"))
        q = OfflineEventQueue.add(q, ev("watched", "1", at = "t4"))
        q = OfflineEventQueue.add(q, ev("play", "1", at = "t1"))
        q = OfflineEventQueue.add(q, ev("play", "1", at = "t1"))
        q = OfflineEventQueue.add(q, ev("play", "1", at = "t5"))
        assertEquals(1, q.count { it.kind == "progress" && it.itemId == "1" })
        assertEquals(50.0, q.first { it.kind == "progress" && it.itemId == "1" }.positionS, 0.0)
        assertEquals(1, q.count { it.kind == "watched" })
        assertEquals(2, q.count { it.kind == "play" })
        val sent = q.take(2)
        assertEquals(q.size - 2, OfflineEventQueue.afterSent(q, sent).size)
    }

    @Test
    fun eventQueueCapsDroppingOldPositionsFirst() {
        var q = emptyList<OfflineSyncEvent>()
        q = OfflineEventQueue.add(q, ev("watched", "w"))
        repeat(OfflineEventQueue.MAX_EVENTS + 5) { i -> q = OfflineEventQueue.add(q, ev("progress", "p$i")) }
        assertEquals(OfflineEventQueue.MAX_EVENTS, q.size)
        assertTrue(q.any { it.kind == "watched" })
        assertFalse(q.any { it.itemId == "p0" })
        assertEquals(3, OfflineEventQueue.batches(List(450) { ev("play", "x", at = "$it") }).size)
    }

    // ── storage ──

    private val gb = 1024L * 1024 * 1024

    @Test
    fun storageCheckUsesTheLimitAndTheDisk() {
        // 25 gb limit, 18.4 used, plenty of disk
        val fits = OfflineStorage.check(4 * gb, (18.4 * gb).toLong(), 25 * gb, 100 * gb)
        assertTrue(fits.fits)
        val over = OfflineStorage.check(8 * gb, (18.4 * gb).toLong(), 25 * gb, 100 * gb)
        assertEquals(FitVerdict.OVER_LIMIT, over.verdict)
        // no limit, but the disk is nearly full: the reserve stays free
        val disk = OfflineStorage.check(2 * gb, 0, 0, 2 * gb)
        assertEquals(FitVerdict.NO_SPACE, disk.verdict)
        assertEquals(2 * gb - OfflineStorage.RESERVE_BYTES, disk.roomBytes)
    }

    @Test
    fun canStartCountsOnlyWhatsLeftOfAPartialDownload() {
        val partial = task("1", state = TaskState.QUEUED, bytes = 9 * gb, total = 10 * gb)
        val done = task("2", state = TaskState.DOWNLOADED, bytes = 5 * gb, total = 5 * gb)
        // used 14 of 15: the last 1 gb still fits
        assertTrue(OfflineStorage.canStart(partial, listOf(partial, done), 15 * gb, 100 * gb).fits)
        assertFalse(OfflineStorage.canStart(partial, listOf(partial, done), 14 * gb, 100 * gb).fits)
    }

    @Test
    fun freeUpTakesWatchedFirstThenOldest() {
        val tasks = listOf(
            task("old", state = TaskState.DOWNLOADED, total = 3 * gb, bytes = 3 * gb, completed = 1),
            task("new", state = TaskState.DOWNLOADED, total = 3 * gb, bytes = 3 * gb, completed = 9),
            task("w1", state = TaskState.DOWNLOADED, total = 1 * gb, bytes = 1 * gb, watched = true, completed = 5),
            task("w2", state = TaskState.DOWNLOADED, total = 2 * gb, bytes = 2 * gb, watched = true, completed = 6),
            task("q", state = TaskState.QUEUED, expected = 9 * gb),
        )
        assertEquals(listOf("w2", "w1"), OfflineStorage.freeUpPlan(tasks, 0).map { it.itemId })
        assertEquals(listOf("w2", "w1", "old"), OfflineStorage.freeUpPlan(tasks, 4 * gb).map { it.itemId })
        assertEquals(listOf("w2"), OfflineStorage.freeUpPlan(tasks, 1 * gb).map { it.itemId })
    }

    @Test
    fun bytesByShelfSplitsKinds() {
        val by = OfflineStorage.bytesByShelf(
            listOf(
                task("1", type = "episode", state = TaskState.DOWNLOADED, total = 10, bytes = 10),
                task("2", type = "movie", state = TaskState.DOWNLOADED, total = 5, bytes = 5),
                task("3", type = "track", state = TaskState.DOWNLOADING, bytes = 2, total = 9),
                task("4", type = "audiobook", state = TaskState.QUEUED),
            ),
        )
        assertEquals(mapOf(OfflineShelf.TV to 10L, OfflineShelf.MOVIES to 5L, OfflineShelf.MUSIC to 2L), by)
    }

    // ── grouping and format ──

    @Test
    fun groupsFoldSeasonsIntoShowsAndKeepMoviesAlone() {
        val tasks = listOf(
            task("1", group = "12").copy(groupType = "season", groupTitle = "Severance", sortIndex = 2005),
            task("2", group = "12").copy(groupType = "show", groupTitle = "Severance", sortIndex = 2004),
            task("9", type = "movie"),
            task("3", type = "track").copy(parentId = "55", subtitle = "Blue"),
        )
        val groups = OfflineGrouping.groups(tasks)
        val show = groups.first { it.groupType == "show" }
        assertEquals(listOf("2", "1"), show.tasks.map { it.itemId })
        assertEquals("Severance", show.title)
        assertEquals("t9", groups.first { it.groupType == "movie" }.title)
        assertEquals("album", groups.first { it.shelf == OfflineShelf.MUSIC }.groupType)
        assertEquals("S2 E5", OfflineGrouping.episodeLabel(2005))
        assertNull(OfflineGrouping.episodeLabel(7))
    }

    @Test
    fun sectionsFileByKindInTheScreensOrderWithLooseSongsTogether() {
        val done = TaskState.DOWNLOADED
        val tasks = listOf(
            task("h", type = "home", state = done, completed = 1),
            task("p1", type = "podcast_episode", state = done, completed = 1).copy(groupType = "podcast", groupId = "3", groupTitle = "Pod"),
            task("b", type = "audiobook", state = done, completed = 1).copy(groupType = "audiobook", groupId = "b", groupTitle = "Dune"),
            task("1", type = "track", state = done, completed = 50).copy(groupType = "playlist", groupId = "user:4", groupTitle = "Road Trip"),
            task("2", type = "track", state = done, completed = 10).copy(groupType = "album", groupId = "9", groupTitle = "Abbey Road"),
            task("3", type = "track", state = done, completed = 30).copy(groupType = "track", groupId = "3"),
            task("4", type = "track", state = done, completed = 40),
            task("y", type = "youtube", state = done, completed = 1),
            task("9", type = "movie", state = done, completed = 1),
            task("e1", state = done, completed = 1, group = "12").copy(groupType = "season", groupTitle = "Severance", sortIndex = 2001),
            task("e2", state = done, completed = 1, group = "12").copy(groupType = "season", groupTitle = "Severance", sortIndex = 1003),
            task("e3", state = done, completed = 1, group = "12").copy(groupType = "season", groupTitle = "Severance", sortIndex = 5),
        )
        val sections = OfflineGrouping.sections(tasks)
        assertEquals(
            listOf("TV shows", "Movies", "YouTube", "Music", "Audiobooks", "Podcasts", "Home videos"),
            sections.map { it.label },
        )
        val music = sections.first { it.shelf == OfflineShelf.MUSIC }
        // newest download first; songs on their own share one card, newest first inside
        assertEquals(listOf("Road Trip", "Songs", "Abbey Road"), music.groups.map { it.title })
        assertEquals(listOf("4", "3"), music.groups[1].tasks.map { it.itemId })
        assertEquals(4, music.count)
        assertEquals(listOf("Abbey Road", "Road Trip", "Songs"), OfflineGrouping.sections(tasks, OfflineSort.AZ).first { it.shelf == OfflineShelf.MUSIC }.groups.map { it.title })
        val show = sections.first().groups.single()
        assertEquals(listOf("Season 1", "Season 2", "Episodes"), OfflineGrouping.seasons(show).map { it.first })
        assertEquals(listOf(OfflineShelf.MOVIES), OfflineGrouping.sections(listOf(task("9", type = "movie"))).map { it.shelf })
    }

    @Test
    fun reportSaysWhenADownloadFinished() {
        val items = OfflineReconcile.reportItems(
            listOf(
                task("1", type = "movie", state = TaskState.DOWNLOADED, completed = 1_790_000_000_000),
                task("2", type = "movie", state = TaskState.QUEUED, completed = 0),
            ),
        )
        assertEquals(1_790_000_000_000L, items[0].downloadedAt)
        assertNull(items[1].downloadedAt)
    }

    @Test
    fun formatsBytesAndSpeeds() {
        assertEquals("4.1 GB", OfflineFormat.bytes((4.1 * gb).toLong()))
        assertEquals("690 MB", OfflineFormat.bytes(690L * 1024 * 1024))
        assertEquals("6.1 MB/s", OfflineFormat.speed((6.1 * 1024 * 1024).toLong()))
        assertEquals("720p", OfflineFormat.qualityTag("phone"))
    }

    // ── migration ──

    @Test
    fun migrationBringsManifestItemsAndOrphanFiles() {
        val manifest = OfflineMigration.parseManifest(
            """[{"key":"movie:7","itemType":"movie","itemId":"7","title":"Dune","artworkUrl":"https://10-0-0-2.x.ssync.net:8008/api/video/poster/movie/7","isVideo":true,"localPath":"/data/soulsync_offline/movie_7.media","sizeBytes":100,"downloadedAtMs":42},
               {"key":"track:3","itemType":"track","itemId":"3","title":"Gone","localPath":"/data/soulsync_offline/track_3.media"}]""",
        )
        assertEquals(2, manifest.size)
        val files = listOf(
            OfflineMigration.LegacyFile("movie_7.media", "/data/soulsync_offline/movie_7.media", 4000, 10),
            OfflineMigration.LegacyFile("podcast_episode_abc_1.media", "/data/soulsync_offline/podcast_episode_abc_1.media", 50, 11),
            OfflineMigration.LegacyFile("episode_812.media.part", "/data/soulsync_offline/episode_812.media.part", 5, 12),
            OfflineMigration.LegacyFile("manifest.json", "/data/soulsync_offline/manifest.json", 5, 12),
        )
        val tasks = OfflineMigration.migrate(manifest, files)
        // track_3 has no file any more: dropped. the .part never finished: not a download
        assertEquals(listOf("movie:7", "podcast_episode:abc_1"), tasks.map { it.key })
        val movie = tasks[0]
        assertEquals("Dune", movie.title)
        assertEquals(TaskState.DOWNLOADED, movie.state)
        assertEquals(4000L, movie.diskBytes)
        assertEquals("/api/video/poster/movie/7", movie.artwork)
        assertFalse(movie.needsMeta)
        assertTrue(tasks[1].needsMeta)
        assertFalse(tasks[1].isVideo)
        assertEquals("android-pixel-8-pro", OfflineMigration.legacyDeviceId("Pixel 8 Pro"))
    }

    @Test
    fun migrationReadsFileNamesAndSurvivesJunk() {
        assertEquals("episode" to "812", OfflineMigration.parseFileName("episode_812.media"))
        assertEquals("podcast_episode" to "x_y", OfflineMigration.parseFileName("podcast_episode_x_y.media"))
        assertNull(OfflineMigration.parseFileName("weird_1.media"))
        assertNull(OfflineMigration.parseFileName("movie_.media"))
        assertTrue(OfflineMigration.parseManifest("not json").isEmpty())
        assertEquals(1, OfflineMigration.parseRemovals("""[{"itemType":"movie","itemId":"1"}]""").size)
    }

    @Test
    fun tasksRoundTripThroughTheStoreCodec() {
        val tasks = listOf(task("1", state = TaskState.PREPARING).copy(prepareId = "p", etag = "\"e\""))
        assertEquals(tasks, OfflineCodec.decodeTasks(OfflineCodec.encodeTasks(tasks)))
        assertTrue(OfflineCodec.decodeTasks("{broken").isEmpty())
    }
}

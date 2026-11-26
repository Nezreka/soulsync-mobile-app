package net.ssync.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNotificationsTest {

    private fun n(id: Long, kind: String = "new_episode", read: Boolean = false) =
        PlayerNotification(id = id, kind = kind, title = "t$id", read = read)

    private fun feed(vararg items: PlayerNotification, latest: Long? = null) =
        NotificationFeed(items.toList(), items.count { !it.read }, latest ?: items.maxOfOrNull { it.id } ?: 0)

    // ── parsing ──

    @Test
    fun feedParsesTheContractShape() {
        val f = NotificationsJson.parseFeed(
            """{"notifications":[{"id":7,"kind":"featured_movie","title":"Dune","body":"in your library",
               "image":"/api/video/poster/movie/3","link":"/player/movies/3","created_at":"2026-11-25T10:00:00Z","read":false},
               {"id":"6","kind":"new_episode","title":"x","read":true}],"unread":4,"latest_id":9}""",
        )
        assertEquals(2, f.notifications.size)
        assertEquals(4, f.unread)
        assertEquals(9L, f.latestId)
        val first = f.notifications[0]
        assertEquals(7L, first.id)
        assertEquals("/player/movies/3", first.link)
        assertEquals("/api/video/poster/movie/3", first.image)
        assertFalse(first.read)
        assertEquals(6L, f.notifications[1].id)
        assertTrue(f.notifications[1].read)
    }

    @Test
    fun feedToleratesMissingFieldsAndJunk() {
        val f = NotificationsJson.parseFeed("""{"notifications":[{"title":"no id"},{"id":3,"image":"","link":null}]}""")
        assertEquals(1, f.notifications.size)
        assertNull(f.notifications[0].image)
        assertNull(f.notifications[0].link)
        assertEquals(3L, f.latestId)
        assertEquals(1, f.unread)
        assertEquals(0, NotificationsJson.parseFeed("[]").notifications.size)
    }

    @Test
    fun readAtCountsAsRead() {
        val f = NotificationsJson.parseFeed("""{"notifications":[{"id":1,"read_at":"2026-11-25 10:00:00"}]}""")
        assertTrue(f.notifications[0].read)
    }

    @Test
    fun kindsParseAndAdminMessagesAreLocked() {
        val env = NotificationsJson.parsePrefsEnvelope(
            """{"prefs":{},"kinds":[{"id":"new_episode","label":"New episodes","help":"h","default":true},
               {"id":"leaving_soon","label":"Leaving soon","default":false},
               {"id":"admin_message","label":"Admin"}]}""",
        )
        assertEquals(listOf("new_episode", "leaving_soon", "admin_message"), env.kinds.map { it.id })
        assertFalse(env.kinds[1].default)
        assertTrue(env.kinds[2].locked)
        assertFalse(env.kinds[0].locked)
        // a locked kind is on even when prefs say off
        val prefs = NotificationPrefs(kinds = mapOf("admin_message" to false))
        assertTrue(prefs.enabled(env.kinds[2]))
    }

    @Test
    fun noKindsFromTheServerFallsBackToTheKnownOnes() {
        val env = NotificationsJson.parsePrefsEnvelope("""{"prefs":{}}""")
        assertEquals(11, env.kinds.size)
        assertTrue(env.kinds.first { it.id == "admin_message" }.locked)
    }

    @Test
    fun prefsParseTheServersFlatShape() {
        val p = NotificationsJson.parsePrefs(
            Json.parseToJsonElement(
                """{"kinds":{"new_episode":false,"featured_movie":true,"new_music":"1","bad":{"x":1}},
                   "featured_not_in_library":true,"memories_time":"7:30","quiet_hours":true,"quiet_start":"23:00",
                   "quiet_end":"25:00","digest":true,"digest_time":"nonsense","timezone":"America/Chicago"}""",
            ),
        )
        assertEquals(mapOf("new_episode" to false, "featured_movie" to true, "new_music" to true), p.kinds)
        assertTrue(p.featuredNotInLibrary)
        assertEquals("07:30", p.memoriesTime)
        assertTrue(p.quietHours)
        assertEquals("23:00", p.quietStart)
        assertEquals("08:00", p.quietEnd) // a bad time keeps the default
        assertTrue(p.digest)
        assertEquals("18:00", p.digestTime)
        assertEquals("America/Chicago", p.timezone)
    }

    @Test
    fun aDefaultKindIsOnUntilSwitchedOff() {
        val k = NotificationKindInfo("new_episode", "New episodes", default = true)
        assertTrue(NotificationPrefs().enabled(k))
        assertFalse(NotificationPrefs(kinds = mapOf("new_episode" to false)).enabled(k))
        assertFalse(NotificationPrefs().enabled(k.copy(default = false)))
    }

    @Test
    fun aSaveSendsOnlyWhatChanged() {
        val before = NotificationPrefs(kinds = mapOf("new_episode" to true, "new_music" to false))
        assertTrue(before.changesFrom(before).isEmpty())
        val after = before.copy(kinds = before.kinds + ("new_music" to true), quietHours = true, quietStart = "21:00")
        val body = after.changesFrom(before)
        assertEquals(setOf("kinds", "quiet_hours", "quiet_start"), body.keys)
        assertEquals(JsonObject(mapOf("new_music" to JsonPrimitive(true))), body["kinds"])
        assertEquals(JsonPrimitive(true), body["quiet_hours"])
        assertEquals(JsonPrimitive("21:00"), body["quiet_start"])
        // every key it can send is one the server knows (it refuses the rest)
        val all = NotificationPrefs(
            kinds = mapOf("x" to true), featuredNotInLibrary = true, memoriesTime = "10:00", quietHours = true,
            quietStart = "20:00", quietEnd = "06:00", digest = true, digestTime = "19:00", timezone = "UTC",
        ).changesFrom(NotificationPrefs())
        assertEquals(
            setOf("kinds", "featured_not_in_library", "memories_time", "quiet_hours", "quiet_start", "quiet_end",
                "digest", "digest_time", "timezone"),
            all.keys,
        )
    }

    @Test
    fun theServersKindsCatalogueParses() {
        val env = NotificationsJson.parsePrefsEnvelope(
            """{"success":true,"prefs":{"kinds":{"download_ready":true}},"kinds":[
               {"id":"download_ready","label":"Downloads ready","help":"h","default":true,"device":"phone"},
               {"id":"admin_message","label":"Messages from the admin","help":"h","default":true,"locked":true}]}""",
        )
        assertEquals(2, env.kinds.size)
        assertTrue(env.kinds[1].locked)
        assertTrue(env.prefs.kinds["download_ready"] == true)
    }

    // ── since-id bookkeeping ──

    @Test
    fun theFirstCheckOnlySetsTheCursor() {
        val plan = NotificationCursor.plan(null, feed(n(3), n(4), n(5)))
        assertTrue(plan.toPost.isEmpty())
        assertEquals(5L, plan.nextCursor)
    }

    @Test
    fun laterChecksPostWhatIsNewAndUnreadOldestFirst() {
        val plan = NotificationCursor.plan(4L, feed(n(7), n(4), n(5, read = true), n(6), n(3)))
        assertEquals(listOf(6L, 7L), plan.toPost.map { it.id })
        assertEquals(7L, plan.nextCursor)
    }

    @Test
    fun theCursorFollowsLatestIdEvenWhenNothingCame() {
        val plan = NotificationCursor.plan(10L, NotificationFeed(emptyList(), 0, 12L))
        assertTrue(plan.toPost.isEmpty())
        assertEquals(12L, plan.nextCursor)
        // and never goes backwards on a quiet feed
        assertEquals(10L, NotificationCursor.plan(10L, NotificationFeed(emptyList(), 0, 10L)).nextCursor)
    }

    @Test
    fun aResetServerStartsOverQuietly() {
        val plan = NotificationCursor.plan(500L, feed(n(1), n(2)))
        assertTrue(plan.toPost.isEmpty())
        assertEquals(2L, plan.nextCursor)
    }

    @Test
    fun duplicatesPostOnce() {
        val plan = NotificationCursor.plan(1L, feed(n(2), n(2), n(3)))
        assertEquals(listOf(2L, 3L), plan.toPost.map { it.id })
    }

    // ── grouping ──

    @Test
    fun groupsPerKindNewestFirstAndCapped() {
        val items = listOf(n(1, "new_episode"), n(2, "featured_movie"), n(3, "new_episode"), n(4, "new_episode"),
            n(5, "new_episode"), n(6, "new_episode"))
        val groups = NotificationGrouping.plan(items, maxPerKind = 3)
        assertEquals(listOf("new_episode", "featured_movie"), groups.map { it.kind })
        val eps = groups[0]
        assertEquals(listOf(6L, 5L, 4L), eps.shown.map { it.id })
        assertEquals(5, eps.total)
        assertTrue(eps.needsSummary)
        assertFalse(groups[1].needsSummary)
    }

    @Test
    fun nothingToGroup() {
        assertTrue(NotificationGrouping.plan(emptyList()).isEmpty())
    }

    // ── channels ──

    @Test
    fun channelIdsAreStableAndSafe() {
        assertEquals("ssn_new_episode", NotificationChannels.channelId("new_episode"))
        assertEquals("ssn_digest", NotificationChannels.channelId("digest"))
        assertEquals("Daily digest", NotificationChannels.fallbackLabel("digest"))
        assertEquals("ssn_weird_kind_", NotificationChannels.channelId("Weird-Kind!"))
        assertEquals("ssn_other", NotificationChannels.channelId(""))
        assertEquals("net.ssync.notify.ssn_party_invite", NotificationChannels.groupKey("party_invite"))
    }

    @Test
    fun everyKnownKindHasItsOwnChannel() {
        val ids = NotificationChannels.KNOWN.map { NotificationChannels.channelId(it.id) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("New episodes", NotificationChannels.fallbackLabel("new_episode"))
        assertEquals("Some new kind", NotificationChannels.fallbackLabel("some_new_kind"))
    }

    @Test
    fun systemIdsAndSummaryIdsNeverMeet() {
        val ids = listOf(1L, 2L, 999_999L, Long.MAX_VALUE, 0x1FFFFFFFL)
        ids.forEach { id ->
            val sys = NotificationChannels.systemId(id)
            assertTrue(sys in 10_000 until 0x40000000)
        }
        NotificationChannels.KNOWN.forEach { k ->
            assertTrue(NotificationChannels.summaryId(k.id) >= 0x40000000)
        }
        assertEquals(NotificationChannels.systemId(5), NotificationChannels.systemId(5))
    }

    // ── when ──

    @Test
    fun timeAgoReadsIsoAndEpoch() {
        val now = NotificationTime.parse("2026-11-26T12:00:00Z")!!
        assertEquals("just now", NotificationTime.ago("2026-11-26T11:59:30Z", now))
        assertEquals("5m", NotificationTime.ago("2026-11-26 11:55:00", now))
        assertEquals("3h", NotificationTime.ago("2026-11-26T09:00:00+00:00", now))
        assertEquals("2d", NotificationTime.ago(((now - 2 * 86_400_000L) / 1000).toString(), now))
        // the server's created_at: epoch seconds as a float
        assertEquals("3h", NotificationTime.ago("%.3f".format(java.util.Locale.US, (now - 3 * 3_600_000L) / 1000.0), now))
        assertEquals("", NotificationTime.ago("", now))
        assertEquals("", NotificationTime.ago("yesterday", now))
    }
}

package net.ssync.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OfflineApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OfflineApiClient(server.url("/").toString(), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun planDecodesItemsQualitiesAndNumericIds() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"video","title":"Severance","subtitle":"Season 2","items":[
                {"item_type":"episode","item_id":812,"title":"Woe's Hollow","duration_s":3120.5,"size_bytes":"3000000000",
                 "source":{"container":"mkv","video_codec":"hevc","height":2160,"hdr":true},"on_device":true,"watched":false,"position_s":null}],
                "qualities":[{"id":"phone","label":"Phone","sub":"720p","total_bytes":1.2e9,"per_item_bytes":{"episode:812":1200000000}}]}""",
            ),
        )
        val plan = (api.plan("season", "12:2", "dev-1") as MusicApiResult.Success).data
        val req = server.takeRequest()
        assertEquals("/api/player/offline/plan?type=season&id=12%3A2&device=dev-1", req.path)
        assertTrue(plan.isVideo)
        val item = plan.items.single()
        assertEquals("812", item.itemId)
        assertEquals("episode:812", item.key)
        assertEquals(3_000_000_000L, item.sizeBytes)
        assertEquals(2160, item.source?.height)
        assertTrue(item.onDevice)
        assertEquals(1_200_000_000L, plan.qualities.single().totalBytes)
        assertEquals(1_200_000_000L, plan.qualities.single().perItemBytes["episode:812"])

        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"kind":"video","title":"Severance","items":[],"qualities":[],"conversion_off":true,
                "group":{"type":"show","id":"12"},"rule":{"rule_id":3,"type":"show","id":"12","keep_next":3},"free_bytes":100,"limit_bytes":200}""",
            ),
        )
        val extras = (api.plan("show", "12", "dev-1") as MusicApiResult.Success).data
        assertTrue(extras.conversionOff)
        assertEquals("3", extras.rule?.ruleId)
        assertEquals(200L, extras.limitBytes)
    }

    @Test
    fun prepareAndPollShareOneShape() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":44,"status":"converting","progress":0.4,"url":null,"size_bytes":0,"error":null}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"id":"44","status":"ready","progress":1,"url":"/api/player/offline/file/44","size_bytes":123,"filename":"Dune (2024) [720p].mp4"}"""))
        val first = (api.prepare("episode", "812", "phone") as MusicApiResult.Success).data
        assertEquals("44", first.id)
        assertEquals("converting", first.status)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"quality\":\"phone\""))
        val second = (api.pollPrepare("44") as MusicApiResult.Success).data
        assertEquals("/api/player/offline/prepare/44", server.takeRequest().path)
        assertEquals(api.base + "/api/player/offline/file/44", api.absolute(second.url!!))
        assertEquals("Dune (2024) [720p].mp4", second.filename)
    }

    @Test
    fun errorsCarryTheServersWords() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"downloads are off for this profile"}"""))
        // a refusal is a failed prepare (not retried), a busy server an error (retried)
        val res = (api.prepare("movie", "1", "original") as MusicApiResult.Success).data
        assertEquals("failed", res.status)
        assertEquals("downloads are off for this profile", res.error)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(api.pollPrepare("1") is MusicApiResult.Error)
        assertEquals("The server is busy. It'll try again.", OfflineApiClient.serverMessage(503, "<html>"))
        // the server's words win over its code; a bare code still reads plainly
        assertEquals("Ask an admin", OfflineApiClient.serverMessage(403, """{"success":false,"error":"downloads_disabled","message":"Ask an admin"}"""))
        assertEquals("Downloads are turned off for this profile.", OfflineApiClient.serverMessage(403, """{"success":false,"error":"downloads_disabled"}"""))
    }

    @Test
    fun wantedDevicesReportRulesAndSync() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"add":[{"item_type":"episode","item_id":9,"quality":"phone","reason":"rule","title":"E9"}],"remove":[{"item_type":"movie","item_id":"3","reason":"watched"}]}""",
            ),
        )
        val wanted = (api.wanted("android phone 1") as MusicApiResult.Success).data
        assertEquals("/api/player/offline/devices/android%20phone%201/wanted", server.takeRequest().path)
        assertEquals("episode:9", wanted.add.single().key)
        assertEquals("watched", wanted.remove.single().reason)

        server.enqueue(
            MockResponse().setBody(
                """{"devices":[{"device_id":"d1","device_name":"Pixel 8","platform":"android","free_bytes":10,"limit_bytes":25,"used_bytes":5,"wifi_only":true,
                "items":[{"item_type":"episode","item_id":9,"state":"downloaded","group":"Severance"}],
                "rules":[{"rule_id":7,"type":"show","id":12,"keep_next":3,"delete_watched":true}]}]}""",
            ),
        )
        val devices = (api.devices() as MusicApiResult.Success).data
        server.takeRequest()
        assertEquals("Severance", devices.single().items.single().groupRef()?.title)
        assertEquals("7", devices.single().rules.single().ruleId)
        assertEquals(3, devices.single().rules.single().keepNext)

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api.report(OfflineDeviceReport("d1", "Pixel", "android", 1, 2, 3, true, emptyList()))
        val report = server.takeRequest().body.readUtf8()
        assertTrue(report.contains("\"device_id\":\"d1\"") && report.contains("\"wifi_only\":true"))

        server.enqueue(MockResponse().setBody("""{"rule":{"rule_id":"r5","type":"show","id":"12","keep_next":3}}"""))
        val saved = (api.saveRule(OfflineRule(deviceId = "d1", type = "show", id = "12", keepNext = 3)) as MusicApiResult.Success).data
        assertEquals("r5", saved?.ruleId)
        val ruleBody = server.takeRequest().body.readUtf8()
        assertTrue(ruleBody.contains("\"keep_next\":3") && ruleBody.contains("\"id\":\"12\""))

        server.enqueue(MockResponse().setBody("{}"))
        api.sync("d1", listOf(OfflineSyncEvent("watched", "episode", "9", 0.0, 0.0, "2026-11-25T00:00:00Z")))
        val sync = server.takeRequest()
        assertEquals("/api/player/offline/sync", sync.path)
        assertTrue(sync.body.readUtf8().contains("\"events\":[{\"kind\":\"watched\""))
    }
}

package net.ssync.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerSubtitlesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SubtitleApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = SubtitleApiClient(server.url("/").toString().trimEnd('/'), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun readsTracksWithAiAndDelay() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"audio":[],"chapters":[],"subtitles":[
                {"id":"s0","language":"eng","title":"Commentary","codec":"subrip","default":true,"forced":false,"sdh":false,"external":false,"kind":"text","ai":false,"delay_ms":0},
                {"id":"s1","language":"eng","codec":"hdmv_pgs_subtitle","default":false,"forced":false,"external":false,"kind":"image","ai":false,"delay_ms":0},
                {"id":"x0","language":"en","title":null,"codec":"subrip","default":false,"forced":false,"sdh":false,"external":true,"kind":"text","ai":true,"low_confidence":true,"delay_ms":-1200},
                {"id":"x1","language":"fr","codec":"subrip","external":true,"kind":"text","delay_ms":99999}
                ]}""",
            ),
        )
        val subs = (client.tracks("movie", "12") as MusicApiResult.Success).data
        assertTrue(server.takeRequest().path!!.startsWith("/api/player/tracks/movie/12"))
        assertEquals(4, subs.size)
        val ai = subs[2]
        assertTrue(ai.ai && ai.lowConfidence && ai.external && ai.isText)
        assertEquals(-1200, ai.delayMs)
        assertEquals("English (AI)", SubtitleLanguages.label(ai))
        assertEquals("low confidence", SubtitleLanguages.detail(ai))
        assertEquals("English · Commentary", SubtitleLanguages.label(subs[0]))
        assertFalse(subs[1].isText)
        // the server clamps, and so does the app
        assertEquals(SubtitleDelay.MAX_MS, subs[3].delayMs)
        assertEquals("French", SubtitleLanguages.label(subs[3]))
        assertTrue(SubtitleLanguages.same("eng", "en"))
        assertTrue(SubtitleLanguages.same("ger", "de"))
        assertFalse(SubtitleLanguages.same("en", "fr"))
    }

    @Test
    fun readsJobsAndPicksTheLanguage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jobs":[
                {"id":7,"video_kind":"movie","video_id":12,"language":"fr","state":"failed","progress":0.2,"error":"the model crashed"},
                {"id":9,"video_kind":"movie","video_id":12,"language":"en","state":"queued","progress":0,"stage":null,"eta_seconds":null,"position":2}
                ],"available":true,"why":null,"watching":false}""",
            ),
        )
        val page = (client.aiJobs("movie", 12) as MusicApiResult.Success).data
        assertEquals("/api/video/subtitles/ai/jobs?kind=movie&item_id=12", server.takeRequest().path)
        assertTrue(page.available)
        val en = page.forLanguage("eng")!!
        assertEquals(9L, en.id)
        assertTrue(en.isActive)
        assertEquals("2nd in line", en.statusLine())
        val fr = page.forLanguage("fr")!!
        assertEquals("the model crashed", fr.problem)
        assertFalse(fr.isActive)

        val running = AiSubtitleJob(id = 1, state = "running", progress = 0.42, etaSeconds = 360, position = 1)
        assertEquals("Making subtitles · 42% · about 6 min left", running.statusLine())
        assertEquals("Next in line", AiSubtitleJob(id = 1, state = "queued", position = 1).statusLine())
        assertEquals("11th in line", AiSubtitleJob(id = 1, state = "queued", position = 11).statusLine())
        assertEquals("less than a minute left", AiSubtitleJob.etaText(20))
        assertEquals("about 1 h 40 min left", AiSubtitleJob.etaText(6000))
        assertNull(AiSubtitleJob.etaText(null))
        assertEquals("The audio isn't in that language.", AiSubtitleJob(id = 1, state = "no_match").problem)
    }

    @Test
    fun startsAJobAndShowsTheServersWordsWhenItCant() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"job":{"id":3,"video_kind":"episode","video_id":5,"language":"en","state":"queued","progress":0,"position":1}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"error":"unavailable","message":"Whisper isn't set up on this server."}""",
            ),
        )
        val job = (client.startAiJob("episode", 5, "en") as MusicApiResult.Success).data
        assertEquals(3L, job.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"origin\":\"player\"") && body.contains("\"item_id\":5") && body.contains("\"kind\":\"episode\""))
        val err = client.startAiJob("episode", 5, "en") as MusicApiResult.Error
        assertEquals("Whisper isn't set up on this server.", err.message)
    }

    @Test
    fun savesTheDelayClamped() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"delay_ms":-30000}"""))
        val saved = (client.saveDelay("movie", "12", "x0", -45000) as MusicApiResult.Success).data
        assertEquals(-30000, saved)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/player/subtitle-delay/movie/12?sub=x0", req.path)
        assertEquals("""{"delay_ms":-30000}""", req.body.readUtf8())
    }

    @Test
    fun parsesWebVtt() {
        val vtt = "﻿WEBVTT\r\n\r\nNOTE made by ffmpeg\r\n\r\n1\r\n00:00:01.000 --> 00:00:02.500 align:start\r\n<i>Hello</i> &amp; welcome\r\n\r\n" +
            "00:03.000 --> 00:04,000\nSecond\nline two\n\n00:00:05.000 --> 00:00:04.000\nbackwards, dropped\n\n00:00:02.000 --> 00:00:06.000\noverlap\n"
        val cues = WebVtt.parse(vtt)
        assertEquals(3, cues.size)
        assertEquals(SubtitleCue(1000, 2500, "Hello & welcome"), cues[0])
        assertEquals(SubtitleCue(3000, 4000, "Second\nline two"), cues[2])
        assertEquals(listOf("Hello & welcome", "overlap"), WebVtt.activeAt(cues, 2200).map { it.text })
        assertEquals(listOf("overlap", "Second\nline two"), WebVtt.activeAt(cues, 3500).map { it.text })
        assertTrue(WebVtt.activeAt(cues, 500).isEmpty())
        assertTrue(WebVtt.activeAt(cues, 6000).isEmpty())
        assertEquals(3_723_456L, WebVtt.time("01:02:03.456"))
    }

    @Test
    fun delayShiftsTheClockAndClamps() {
        // a subtitle shown 1.2 s later: at 5 s the picture shows the line due at 3.8 s
        assertEquals(3800L, SubtitleDelay.subtitleClock(5000, 1200))
        assertEquals(6200L, SubtitleDelay.subtitleClock(5000, -1200))
        assertEquals(30_000, SubtitleDelay.clamp(31_000))
        assertEquals(-30_000, SubtitleDelay.clamp(-99_000))
        assertEquals("In sync", SubtitleDelay.describe(0))
        assertEquals("0.3 s later", SubtitleDelay.describe(300))
        assertEquals("1.2 s earlier", SubtitleDelay.describe(-1200))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun debouncerSavesOnceNudgingStops() = runTest {
        val saved = mutableListOf<Pair<String, Int>>()
        val d = DelaySaveDebouncer(this, waitMs = 600L) { sub, ms -> saved.add(sub to ms) }
        d.push("x0", 100)
        advanceTimeBy(300)
        d.push("x0", 200)
        advanceTimeBy(300)
        d.push("x0", 300)
        advanceTimeBy(599)
        runCurrent()
        assertTrue(saved.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("x0" to 300), saved)

        // another subtitle saves the waiting one at once
        d.push("x0", 400)
        d.push("x1", -100)
        runCurrent()
        assertEquals("x0" to 400, saved.last())
        d.flush()
        runCurrent()
        assertEquals("x1" to -100, saved.last())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(3, saved.size)
    }
}

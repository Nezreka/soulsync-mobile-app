package net.ssync.app.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineCheckTest {
    private val lan = "https://192-168-1-5.ss.example:8443"
    private val wan = "https://wan.ss.example:8443"

    @Test
    fun oneMissNeverTakesTheAppOffline() {
        val c = OnlineCheck(strikesToOffline = 2)
        assertTrue(c.decide(reached = false, wasOnline = true))
        assertFalse(c.decide(reached = false, wasOnline = true))
        // stays offline while misses go on
        assertFalse(c.decide(reached = false, wasOnline = false))
    }

    @Test
    fun aMissThenAnAnswerStartsTheCountAgain() {
        val c = OnlineCheck(strikesToOffline = 2)
        assertTrue(c.decide(false, true))
        assertTrue(c.decide(true, true))
        assertEquals(0, c.misses)
        assertTrue(c.decide(false, true))
    }

    @Test
    fun oneAnswerBringsItBackOnline() {
        val c = OnlineCheck(strikesToOffline = 2)
        c.decide(false, true)
        assertFalse(c.decide(false, true))
        assertTrue(c.decide(true, false))
        assertEquals(0, c.misses)
    }

    @Test
    fun theCurrentAddressAnsweringSkipsTheRace() = runBlocking {
        var raced = false
        val r = OnlineCheck().look(lan, probeCurrent = { true }, raceAll = { raced = true; wan })
        assertTrue(r.reached)
        assertNull(r.switchTo)
        assertFalse(raced)
    }

    @Test
    fun leftHomeTheRacePicksTheAddressThatAnswers() = runBlocking {
        val reachable = setOf(wan)
        val r = OnlineCheck().look(
            lan,
            probeCurrent = { lan in reachable },
            raceAll = { listOf(lan, wan).firstOrNull { it in reachable } + "/" },
        )
        assertTrue(r.reached)
        assertEquals(wan, r.switchTo)
    }

    @Test
    fun theRaceWinningOnTheSameAddressIsNoSwitch() = runBlocking {
        val r = OnlineCheck().look(lan, probeCurrent = { false }, raceAll = { lan })
        assertTrue(r.reached)
        assertNull(r.switchTo)
    }

    @Test
    fun nothingAnswersIsAMiss() = runBlocking {
        val c = OnlineCheck()
        val r = c.look(lan, probeCurrent = { false }, raceAll = { null })
        assertFalse(r.reached)
        assertTrue(c.decide(r.reached, true))
        val r2 = c.look(lan, probeCurrent = { false }, raceAll = { null })
        assertFalse(c.decide(r2.reached, true))
    }

    @Test
    fun noClientYetStillRaces() = runBlocking {
        val r = OnlineCheck().look(null, probeCurrent = { error("no client to probe") }, raceAll = { wan })
        assertTrue(r.reached)
        assertEquals(wan, r.switchTo)
    }

    @Test
    fun checkedAgoReadsPlainly() {
        assertEquals("just now", checkedAgo(20_000))
        assertEquals("3 min ago", checkedAgo(3 * 60_000L + 5_000))
        assertEquals("2 h ago", checkedAgo(2 * 3600_000L))
    }
}

package net.ssync.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerLaunchTest {

    @Test
    fun watchUrlFromAYoutubeKey() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", TrailerLaunch.watchUrl("dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=a-b_C1", TrailerLaunch.watchUrl("  a-b_C1 "))
    }

    @Test
    fun blankOrOddKeysGiveNoUrl() {
        assertNull(TrailerLaunch.watchUrl(null))
        assertNull(TrailerLaunch.watchUrl(""))
        assertNull(TrailerLaunch.watchUrl("   "))
        assertNull(TrailerLaunch.watchUrl("abc&list=x"))
        assertNull(TrailerLaunch.watchUrl("../evil"))
    }

    @Test
    fun phoneTriesTheYoutubeAppThenTheBrowser() {
        assertEquals(listOf(TrailerLaunch.YOUTUBE_APP, null), TrailerLaunch.targets(isTv = false))
    }

    @Test
    fun tvTriesTheYoutubeTvAppFirst() {
        assertEquals(
            listOf(TrailerLaunch.YOUTUBE_TV_APP, TrailerLaunch.YOUTUBE_APP, null),
            TrailerLaunch.targets(isTv = true),
        )
    }
}

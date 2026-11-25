package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the same cases as the web player's nav-order.test.ts */
class NavOrderTest {
    private val items = listOf("/player", "/player/movies", "lib:5", "/player/tv", "/player/youtube")

    @Test
    fun `the usual order until one is saved`() {
        assertEquals(items, NavOrder.arrange(items, emptyList(), emptyList()))
    }

    @Test
    fun `saved places first in their order, the rest after in their usual order`() {
        assertEquals(
            listOf("/player/tv", "lib:5", "/player", "/player/movies", "/player/youtube"),
            NavOrder.arrange(items, listOf("/player/tv", "lib:5"), emptyList()),
        )
    }

    @Test
    fun `a saved place the player no longer has is skipped`() {
        assertEquals("/player/youtube", NavOrder.arrange(items, listOf("lib:99", "/player/youtube"), emptyList()).first())
    }

    @Test
    fun `hidden places are left out, but never home`() {
        assertEquals(
            listOf("/player", "/player/movies", "/player/youtube"),
            NavOrder.arrange(items, emptyList(), listOf("/player/tv", "/player", "lib:5")),
        )
        assertTrue(NavOrder.shown("/player", listOf("/player")))
        assertFalse(NavOrder.shown("/player/guide", listOf("/player/guide")))
    }
}

package net.ssync.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsersHeaderLayoutTest {

    @Test
    fun phonesStackTheHeader() {
        // small phone, typical phone, big phone (portrait)
        assertTrue(UsersHeaderLayout.stacked(320f))
        assertTrue(UsersHeaderLayout.stacked(360f))
        assertTrue(UsersHeaderLayout.stacked(412f))
        // a phone in landscape with the drawer closed still stacks under the floor
        assertTrue(UsersHeaderLayout.stacked(539f))
    }

    @Test
    fun tabletsAndTvsKeepOneLine() {
        // tablet content width beside the sidebar, and a tv (960 wide less the rail)
        assertFalse(UsersHeaderLayout.stacked(600f))
        assertFalse(UsersHeaderLayout.stacked(850f))
        assertFalse(UsersHeaderLayout.stacked(882f))
    }

    @Test
    fun oneLineFitsTitleAndBothTabsAtTheirMinimum() {
        // the title needs about 230dp; the tabs and gaps the rest
        val needed = 230f + 2 * UsersHeaderLayout.TAB_MIN_WIDTH_DP + 3 * 10f + 6f
        assertTrue(UsersHeaderLayout.ONE_LINE_MIN_WIDTH_DP >= needed)
    }
}

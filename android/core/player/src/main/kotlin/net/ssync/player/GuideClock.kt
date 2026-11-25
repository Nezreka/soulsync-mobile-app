package net.ssync.player

import java.util.Calendar
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * the clock the Live Guide runs on: the server's.
 *
 * a channel's day starts at midnight and is seeded by the date. each device
 * used its OWN midnight and its own date, so a phone in another timezone (or
 * a server whose TZ isn't the house's) laid out a different guide from the
 * tv in the living room. the server hands its clock out with the day's pool
 * (core/player/guide_day.py clock()) and every day is counted on it. what a
 * device shows is still its own local time for those instants.
 *
 * the web does the same in webui/src/routes/player/-lib/schedule.ts
 * (setGuideClock): the two must agree, or the web and the app drift apart.
 */
object GuideClock {
    @Volatile
    private var zone: TimeZone? = null

    /** the server's zone by name, else its offset from utc; neither: this device's own */
    fun set(tz: String?, offsetMinutes: Int?) {
        val name = tz?.trim().orEmpty()
        zone = when {
            // getTimeZone() answers GMT for a name it doesn't know: ask first
            name.isNotEmpty() && TimeZone.getAvailableIDs().contains(name) -> TimeZone.getTimeZone(name)
            offsetMinutes != null -> SimpleTimeZone(offsetMinutes * 60_000, "guide")
            else -> null
        }
    }

    /** a calendar on the guide's clock */
    fun calendar(epochMs: Long): Calendar =
        Calendar.getInstance(zone ?: TimeZone.getDefault()).apply { timeInMillis = epochMs }

    /** midnight on the guide's clock, at or before an instant */
    fun startOfDayMs(epochMs: Long): Long = calendar(epochMs).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

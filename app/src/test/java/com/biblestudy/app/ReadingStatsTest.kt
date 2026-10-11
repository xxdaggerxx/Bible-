package com.biblestudy.app

import com.biblestudy.app.data.ChapterReading
import com.biblestudy.app.model.BookInfo
import com.biblestudy.app.ui.ReadingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadingStatsTest {
    private val books = listOf(BookInfo(1, "Genesis", "Gen", 50), BookInfo(43, "John", "John", 21))
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun streaksCountDaysWithAMinuteOrMore() {
        val days = listOf(
            Triple("2026-10-01", 600, 0), Triple("2026-10-02", 600, 0), Triple("2026-10-03", 600, 0), // 3 in a row
            Triple("2026-10-08", 30, 0), // under a minute: not a reading day
            Triple("2026-10-09", 300, 60), Triple("2026-10-10", 120, 0), // today and yesterday
        )
        val s = ReadingStats.compute(days, emptyList(), books, today)
        assertEquals(2, s.currentStreak)
        assertEquals(3, s.longestStreak)
        assertEquals(5, s.daysRead)
        assertEquals(120, s.todaySeconds)
        assertEquals(450, s.weekSeconds) // Oct 4 to 10
        assertEquals(60, s.studySeconds)
        assertEquals(30, s.last30.size)
        assertEquals(today, s.last30.last().first)
    }

    @Test
    fun aStreakCarriesFromYesterdayUntilTodayIsRead() {
        val s = ReadingStats.compute(listOf(Triple("2026-10-09", 300, 0)), emptyList(), books, today)
        assertEquals(1, s.currentStreak)
    }

    @Test
    fun chaptersReadAddUpByTestamentAndBook() {
        val chapters = listOf(
            ChapterReading(43, 3, 900, 4, 2, 0), ChapterReading(43, 1, 100, 1, 1, 0),
            ChapterReading(1, 1, 50, 1, 0, 0), // opened but not read
        )
        val s = ReadingStats.compute(emptyList(), chapters, books, today)
        assertEquals(2, s.chaptersRead)
        assertEquals(71, s.chapters)
        assertEquals(0, s.otRead)
        assertEquals(2, s.ntRead)
        assertEquals(2, s.timesRead[43003])
        assertEquals(43 to 3, s.topChapters.first().let { it.book to it.chapter })
        assertEquals(1000, s.books.first { it.book == 43 }.seconds)
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("40 s", ReadingStats.duration(40))
        assertEquals("12 min", ReadingStats.duration(725))
        assertEquals("1 h 5 min", ReadingStats.duration(3900))
    }

    @Test
    fun milestonesAreEarnedAndTheNearestIsNext() {
        val chapters = (1..21).map { ChapterReading(43, it, 600, 1, 1, 0) }
        val days = (0..7).map { Triple(today.minusDays(it.toLong()).toString(), 900, 0) }
        val s = ReadingStats.compute(days, chapters, books, today)
        val got = s.achievements.associate { it.id to it.earned }
        assertEquals(true, got["first"])
        assertEquals(true, got["week"]) // 8 days in a row
        assertEquals(true, got["ten"])
        assertEquals(true, got["book"]) // all of John
        assertEquals(false, got["hours"]) // 2 hours
        assertEquals(false, got["month"])
        // This list of books has only John in the New Testament, so the Gospels and the NT are done.
        assertEquals(21, s.achievements.single { it.id == "gospels" }.have)
        assertEquals(true, got["nt"])
        assertEquals(false, got["bible"])
        val next = s.nextGoal!!
        assertEquals(s.achievements.filter { !it.earned }.maxOf { it.progress }, next.progress, 0f)
        // Nothing read: nothing earned, and the first step is next.
        val none = ReadingStats.compute(emptyList(), emptyList(), books, today)
        assertTrue(none.achievements.none { it.earned })
    }
}

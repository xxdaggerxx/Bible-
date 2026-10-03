package com.biblestudy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.biblestudy.app.data.ChapterReading
import com.biblestudy.app.model.BookInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One book's reading (ANL-2, ANL-4). */
data class BookReading(val book: Int, val name: String, val chaptersRead: Int, val chapters: Int, val seconds: Int)

/** Everything the reading stats window shows, worked out from the saved counts (ANL-1 to ANL-5). */
data class ReadingStats(
    val chaptersRead: Int,
    val chapters: Int,
    val otRead: Int,
    val otChapters: Int,
    val ntRead: Int,
    val ntChapters: Int,
    val todaySeconds: Int,
    val weekSeconds: Int,
    val monthSeconds: Int,
    val totalSeconds: Int,
    val studySeconds: Int,
    val daysRead: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    /** Reading seconds for each of the last 30 days, oldest first. */
    val last30: List<Pair<LocalDate, Int>>,
    val books: List<BookReading>,
    val topChapters: List<ChapterReading>,
    /** Times read for every chapter, by book*1000+chapter. */
    val timesRead: Map<Int, Int>,
) {
    companion object {
        /** A day counts toward the streak with at least a minute of reading. */
        private const val DAY_MIN_S = 60

        fun compute(days: List<Triple<String, Int, Int>>, chapters: List<ChapterReading>, books: List<BookInfo>, today: LocalDate): ReadingStats {
            val byDay = days.associate { LocalDate.parse(it.first) to it.second }
            val read = chapters.filter { it.timesRead > 0 }
            val readSet = read.mapTo(HashSet()) { it.book * 1000 + it.chapter }
            fun secondsSince(from: LocalDate) = byDay.filterKeys { !it.isBefore(from) && !it.isAfter(today) }.values.sum()
            val readingDays = byDay.filterValues { it >= DAY_MIN_S }.keys.sorted()
            var longest = 0; var run = 0; var prev: LocalDate? = null
            for (d in readingDays) {
                run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
                longest = maxOf(longest, run); prev = d
            }
            // The current run counts today if read today, else carries from yesterday.
            var current = 0
            var d = if (today in readingDays) today else today.minusDays(1)
            val set = readingDays.toHashSet()
            while (d in set) { current++; d = d.minusDays(1) }
            val secondsByBook = chapters.groupBy { it.book }.mapValues { (_, l) -> l.sumOf { it.seconds } }
            val bookStats = books.map { b ->
                BookReading(b.id, b.name, (1..b.chapters).count { b.id * 1000 + it in readSet }, b.chapters, secondsByBook[b.id] ?: 0)
            }
            val ot = bookStats.filter { it.book < 40 }; val nt = bookStats.filter { it.book >= 40 }
            return ReadingStats(
                chaptersRead = readSet.size,
                chapters = bookStats.sumOf { it.chapters },
                otRead = ot.sumOf { it.chaptersRead }, otChapters = ot.sumOf { it.chapters },
                ntRead = nt.sumOf { it.chaptersRead }, ntChapters = nt.sumOf { it.chapters },
                todaySeconds = byDay[today] ?: 0,
                weekSeconds = secondsSince(today.minusDays(6)),
                monthSeconds = secondsSince(today.minusDays(29)),
                totalSeconds = days.sumOf { it.second },
                studySeconds = days.sumOf { it.third },
                daysRead = readingDays.size,
                currentStreak = current,
                longestStreak = longest,
                last30 = (29 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0) },
                books = bookStats,
                topChapters = chapters.filter { it.seconds > 0 }.sortedByDescending { it.seconds }.take(10),
                timesRead = read.associate { it.book * 1000 + it.chapter to it.timesRead },
            )
        }

        /** "1 h 5 min", "12 min" or "40 s". */
        fun duration(s: Int): String = when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min"
            else -> "$s s"
        }
    }
}

/** The single hue used for reading in the charts: light for a little, dark for a lot (sequential). */
private val READ_SHADES = listOf(Color(0xFFC8E6C9), Color(0xFF81C784), Color(0xFF43A047), Color(0xFF2E7D32))

/** How much of the Bible has been read, how often and for how long, and what's read most (ANL-1 to ANL-5). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingStatsDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    val stats by produceState<ReadingStats?>(null, vm.readingGeneration) {
        value = background {
            ReadingStats.compute(vm.user.readingDays(), vm.user.readingChapters(), vm.bible.books, LocalDate.now())
        }
    }
    BigDialog(onDismiss) {
        Column(Modifier.testTag("readingStats")) {
            DialogTitle("Reading stats", onDismiss)
            val s = stats ?: run { Text("Loading…"); return@Column }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (!vm.trackReading) {
                    Text("Counting is off. Turn it on in Settings → Reading.", color = MaterialTheme.colorScheme.error)
                }
                // Headline numbers.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("${percent(s.chaptersRead, s.chapters)}%", "of the Bible read", "${s.chaptersRead} of ${s.chapters} chapters")
                    StatTile(ReadingStats.duration(s.weekSeconds), "this week", "today ${ReadingStats.duration(s.todaySeconds)}")
                    StatTile("${s.currentStreak}", if (s.currentStreak == 1) "day in a row" else "days in a row", "longest ${s.longestStreak}")
                    StatTile(ReadingStats.duration(s.totalSeconds), "in all", "${s.daysRead} days · study ${ReadingStats.duration(s.studySeconds)}")
                }
                Section("Old and New Testament")
                Progress("Old Testament", s.otRead, s.otChapters)
                Progress("New Testament", s.ntRead, s.ntChapters)

                Section("The last 30 days")
                DaysChart(s.last30)

                Section("Most read")
                if (s.topChapters.isEmpty()) Text("Nothing yet. Time is counted while you read.")
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Chapters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                        for (c in s.topChapters) {
                            val times = s.timesRead[c.book * 1000 + c.chapter] ?: 0
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("${vm.bible.book(c.book).name} ${c.chapter}", modifier = Modifier.weight(1f))
                                Text(
                                    ReadingStats.duration(c.seconds) + if (times > 0) " · read $times×" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Books", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                        for (b in s.books.filter { it.seconds > 0 }.sortedByDescending { it.seconds }.take(10)) {
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(b.name, modifier = Modifier.weight(1f))
                                Text(
                                    "${ReadingStats.duration(b.seconds)} · ${b.chaptersRead}/${b.chapters}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Section("Every chapter")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Not yet", style = MaterialTheme.typography.bodySmall)
                    Cell(MaterialTheme.colorScheme.surfaceContainerHighest)
                    for ((i, c) in READ_SHADES.withIndex()) {
                        Cell(c)
                        Text(if (i == READ_SHADES.lastIndex) "${i + 1}+ times" else "${i + 1}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                for (b in s.books) {
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Text(b.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (ch in 1..b.chapters) {
                                val t = s.timesRead[b.book * 1000 + ch] ?: 0
                                Cell(if (t == 0) MaterialTheme.colorScheme.surfaceContainerHighest else READ_SHADES[(t - 1).coerceAtMost(READ_SHADES.lastIndex)])
                            }
                        }
                    }
                }
                Text(
                    "A chapter counts as read after a minute in it, scrolled through most of the way. Time pauses after " +
                        "two minutes without a touch. Everything stays on this tablet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun percent(a: Int, b: Int) = if (b == 0) 0 else (a * 100 / b).let { if (it == 0 && a > 0) 1 else it }

@Composable
private fun StatTile(value: String, label: String, detail: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).width(150.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Progress(label: String, read: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(140.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else read.toFloat() / total },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = READ_SHADES[2],
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text("  $read / $total", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Cell(color: Color) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

/** Minutes read each day: one series, thin bars on a quiet baseline; tap a bar for its day and time. */
@Composable
private fun DaysChart(days: List<Pair<LocalDate, Int>>) {
    var picked by remember(days) { mutableStateOf<Int?>(days.indexOfLast { it.second > 0 }.takeIf { it >= 0 }) }
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(60)
    val bar = READ_SHADES[2]
    val axis = MaterialTheme.colorScheme.outlineVariant
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    Text(
        picked?.let { i -> "${days[i].first.format(fmt)}: ${ReadingStats.duration(days[i].second)}" } ?: "Tap a day to see its time.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp)
            .semantics { contentDescription = "Reading time for each of the last 30 days" }
            .pointerInput(days) {
                detectTapGestures { p -> picked = (p.x / (size.width / days.size)).toInt().coerceIn(0, days.lastIndex) }
            }
    ) {
        val slot = size.width / days.size
        val w = (slot - 2.dp.toPx()).coerceAtLeast(2f).coerceAtMost(14.dp.toPx())
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        days.forEachIndexed { i, (_, s) ->
            if (s <= 0) return@forEachIndexed
            val h = (s.toFloat() / max * size.height).coerceAtLeast(3.dp.toPx())
            val x = i * slot + (slot - w) / 2
            drawRoundRect(
                if (picked == i) READ_SHADES[3] else bar,
                topLeft = Offset(x, size.height - h), size = Size(w, h),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(days.first().first.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("Most: ${ReadingStats.duration(days.maxOf { it.second })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("Today", style = MaterialTheme.typography.labelSmall)
    }
}

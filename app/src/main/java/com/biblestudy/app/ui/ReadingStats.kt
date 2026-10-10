package com.biblestudy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.biblestudy.app.data.ChapterReading
import com.biblestudy.app.model.BookInfo
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One book's reading (ANL-2, ANL-4). */
data class BookReading(val book: Int, val name: String, val chaptersRead: Int, val chapters: Int, val seconds: Int)

/** A milestone to reach (ANL-7): earned once [have] reaches [need]. */
data class Achievement(val id: String, val title: String, val about: String, val have: Int, val need: Int) {
    val earned get() = have >= need
    val progress get() = (have.toFloat() / need).coerceIn(0f, 1f)
}

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
    /** Milestones, easiest first (ANL-7). */
    val achievements: List<Achievement> = emptyList(),
) {
    /** The milestone nearest to being earned, to aim for next. */
    val nextGoal: Achievement? get() = achievements.filter { !it.earned }.maxByOrNull { it.progress }

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
            val gospels = bookStats.filter { it.book in 40..43 }
            val all = bookStats.sumOf { it.chapters }
            val total = days.sumOf { it.second }
            fun a(id: String, title: String, about: String, have: Int, need: Int) = Achievement(id, title, about, have, need.coerceAtLeast(1))
            val achievements = listOf(
                a("first", "First step", "Read your first chapter", readSet.size, 1),
                a("week", "A week in the Word", "Read 7 days in a row", longest, 7),
                a("ten", "Ten chapters", "Read 10 chapters", readSet.size, 10),
                a("book", "A whole book", "Read every chapter of a book", bookStats.count { it.chapters > 0 && it.chaptersRead == it.chapters }, 1),
                a("hours", "Ten hours", "Spend 10 hours reading", total / 3600, 10),
                a("month", "A month in the Word", "Read 30 days in a row", longest, 30),
                a("gospels", "The Gospels", "Read Matthew, Mark, Luke and John", gospels.sumOf { it.chaptersRead }, gospels.sumOf { it.chapters }),
                a("hundred", "A hundred chapters", "Read 100 chapters", readSet.size, 100),
                a("nt", "New Testament", "Read the whole New Testament", nt.sumOf { it.chaptersRead }, nt.sumOf { it.chapters }),
                a("ot", "Old Testament", "Read the whole Old Testament", ot.sumOf { it.chaptersRead }, ot.sumOf { it.chapters }),
                a("bible", "The whole Bible", "Read every chapter of the Bible", readSet.size, all),
            )
            return ReadingStats(
                chaptersRead = readSet.size,
                chapters = bookStats.sumOf { it.chapters },
                otRead = ot.sumOf { it.chaptersRead }, otChapters = ot.sumOf { it.chapters },
                ntRead = nt.sumOf { it.chaptersRead }, ntChapters = nt.sumOf { it.chapters },
                todaySeconds = byDay[today] ?: 0,
                weekSeconds = secondsSince(today.minusDays(6)),
                monthSeconds = secondsSince(today.minusDays(29)),
                totalSeconds = total,
                studySeconds = days.sumOf { it.third },
                daysRead = readingDays.size,
                currentStreak = current,
                longestStreak = longest,
                last30 = (29 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0) },
                books = bookStats,
                topChapters = chapters.filter { it.seconds > 0 }.sortedByDescending { it.seconds }.take(10),
                timesRead = read.associate { it.book * 1000 + it.chapter to it.timesRead },
                achievements = achievements,
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


/**
 * The single hue for reading, one step per "how much" (sequential): on a light page light to dark,
 * on a dark page dim to bright, so more reading always stands out more.
 */
private val READ_LIGHT = listOf(Color(0xFFC8E6C9), Color(0xFF81C784), Color(0xFF43A047), Color(0xFF2E7D32))
private val READ_DARK = listOf(Color(0xFF3B7A40), Color(0xFF4E9F55), Color(0xFF76C27C), Color(0xFFB4E0B6))
/** The streak's flame. */
private val FLAME = Color(0xFFFF8F00)

@Composable
private fun readShades(): List<Color> = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) READ_DARK else READ_LIGHT

/**
 * How much of the Bible has been read, how often and for how long, what's read most, and the
 * milestones reached (ANL-1 to ANL-7). Cards in one column on a phone, two on a tablet.
 */
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
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 600.dp
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!vm.trackReading) {
                        Text("Counting is off. Turn it on in Settings → Reading.", color = MaterialTheme.colorScheme.error)
                    }
                    CardPair(wide, { BibleCard(s) }, { StreakCard(s) })
                    Tiles(s, wide)
                    CardPair(wide, { AchievementsCard(s, wide) }, { DaysCard(s.last30) })
                    CardPair(wide, { TopChaptersCard(vm, s, if (wide) 10 else 5) }, { TopBooksCard(s, if (wide) 10 else 5) })
                    ChapterGridCard(s, wide)
                    Text(
                        "A chapter counts as read after a minute in it, scrolled through most of the way. Time pauses after " +
                            "two minutes without a touch. With Sync with Google Drive on, your devices' times are added together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** Two cards side by side on a tablet, one under the other on a phone. */
@Composable
private fun CardPair(wide: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    } else {
        first(); second()
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun CardTitle(text: String, icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** How much of the Bible is read: a ring, and a bar for each Testament. */
@Composable
private fun BibleCard(s: ReadingStats) {
    val shades = readShades()
    StatCard {
        CardTitle("Through the Bible", Icons.Filled.AutoStories)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(s.chaptersRead.toFloat() / s.chapters.coerceAtLeast(1), shades[2], 112.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${percent(s.chaptersRead, s.chapters)}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${s.chaptersRead} of ${s.chapters} chapters", style = MaterialTheme.typography.bodyMedium)
                Bar("Old Testament", s.otRead, s.otChapters, shades[2])
                Bar("New Testament", s.ntRead, s.ntChapters, shades[2])
            }
        }
    }
}

/** A progress ring with something in its middle. */
@Composable
private fun Ring(fraction: Float, color: Color, size: Dp, stroke: Dp = 10.dp, center: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val w = stroke.toPx()
            val inset = w / 2
            val box = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            // Even a little reading shows as a dot on the ring.
            if (fraction > 0f) drawArc(color, -90f, sweep.coerceAtLeast(2f), false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}

/** A labelled progress bar: "Old Testament  12 / 929". */
@Composable
private fun Bar(label: String, read: Int, total: Int, color: Color) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("$read / $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        ProgressLine(if (total == 0) 0f else read.toFloat() / total, color)
    }
}

@Composable
private fun ProgressLine(fraction: Float, color: Color, height: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** Days in a row, the best run, and this week's days, each lit if read. */
@Composable
private fun StreakCard(s: ReadingStats) {
    val shades = readShades()
    val lit = s.currentStreak > 0
    StatCard {
        CardTitle("Daily reading", Icons.Filled.LocalFireDepartment, if (lit) FLAME else MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.currentStreak}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.currentStreak == 1) "day in a row" else "days in a row",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            if (s.currentStreak > 0 && s.currentStreak >= s.longestStreak) "Your best run yet. Keep it going!"
            else "Best run: ${s.longestStreak} ${if (s.longestStreak == 1) "day" else "days"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // The last seven days, today last.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((i, day) in s.last30.takeLast(7).withIndex()) {
                val read = day.second >= 60
                val today = i == 6
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(if (read) shades[2] else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(if (today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .semantics { contentDescription = "${day.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}: " + if (read) "read" else "not read" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (read) Icon(Icons.Filled.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        day.first.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Time read: today, this week, the last 30 days, and in all. Two to a row on a phone, four on a tablet. */
@Composable
private fun Tiles(s: ReadingStats, wide: Boolean) {
    val tiles = listOf(
        Triple(Icons.Filled.Schedule, ReadingStats.duration(s.todaySeconds), "today"),
        Triple(Icons.Filled.DateRange, ReadingStats.duration(s.weekSeconds), "this week"),
        Triple(Icons.Filled.CalendarMonth, ReadingStats.duration(s.monthSeconds), "last 30 days"),
        Triple(Icons.Filled.MenuBook, ReadingStats.duration(s.totalSeconds), "in all · ${s.daysRead} ${if (s.daysRead == 1) "day" else "days"}"),
    )
    for (row in tiles.chunked(if (wide) 4 else 2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((icon, value, label) in row) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun badgeIcon(id: String): ImageVector = when (id) {
    "first" -> Icons.Filled.Flag
    "week", "month" -> Icons.Filled.LocalFireDepartment
    "book" -> Icons.Filled.MenuBook
    "hours" -> Icons.Filled.Schedule
    "gospels" -> Icons.Filled.AutoStories
    "nt", "ot" -> Icons.Filled.Verified
    "bible" -> Icons.Filled.WorkspacePremium
    else -> Icons.Filled.Star
}

/** Milestones (ANL-7): earned ones lit, the rest grey with how far along they are; tap one to see what it takes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsCard(s: ReadingStats, wide: Boolean) {
    val earned = s.achievements.count { it.earned }
    var picked by remember(s) { mutableStateOf(s.nextGoal) }
    StatCard {
        CardTitle("Milestones · $earned of ${s.achievements.size}", Icons.Filled.EmojiEvents)
        picked?.let { a ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        (if (a.earned) "Earned: " else if (a == s.nextGoal) "Next: " else "") + a.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(a.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!a.earned) {
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(a.progress, MaterialTheme.colorScheme.primary, 6.dp)
                        Text("${a.have} of ${a.need}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        val perRow = 4
        for (row in s.achievements.chunked(perRow)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (a in row) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { picked = a }.padding(vertical = 4.dp)
                            .semantics { contentDescription = a.title + if (a.earned) ", earned" else ", ${a.have} of ${a.need}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Badge(a, selected = picked == a)
                        Text(
                            a.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
                            color = if (a.earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Badge(a: Achievement, selected: Boolean) {
    val c = MaterialTheme.colorScheme
    if (a.earned) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(c.primary)
                .then(if (selected) Modifier.border(3.dp, c.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(badgeIcon(a.id), null, tint = c.onPrimary, modifier = Modifier.size(26.dp)) }
    } else {
        // Locked: grey, with a ring showing how far along it is.
        Ring(a.progress, c.primary, 52.dp, stroke = 4.dp) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceContainerHighest).then(if (selected) Modifier.border(2.dp, c.onSurface, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                Icon(if (a.progress == 0f) Icons.Filled.Lock else badgeIcon(a.id), null, tint = c.outline, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Minutes read each day: one series, thin bars on a quiet baseline; tap a bar for its day and time. */
@Composable
private fun DaysCard(days: List<Pair<LocalDate, Int>>) {
    val shades = readShades()
    var picked by remember(days) { mutableStateOf<Int?>(days.indexOfLast { it.second > 0 }.takeIf { it >= 0 }) }
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(60)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    val active = days.count { it.second >= 60 }
    StatCard {
        CardTitle("The last 30 days", Icons.Filled.CalendarMonth)
        Text(
            picked?.let { i -> "${days[i].first.format(fmt)}: ${ReadingStats.duration(days[i].second)}" } ?: "Tap a day to see its time.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Read on $active of 30 days" + if (active > 0) " · about ${ReadingStats.duration(days.sumOf { it.second } / active)} a day" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.fillMaxWidth().height(130.dp).padding(top = 10.dp)
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
                    if (picked == i) shades[3] else shades[2],
                    topLeft = Offset(x, size.height - h), size = Size(w, h),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(days.first().first.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("Most: ${ReadingStats.duration(days.maxOf { it.second })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A ranked row: the name, a bar for its share of the most, and the figures. */
@Composable
private fun RankRow(rank: Int, name: String, detail: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Text("$rank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            ProgressLine(fraction, color, 6.dp)
        }
    }
}

@Composable
private fun TopChaptersCard(vm: StudyViewModel, s: ReadingStats, count: Int) {
    val color = readShades()[2]
    StatCard {
        CardTitle("Most read chapters", Icons.Filled.Star)
        if (s.topChapters.isEmpty()) Text("Nothing yet. Time is counted while you read.", style = MaterialTheme.typography.bodySmall)
        val top = s.topChapters.firstOrNull()?.seconds?.coerceAtLeast(1) ?: 1
        for ((i, c) in s.topChapters.take(count).withIndex()) {
            val times = s.timesRead[c.book * 1000 + c.chapter] ?: 0
            RankRow(
                i + 1, "${vm.bible.book(c.book).name} ${c.chapter}",
                ReadingStats.duration(c.seconds) + if (times > 0) " · ${times}×" else "",
                c.seconds.toFloat() / top, color,
            )
        }
    }
}

@Composable
private fun TopBooksCard(s: ReadingStats, count: Int) {
    val color = readShades()[2]
    val books = s.books.filter { it.seconds > 0 }.sortedByDescending { it.seconds }.take(count)
    StatCard {
        CardTitle("Books you've read in", Icons.Filled.MenuBook)
        if (books.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        // Each bar shows how much of the book is read; the time spent is beside it.
        for ((i, b) in books.withIndex()) {
            RankRow(i + 1, b.name, "${ReadingStats.duration(b.seconds)} · ${b.chaptersRead}/${b.chapters} ch", b.chaptersRead.toFloat() / b.chapters.coerceAtLeast(1), color)
        }
    }
}

/** Every chapter as a square, shaded by how often it's been read. Names beside them on a tablet, above them on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterGridCard(s: ReadingStats, wide: Boolean) {
    val shades = readShades()
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    StatCard {
        CardTitle("Every chapter", Icons.Filled.AutoStories)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Not yet", style = MaterialTheme.typography.bodySmall)
            Cell(empty)
            for ((i, c) in shades.withIndex()) {
                Cell(c)
                Text(if (i == shades.lastIndex) "${i + 1}+ times" else "${i + 1}", style = MaterialTheme.typography.bodySmall)
            }
        }
        for ((title, part) in listOf("Old Testament" to s.books.filter { it.book < 40 }, "New Testament" to s.books.filter { it.book >= 40 })) {
            if (part.isEmpty()) continue
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            @Composable fun cells(b: BookReading, m: Modifier) = FlowRow(m, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (ch in 1..b.chapters) {
                    val t = s.timesRead[b.book * 1000 + ch] ?: 0
                    Cell(if (t == 0) empty else shades[(t - 1).coerceAtMost(shades.lastIndex)])
                }
            }
            fun name(b: BookReading) = b.name + if (b.chaptersRead == b.chapters && b.chapters > 0) " ✓" else ""
            if (wide) {
                for (b in part) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Text(name(b), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                    cells(b, Modifier.weight(1f))
                }
            } else {
                // On a phone the books flow along the line, each with its name above its chapters, so short
                // books share a line and long ones wrap.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (b in part) Column {
                        Text(name(b), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        cells(b, Modifier)
                    }
                }
            }
        }
    }
}

private fun percent(a: Int, b: Int) = if (b == 0) 0 else (a * 100 / b).let { if (it == 0 && a > 0) 1 else it }

@Composable
private fun Cell(color: Color) {
    Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

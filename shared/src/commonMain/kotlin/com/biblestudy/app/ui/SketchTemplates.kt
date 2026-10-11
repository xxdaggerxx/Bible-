package com.biblestudy.app.ui

import com.biblestudy.app.model.Drawn
import com.biblestudy.app.model.DrawnBox
import com.biblestudy.app.model.DrawnLine
import com.biblestudy.app.model.DrawnVerse
import com.biblestudy.app.model.Paper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ready-made sketch pages (SKT-5): ordinary text boxes, ink and verse cards, so everything on
 * them can be moved, changed, written over or deleted. References in the text become links.
 * Dates and meanings follow the traditional view (for the kings, Edwin Thiele's chronology).
 */
class SketchTemplate(val id: String, val name: String, val about: String, val paper: Paper, val items: (TemplateEnv) -> List<Drawn>)

/** What a ready-made page may draw from: the offline map outline (null if it couldn't be read). */
class TemplateEnv(val map: LandsMap?)

object SketchTemplates {
    val all = listOf(
        SketchTemplate("feasts", "The feasts of Israel", "The seven feasts of Leviticus 23, with Purim and Hanukkah", Paper.BLANK) { feasts() },
        SketchTemplate("tabernacle", "The tabernacle", "A labelled plan, each piece of furniture and its meaning", Paper.GRID) { tabernacle() },
        SketchTemplate("kings", "The kings of Israel and Judah", "A timeline from 931 to 586 BC, with the prophets", Paper.BLANK) { kings() },
        SketchTemplate("adam", "From Adam to Jesus", "The line of descent through Genesis, Ruth, Matthew and Luke", Paper.BLANK) { adamToJesus() },
        // Added in 1.1.
        SketchTemplate("paul", "Paul's missionary journeys", "The three journeys and the voyage to Rome, on the map", Paper.BLANK) { MoreTemplates.paul(it) },
        SketchTemplate("exodus", "The Exodus and the wilderness", "The traditional route from Egypt to the Jordan, with the main events", Paper.BLANK) { MoreTemplates.exodus(it) },
        SketchTemplate("christ", "The life of Christ", "The main events in order, with the four Gospels side by side", Paper.BLANK) { MoreTemplates.lifeOfChrist() },
        SketchTemplate("tribes", "The twelve tribes", "Jacob's sons by their mothers, and the tribes' lands", Paper.BLANK) { MoreTemplates.twelveTribes(it) },
        SketchTemplate("temples", "Solomon's and Herod's temples", "Labelled plans of both temples", Paper.GRID) { MoreTemplates.temples() },
    )

    private const val LEFT = 40f
    private const val RIGHT = 1260f
    private const val INK = 0xFF6D4C41.toInt()
    private const val GREY = 0xFF777777.toInt()
    private const val SPRING = 0x4081C784
    private const val AUTUMN = 0x40FFB74D
    private const val LATER = 0x4090A4AE
    private const val GOOD = 0x4081C784
    private const val EVIL = 0x40E57373
    private const val MIXED = 0x40FFD54F
    private const val PROPHET = 0x40B39DDB

    /** Roughly how tall a text box will be, as the app estimates it before layout. */
    private fun height(text: String, w: Float, size: Float): Float {
        val perLine = ((w - 16f) / (size * 0.5f)).coerceAtLeast(1f)
        val lines = text.lines().sumOf { kotlin.math.ceil(it.length.coerceAtLeast(1) / perLine).toInt() }
        return lines * size * 1.35f + 16f
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int = INK, width: Float = 3f) =
        DrawnLine(listOf(x to y, x + w to y, x + w to y + h, x to y + h, x to y), color, width)

    private fun circle(cx: Float, cy: Float, r: Float, color: Int = INK, width: Float = 3f) =
        DrawnLine((0..24).map { i -> val a = 2 * PI * i / 24; (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat() }, color, width)

    private fun line(vararg p: Pair<Float, Float>, color: Int = INK, width: Float = 3f) = DrawnLine(p.toList(), color, width)

    // ---------------------------------------------------------------------------------------------
    // The feasts of Israel
    // ---------------------------------------------------------------------------------------------

    private class Feast(val n: Int, val name: String, val month: Float, val bg: Int, val text: String)

    private fun feasts(): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "“These are the appointed feasts of the LORD” (Leviticus 23:4). The spring feasts were fulfilled at Christ’s first coming; " +
                "the autumn feasts look forward to his return. Green: spring · Amber: autumn · Grey: later feasts.", size = 18f)
        // The year, Nisan to Adar, with each feast's place on it.
        val months = listOf("Nisan", "Iyyar", "Sivan", "Tammuz", "Av", "Elul", "Tishri", "Heshvan", "Kislev", "Tevet", "Shevat", "Adar")
        val y = 150f
        val x0 = 80f; val step = (RIGHT - 60f - x0) / 12f
        out += line(x0 to y, x0 + step * 12 to y)
        for (i in 0..12) out += line(x0 + step * i to y - 10f, x0 + step * i to y + 10f, width = 2f)
        months.forEachIndexed { i, m -> out += DrawnBox(x0 + step * i + 4f, y + 14f, step - 4f, m, size = 14f, color = GREY) }
        out += DrawnBox(x0, y + 44f, 120f, "Mar\u2013Apr", size = 12f, color = GREY)
        out += DrawnBox(x0 + step * 6, y + 44f, 120f, "Sep\u2013Oct", size = 12f, color = GREY)
        val list = feastList()
        // Feasts close together share one mark: "1 2 3" in Nisan, "5 6 7" in Tishri.
        val groups = ArrayList<MutableList<Feast>>()
        for (f in list.sortedBy { it.month }) {
            val last = groups.lastOrNull()
            if (last != null && f.month - last.last().month < 0.6f) last += f else groups += mutableListOf(f)
        }
        for (g in groups) {
            val fx = x0 + step * (g.map { it.month }.average().toFloat() - 1f)
            out += circle(fx, y, 11f, width = 4f)
            val label = g.joinToString(" ") { it.n.toString() }
            val w = label.length * 10f + 24f
            out += DrawnBox(fx - w / 2, y - 58f, w, label, size = 16f)
        }
        // A card for each feast, two columns.
        val colW = (RIGHT - LEFT - 30f) / 2
        var yl = 250f; var yr = 250f
        for (f in list) {
            val text = "${f.n}. ${f.name}\n${f.text}"
            val h = height(text, colW, 17f)
            if (yl <= yr) { out += DrawnBox(LEFT, yl, colW, text, size = 17f, background = f.bg); yl += h + 24f }
            else { out += DrawnBox(LEFT + colW + 30f, yr, colW, text, size = 17f, background = f.bg); yr += h + 24f }
        }
        return out
    }

    private fun feastList() = listOf(
        Feast(1, "Passover (Pesach)", 1.45f, SPRING,
            "When: 14 Nisan, at twilight.\nRemembers: the lamb’s blood on the doorposts, when the LORD passed over Israel and brought them out of Egypt (Exodus 12).\n" +
                "Kept: a lamb without blemish was killed and eaten with unleavened bread and bitter herbs.\n" +
                "Points to Christ: “Christ, our Passover lamb, has been sacrificed” (1 Corinthians 5:7); he died at Passover (John 19:14, 36).\n" +
                "Read: Leviticus 23:5; Exodus 12:1-28; Luke 22:7-20."),
        Feast(2, "Unleavened Bread (Matzot)", 1.5f, SPRING,
            "When: 15–21 Nisan.\nRemembers: leaving Egypt in haste, with no time for the bread to rise (Exodus 12:39).\n" +
                "Kept: seven days without yeast; the first and last days were holy gatherings.\n" +
                "Points to Christ: his sinless body laid in the grave; believers are to live “with the unleavened bread of sincerity and truth” (1 Corinthians 5:8).\n" +
                "Read: Leviticus 23:6-8; Exodus 12:14-20."),
        Feast(3, "Firstfruits", 1.55f, SPRING,
            "When: the day after the Sabbath in the week of Unleavened Bread.\nRemembers: the first sheaf of the barley harvest belongs to the LORD.\n" +
                "Kept: the priest waved the first sheaf before the LORD; no new grain was eaten until then.\n" +
                "Points to Christ: raised on that day, “the firstfruits of those who have fallen asleep” (1 Corinthians 15:20-23).\n" +
                "Read: Leviticus 23:9-14."),
        Feast(4, "Weeks (Shavuot, Pentecost)", 3.2f, SPRING,
            "When: fifty days after Firstfruits (early Sivan).\nRemembers: the wheat harvest; by Jewish tradition, the giving of the law at Sinai.\n" +
                "Kept: two loaves baked with yeast were waved before the LORD, with offerings; gleanings were left for the poor.\n" +
                "Points to Christ: the Holy Spirit was poured out at Pentecost and the first harvest of the church gathered (Acts 2:1-41).\n" +
                "Read: Leviticus 23:15-22; Deuteronomy 16:9-12."),
        Feast(5, "Trumpets (Rosh Hashanah)", 7.0f, AUTUMN,
            "When: 1 Tishri.\nRemembers: a day of rest and a memorial blown with trumpets, calling Israel to gather and prepare.\n" +
                "Kept: trumpet blasts, rest from work and offerings by fire.\n" +
                "Points to Christ: the trumpet that will sound when the Lord returns (1 Thessalonians 4:16; 1 Corinthians 15:52).\n" +
                "Read: Leviticus 23:23-25; Numbers 29:1-6."),
        Feast(6, "Day of Atonement (Yom Kippur)", 7.3f, AUTUMN,
            "When: 10 Tishri.\nRemembers: the one day each year the high priest entered the Most Holy Place to make atonement for the people.\n" +
                "Kept: fasting and rest; blood of the sin offering sprinkled on the mercy seat; the scapegoat sent into the wilderness.\n" +
                "Points to Christ: our high priest entered heaven itself “by his own blood”, once for all (Hebrews 9:11-12, 24-28).\n" +
                "Read: Leviticus 16; Leviticus 23:26-32."),
        Feast(7, "Tabernacles (Sukkot, Booths)", 7.5f, AUTUMN,
            "When: 15–21 Tishri, with a holy eighth day.\nRemembers: Israel living in tents in the wilderness; the harvest gathered in.\n" +
                "Kept: living in booths made of branches, rejoicing before the LORD; water and lights at the temple in later times.\n" +
                "Points to Christ: “The Word became flesh and dwelt (tabernacled) among us” (John 1:14); his call at the feast (John 7:37-39); God dwelling with his people (Revelation 21:3).\n" +
                "Read: Leviticus 23:33-43; Nehemiah 8:13-18."),
        Feast(8, "Purim", 12.45f, LATER,
            "When: 14–15 Adar.\nRemembers: God’s deliverance of the Jews from Haman’s plot in the days of Esther.\n" +
                "Kept: reading the book of Esther, feasting, gifts to one another and to the poor.\n" +
                "Points to: God’s unseen care for his people and his faithfulness to his promises.\n" +
                "Read: Esther 9:20-32."),
        Feast(9, "Dedication (Hanukkah)", 9.8f, LATER,
            "When: 25 Kislev, eight days.\nRemembers: the cleansing and rededication of the temple in 164 BC after its defilement by Antiochus IV (told in 1 Maccabees 4).\n" +
                "Kept: lamps lit for eight days.\n" +
                "Points to Christ: Jesus walked in the temple at this feast and declared he and the Father are one (John 10:22-30); he is the light of the world (John 8:12).\n" +
                "Read: John 10:22-39."),
    )

    // ---------------------------------------------------------------------------------------------
    // The tabernacle
    // ---------------------------------------------------------------------------------------------

    private fun tabernacle(): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "The plan from above, west on the left. The court was 100 by 50 cubits (about 45 by 23 m); the tent 30 by 10 cubits. " +
                "Read Exodus 25–40 and Hebrews 8–10.", size = 18f)
        val c = 11f // page units per cubit
        val x0 = 80f; val y0 = 130f
        fun cx(cubit: Float) = x0 + cubit * c
        fun cy(cubit: Float) = y0 + cubit * c
        // The court, with its gate in the east side.
        out += line(cx(100f) to cy(15f), cx(100f) to cy(0f), cx(0f) to cy(0f), cx(0f) to cy(50f), cx(100f) to cy(50f), cx(100f) to cy(35f), width = 4f)
        out += line(cx(100f) to cy(15f), cx(100f) to cy(35f), color = 0xFF1E88E5.toInt(), width = 6f)
        out += DrawnBox(cx(100f) + 8f, cy(22f), 140f, "Gate (east)", size = 15f)
        out += DrawnBox(cx(42f), cy(0f) - 34f, 200f, "Court (curtains of linen)", size = 15f, color = GREY)
        // The tent: Most Holy Place (a 10-cubit cube) and Holy Place, with the veil between.
        out += rect(cx(20f), cy(20f), 30f * c, 10f * c, width = 4f)
        out += line(cx(30f) to cy(20f), cx(30f) to cy(30f), color = 0xFFC62828.toInt(), width = 5f)
        out += DrawnBox(cx(20f), cy(20f) - 34f, 120f, "Most Holy", size = 14f)
        out += DrawnBox(cx(34f), cy(20f) - 34f, 120f, "Holy Place", size = 14f)
        out += DrawnBox(cx(27f), cy(30f) + 4f, 70f, "veil", size = 13f, color = 0xFFC62828.toInt())
        // Furniture.
        out += rect(cx(23.8f), cy(24.2f), 2.5f * c, 1.5f * c, color = 0xFFB8860B.toInt(), width = 4f) // ark
        out += DrawnBox(cx(21.6f), cy(26f), 80f, "1", size = 14f)
        out += rect(cx(31f), cy(24.5f), 1f * c, 1f * c, color = 0xFFB8860B.toInt()) // incense altar
        out += DrawnBox(cx(31f), cy(26f), 40f, "2", size = 14f)
        out += rect(cx(40f), cy(21.5f), 2f * c, 1f * c, color = 0xFFB8860B.toInt()) // table
        out += DrawnBox(cx(42.3f), cy(21f), 40f, "3", size = 14f)
        out += line(cx(41f) to cy(26.5f), cx(41f) to cy(28.5f), color = 0xFFB8860B.toInt()) // lampstand
        out += line(cx(40f) to cy(26.5f), cx(40f) to cy(27.5f), cx(42f) to cy(27.5f), cx(42f) to cy(26.5f), color = 0xFFB8860B.toInt())
        out += DrawnBox(cx(42.3f), cy(26.6f), 40f, "4", size = 14f)
        out += circle(cx(60f), cy(25f), 1.2f * c, color = 0xFF8D6E63.toInt()) // laver
        out += DrawnBox(cx(61.5f), cy(23.5f), 40f, "5", size = 14f)
        out += rect(cx(72.5f), cy(22.5f), 5f * c, 5f * c, color = 0xFF8D6E63.toInt(), width = 4f) // altar
        out += DrawnBox(cx(78f), cy(22.5f), 40f, "6", size = 14f)
        out += DrawnBox(LEFT, cy(50f) + 12f, RIGHT - LEFT,
            "Gold furniture inside the tent; bronze in the court. The way in runs from the gate to the altar, the laver, the Holy Place and, " +
                "once a year, the Most Holy Place.", size = 15f, color = GREY)
        // A card for each piece, then key verses.
        val pieces = listOf(
            "1. The ark of the covenant and mercy seat\nAcacia wood covered in gold, with two cherubim on the lid. It held the tablets of the law, a jar of manna and Aaron’s rod (Hebrews 9:4). " +
                "God met with Moses above the mercy seat (Exodus 25:22).\nMeaning: God’s throne among his people; sin covered by blood (Romans 3:25). Read: Exodus 25:10-22.",
            "2. The altar of incense\nA small gold altar before the veil; sweet incense burned morning and evening.\nMeaning: prayer rising to God (Psalm 141:2; Revelation 8:3-4); " +
                "Christ who always lives to intercede (Hebrews 7:25). Read: Exodus 30:1-10.",
            "3. The table of the bread of the Presence\nTwelve loaves, one for each tribe, set out fresh every Sabbath.\nMeaning: fellowship with God and his provision; Christ the bread of life (John 6:35). Read: Exodus 25:23-30; Leviticus 24:5-9.",
            "4. The golden lampstand\nBeaten from one piece of gold, with seven lamps kept burning with olive oil; the only light in the Holy Place.\nMeaning: Christ the light of the world (John 8:12); " +
                "the Spirit’s light (Zechariah 4:2-6). Read: Exodus 25:31-40.",
            "5. The bronze laver\nA basin made from the women’s bronze mirrors, where the priests washed hands and feet before serving.\nMeaning: cleansing for service (Titus 3:5; John 13:8-10). Read: Exodus 30:17-21; 38:8.",
            "6. The bronze altar\nFive cubits square, where the burnt offerings were made; the first thing met inside the gate.\nMeaning: no approach to God without sacrifice; Christ offered once for all (Hebrews 10:10-14). Read: Exodus 27:1-8.",
        )
        val colW = (RIGHT - LEFT - 30f) / 2
        var yl = cy(50f) + 90f; var yr = yl
        for ((i, p) in pieces.withIndex()) {
            val h = height(p, colW, 17f)
            val bg = if (i < 4) 0x40FFD54F else 0x40BCAAA4
            if (yl <= yr) { out += DrawnBox(LEFT, yl, colW, p, size = 17f, background = bg); yl += h + 20f }
            else { out += DrawnBox(LEFT + colW + 30f, yr, colW, p, size = 17f, background = bg); yr += h + 20f }
        }
        var y = maxOf(yl, yr) + 20f
        for (ref in listOf("Exodus 25:8-9", "Hebrews 9:11-12", "Hebrews 10:19-22")) {
            out += DrawnVerse(LEFT, y, RIGHT - LEFT, ref)
            y += 150f
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The kings of Israel and Judah (Thiele)
    // ---------------------------------------------------------------------------------------------

    /** [verdict]: "g" good, "e" evil, "m" mixed. */
    private class King(val name: String, val from: Int, val to: Int, val verdict: Char, val read: String)

    private val israel = listOf(
        King("Jeroboam I", 931, 910, 'e', "1 Kings 12:20-14:20"), King("Nadab", 910, 909, 'e', "1 Kings 15:25-31"),
        King("Baasha", 909, 886, 'e', "1 Kings 15:27-16:7"), King("Elah", 886, 885, 'e', "1 Kings 16:8-14"),
        King("Zimri (7 days)", 885, 885, 'e', "1 Kings 16:15-20"), King("Omri", 885, 874, 'e', "1 Kings 16:21-28"),
        King("Ahab", 874, 853, 'e', "1 Kings 16:29-22:40"), King("Ahaziah", 853, 852, 'e', "1 Kings 22:51-2 Kings 1:18"),
        King("Joram", 852, 841, 'e', "2 Kings 3:1-9:26"), King("Jehu", 841, 814, 'e', "2 Kings 9-10"),
        King("Jehoahaz", 814, 798, 'e', "2 Kings 13:1-9"), King("Jehoash", 798, 782, 'e', "2 Kings 13:10-25"),
        King("Jeroboam II", 793, 753, 'e', "2 Kings 14:23-29"), King("Zechariah", 753, 752, 'e', "2 Kings 15:8-12"),
        King("Shallum (1 month)", 752, 752, 'e', "2 Kings 15:13-15"), King("Menahem", 752, 742, 'e', "2 Kings 15:16-22"),
        King("Pekahiah", 742, 740, 'e', "2 Kings 15:23-26"), King("Pekah", 752, 732, 'e', "2 Kings 15:27-31"),
        King("Hoshea", 732, 722, 'e', "2 Kings 17:1-6"),
    )
    private val judah = listOf(
        King("Rehoboam", 931, 913, 'e', "1 Kings 12:1-24; 1 Kings 14:21-31"), King("Abijah", 913, 911, 'e', "1 Kings 15:1-8"),
        King("Asa", 911, 870, 'g', "1 Kings 15:9-24"), King("Jehoshaphat", 872, 848, 'g', "1 Kings 22:41-50"),
        King("Jehoram", 853, 841, 'e', "2 Kings 8:16-24"), King("Ahaziah", 841, 841, 'e', "2 Kings 8:25-29"),
        King("Athaliah (queen)", 841, 835, 'e', "2 Kings 11"), King("Joash", 835, 796, 'm', "2 Kings 12"),
        King("Amaziah", 796, 767, 'm', "2 Kings 14:1-20"), King("Uzziah (Azariah)", 792, 740, 'm', "2 Kings 15:1-7; 2 Chronicles 26"),
        King("Jotham", 750, 732, 'g', "2 Kings 15:32-38"), King("Ahaz", 735, 716, 'e', "2 Kings 16"),
        King("Hezekiah", 716, 687, 'g', "2 Kings 18-20"), King("Manasseh", 697, 643, 'e', "2 Kings 21:1-18; 2 Chronicles 33"),
        King("Amon", 643, 641, 'e', "2 Kings 21:19-26"), King("Josiah", 641, 609, 'g', "2 Kings 22:1-23:30"),
        King("Jehoahaz", 609, 609, 'e', "2 Kings 23:31-34"), King("Jehoiakim", 609, 598, 'e', "2 Kings 23:34-24:7"),
        King("Jehoiachin", 598, 597, 'e', "2 Kings 24:8-17"), King("Zedekiah", 597, 586, 'e', "2 Kings 24:18-25:7"),
    )
    private val prophets = listOf(
        "Elijah" to 870, "Elisha" to 848, "Joel?" to 835, "Jonah" to 785, "Amos" to 760, "Hosea" to 755, "Isaiah" to 740,
        "Micah" to 735, "Nahum" to 650, "Zephaniah" to 630, "Jeremiah" to 627, "Habakkuk" to 607, "Daniel" to 605, "Ezekiel" to 593,
    )

    private fun kings(): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "Dates BC follow Edwin Thiele’s chronology; overlapping dates are shared reigns (co-regencies) or rivals. " +
                "Green: did right in the eyes of the LORD · Yellow: right, but not wholeheartedly · Red: did evil. Tap a reference to read his story.", size = 17f)
        val top = 130f
        val perYear = 6.2f
        fun yOf(year: Int) = top + (931 - year) * perYear
        val axis = 650f
        val strip = 130f // clear space each side of the axis for the years and events
        // The years down the middle.
        out += line(axis to yOf(931), axis to yOf(586), width = 4f)
        for (year in 900 downTo 600 step 25) {
            out += line(axis - 8f to yOf(year), axis + 8f to yOf(year), width = 2f)
            out += DrawnBox(axis - 30f, yOf(year) - 30f, 70f, "$year", size = 12f, color = GREY)
        }
        out += DrawnBox(LEFT, top - 70f, 400f, "ISRAEL (north)", size = 22f)
        out += DrawnBox(RIGHT - 400f, top - 70f, 400f, "JUDAH (south)", size = 22f)
        // Events across the axis.
        for ((year, text) in listOf(931 to "931 The kingdom divides (1 Kings 12)", 722 to "722 Samaria falls to Assyria (2 Kings 17)", 586 to "586 Jerusalem falls to Babylon (2 Kings 25)")) {
            out += line(axis - strip + 10f to yOf(year), axis + strip - 10f to yOf(year), color = 0xFFC62828.toInt(), width = 2f)
            out += DrawnBox(axis - strip + 10f, yOf(year) + 4f, 2 * strip - 20f, text, size = 13f, color = 0xFFC62828.toInt())
        }
        // Kings, pushed down where reigns are too close to read, with a line to their year.
        fun lane(list: List<King>, left: Boolean) {
            var next = 0f
            for (k in list.sortedBy { -it.from }) {
                val text = "${k.name}  ${k.from}–${k.to}\n${k.read}"
                val w = 300f
                val y = maxOf(yOf(k.from) - 12f, next)
                val x = if (left) axis - strip - w else axis + strip
                val bg = when (k.verdict) { 'g' -> GOOD; 'm' -> MIXED; else -> EVIL }
                out += DrawnBox(x, y, w, text, size = 15f, background = bg)
                val edge = if (left) x + w else x
                out += line(edge to y + 14f, axis to yOf(k.from), color = GREY, width = 1.5f)
                next = y + height(text, w, 15f) + 6f
            }
        }
        lane(israel, left = true)
        lane(judah, left = false)
        // Prophets along the outer edges.
        var py = 0f
        for ((name, year) in prophets) {
            val y = maxOf(yOf(year), py)
            val toIsrael = name in setOf("Elijah", "Elisha", "Jonah", "Amos", "Hosea")
            out += DrawnBox(if (toIsrael) LEFT - 20f else RIGHT - 110f, y, 130f, name, size = 14f, background = PROPHET)
            if (!toIsrael) py = y + 34f
        }
        out += DrawnBox(LEFT, yOf(586) + 60f, RIGHT - LEFT,
            "Purple: prophets of the time (outer edges; Israel left, Judah right). Dates of some prophets are approximate.", size = 14f, color = GREY)
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // From Adam to Jesus
    // ---------------------------------------------------------------------------------------------

    private fun adamToJesus(): List<Drawn> {
        val sections = listOf(
            "Adam to Noah — Genesis 5" to listOf("Adam", "Seth", "Enosh", "Kenan", "Mahalalel", "Jared", "Enoch", "Methuselah", "Lamech", "Noah"),
            "Noah to Abraham — Genesis 11:10-26" to listOf("Shem", "Arphaxad", "Shelah", "Eber", "Peleg", "Reu", "Serug", "Nahor", "Terah", "Abraham"),
            "The patriarchs — Genesis 21–38" to listOf("Isaac", "Jacob", "Judah", "Perez"),
            "Judah to David — Ruth 4:18-22" to listOf("Hezron", "Ram", "Amminadab", "Nahshon", "Salmon", "Boaz (m. Ruth)", "Obed", "Jesse", "David"),
            "The kings of Judah — Matthew 1:6-11" to listOf("Solomon", "Rehoboam", "Abijah", "Asa", "Jehoshaphat", "Joram", "Uzziah", "Jotham", "Ahaz", "Hezekiah", "Manasseh", "Amon", "Josiah", "Jeconiah"),
            "After the exile — Matthew 1:12-16" to listOf("Shealtiel", "Zerubbabel", "Abiud", "Eliakim", "Azor", "Zadok", "Achim", "Eliud", "Eleazar", "Matthan", "Jacob", "Joseph (husband of Mary)", "JESUS"),
        )
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "The promised offspring of the woman (Genesis 3:15), of Abraham (Genesis 22:18) and of David (2 Samuel 7:12-16). " +
                "Matthew follows the royal line through Solomon to Joseph; Luke 3:23-38 traces the line through David’s son Nathan, " +
                "traditionally understood as Mary’s line.", size = 17f)
        val perRow = 5
        val boxW = 200f
        val gap = (RIGHT - LEFT - perRow * boxW) / (perRow - 1)
        var y = 140f
        var prev: Pair<Float, Float>? = null
        for ((title, names) in sections) {
            // The title sits clear of the line coming down from the last name.
            val tx = prev?.let { if (it.first < (LEFT + RIGHT) / 2) LEFT + boxW + gap else LEFT } ?: LEFT
            out += DrawnBox(tx, y, RIGHT - tx, title, size = 19f, color = 0xFF7A5C2E.toInt())
            y += 50f
            names.chunked(perRow).forEachIndexed { r, row ->
                // Rows snake: left to right, then right to left, so each name follows the last.
                val ltr = r % 2 == 0
                row.forEachIndexed { i, name ->
                    val col = if (ltr) i else perRow - 1 - i
                    val x = LEFT + col * (boxW + gap)
                    val bg = when (name) { "JESUS" -> 0x80FFD54F.toInt(); "David", "Abraham", "Adam", "Noah" -> 0x4090CAF9; else -> 0x20000000 }
                    out += DrawnBox(x, y, boxW, name, size = if (name == "JESUS") 22f else 17f, background = bg)
                    val here = (x + boxW / 2) to (y + 20f)
                    prev?.let { p ->
                        if (p.second == here.second) {
                            val (a, b) = if (p.first < here.first) p to here else here to p
                            out += line(a.first + boxW / 2 to a.second, b.first - boxW / 2 to b.second, width = 2f)
                        } else {
                            out += line(p.first to p.second + 20f, p.first to here.second - 34f, here.first to here.second - 34f, here.first to here.second - 20f, width = 2f)
                        }
                    }
                    prev = here
                }
                y += 90f
            }
            y += 10f
            // The next section starts on a new row; the line continues from the last name.
        }
        out += DrawnVerse(LEFT, y + 10f, RIGHT - LEFT, "Matthew 1:17")
        return out
    }
}

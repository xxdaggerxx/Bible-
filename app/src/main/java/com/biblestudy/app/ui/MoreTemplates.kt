package com.biblestudy.app.ui

import com.biblestudy.app.model.Drawn
import com.biblestudy.app.model.DrawnBox
import com.biblestudy.app.model.DrawnLine
import com.biblestudy.app.model.DrawnVerse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ready-made pages added in 1.1 (SKT-5): two maps, a harmony of the life of Christ, the
 * twelve tribes and the temples. Places and dates follow the traditional view.
 */
internal object MoreTemplates {
    private const val LEFT = 40f
    private const val RIGHT = 1260f
    private const val INK = 0xFF6D4C41.toInt()
    private const val GREY = 0xFF777777.toInt()
    private const val COAST = 0xFF9E8F73.toInt()
    private const val RIVER = 0xFF5B8DB8.toInt()
    private const val ROUTE1 = 0xFFC62828.toInt()
    private const val ROUTE2 = 0xFF1E4FA8.toInt()
    private const val ROUTE3 = 0xFF2E7D32.toInt()
    private const val ROUTE4 = 0xFF6A1B9A.toInt()

    private fun line(vararg p: Pair<Float, Float>, color: Int = INK, width: Float = 3f) = DrawnLine(p.toList(), color, width)
    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int = INK, width: Float = 3f) =
        DrawnLine(listOf(x to y, x + w to y, x + w to y + h, x to y + h, x to y), color, width)
    private fun dot(x: Float, y: Float, color: Int, r: Float = 6f) =
        DrawnLine((0..12).map { i -> val a = 2 * PI * i / 12; (x + r * cos(a)).toFloat() to (y + r * sin(a)).toFloat() }, color, 4f)

    /** A map of a lon/lat box drawn into the page, with a way to place points on it. */
    private class MapArea(val lonMin: Float, val lonMax: Float, val latMin: Float, val latMax: Float, val x0: Float, val y0: Float, val w: Float) {
        private val k = cos(Math.toRadians(((latMin + latMax) / 2).toDouble())).toFloat()
        val h = w * (latMax - latMin) / ((lonMax - lonMin) * k)
        fun at(lat: Float, lon: Float) = (x0 + (lon - lonMin) / (lonMax - lonMin) * w) to (y0 + (latMax - lat) / (latMax - latMin) * h)
        fun inside(lon: Float, lat: Float) = lon in lonMin..lonMax && lat in latMin..latMax
    }

    /** Coasts, lakes and rivers inside the area, as ink; every few points so the page stays light. */
    private fun mapLines(env: TemplateEnv, a: MapArea): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += rect(a.x0, a.y0, a.w, a.h, GREY, 2f)
        val map = env.map ?: return out
        fun add(rings: List<FloatArray>, color: Int, width: Float) {
            for (r in rings) {
                var run = ArrayList<Pair<Float, Float>>()
                var i = 0
                while (i + 1 < r.size) {
                    val lon = r[i]; val lat = r[i + 1]
                    if (a.inside(lon, lat)) run += a.at(lat, lon)
                    else { if (run.size > 1) out += DrawnLine(run, color, width); run = ArrayList() }
                    i += 2
                }
                if (run.size > 1) out += DrawnLine(run, color, width)
            }
        }
        add(map.land, COAST, 2f)
        add(map.lakes, RIVER, 2f)
        add(map.rivers, RIVER, 1.5f)
        return out
    }

    private class Stop(val name: String, val lat: Float, val lon: Float)

    private fun route(a: MapArea, stops: List<Stop>, color: Int, labels: Boolean, seen: MutableSet<String>): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnLine(stops.map { a.at(it.lat, it.lon) }, color, 3.5f)
        for (s in stops) {
            val (x, y) = a.at(s.lat, s.lon)
            out += dot(x, y, color, 5f)
            if (labels && seen.add(s.name)) out += DrawnBox(x + 8f, y - 26f, 170f, s.name, size = 13f)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Paul's missionary journeys
    // ---------------------------------------------------------------------------------------------

    fun paul(env: TemplateEnv): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "Red: first journey (Acts 13–14) · Blue: second (Acts 15:36–18:22) · Green: third (Acts 18:23–21:17) · " +
                "Purple: the voyage to Rome (Acts 27–28). Places as traditionally identified.", size = 17f)
        val a = MapArea(12f, 37f, 30.5f, 42.5f, LEFT, 90f, RIGHT - LEFT)
        out += mapLines(env, a)
        val antioch = Stop("Antioch", 36.20f, 36.16f); val seleucia = Stop("Seleucia", 36.12f, 35.93f)
        val salamis = Stop("Salamis", 35.18f, 33.90f); val paphos = Stop("Paphos", 34.76f, 32.41f)
        val perga = Stop("Perga", 36.96f, 30.85f); val pisidian = Stop("Pisidian Antioch", 38.30f, 31.19f)
        val iconium = Stop("Iconium", 37.87f, 32.48f); val lystra = Stop("Lystra", 37.58f, 32.45f)
        val derbe = Stop("Derbe", 37.35f, 33.36f); val attalia = Stop("Attalia", 36.89f, 30.70f)
        val troas = Stop("Troas", 39.75f, 26.16f); val neapolis = Stop("Neapolis", 40.94f, 24.41f)
        val philippi = Stop("Philippi", 41.01f, 24.29f); val thessalonica = Stop("Thessalonica", 40.64f, 22.94f)
        val berea = Stop("Berea", 40.52f, 22.20f); val athens = Stop("Athens", 37.98f, 23.73f)
        val corinth = Stop("Corinth", 37.91f, 22.88f); val ephesus = Stop("Ephesus", 37.94f, 27.34f)
        val caesarea = Stop("Caesarea", 32.50f, 34.89f); val jerusalem = Stop("Jerusalem", 31.78f, 35.23f)
        val miletus = Stop("Miletus", 37.53f, 27.28f); val rhodes = Stop("Rhodes", 36.43f, 28.22f)
        val patara = Stop("Patara", 36.26f, 29.31f); val tyre = Stop("Tyre", 33.27f, 35.20f)
        val sidon = Stop("Sidon", 33.56f, 35.37f); val myra = Stop("Myra", 36.26f, 29.98f)
        val fairHavens = Stop("Fair Havens", 34.93f, 24.80f); val malta = Stop("Malta", 35.90f, 14.45f)
        val syracuse = Stop("Syracuse", 37.08f, 15.29f); val rhegium = Stop("Rhegium", 38.11f, 15.65f)
        val puteoli = Stop("Puteoli", 40.82f, 14.12f); val rome = Stop("Rome", 41.89f, 12.49f)
        val seen = HashSet<String>()
        out += route(a, listOf(antioch, seleucia, salamis, paphos, perga, pisidian, iconium, lystra, derbe, lystra, iconium, pisidian, perga, attalia, antioch), ROUTE1, true, seen)
        out += route(a, listOf(antioch, derbe, lystra, iconium, troas, neapolis, philippi, thessalonica, berea, athens, corinth, ephesus, caesarea, jerusalem, antioch), ROUTE2, true, seen)
        out += route(a, listOf(antioch, pisidian, ephesus, troas, philippi, thessalonica, berea, corinth, berea, philippi, troas, miletus, rhodes, patara, tyre, caesarea, jerusalem), ROUTE3, true, seen)
        out += route(a, listOf(caesarea, sidon, myra, fairHavens, malta, syracuse, rhegium, puteoli, rome), ROUTE4, true, seen)
        var y = a.y0 + a.h + 30f
        for (ref in listOf("Acts 13:2-3", "Acts 16:9-10", "Acts 20:24", "Acts 28:30-31")) {
            out += DrawnVerse(LEFT, y, RIGHT - LEFT, ref)
            y += 140f
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The Exodus and the wilderness journey
    // ---------------------------------------------------------------------------------------------

    fun exodus(env: TemplateEnv): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "The traditional route: out of Egypt from Rameses, through the sea, south to Mount Sinai (Jebel Musa), north to Kadesh, " +
                "round Edom and up to the plains of Moab across the Jordan from Jericho. Numbers 33 lists every camp.", size = 17f)
        val a = MapArea(30.8f, 36.6f, 27.6f, 32.4f, LEFT, 110f, RIGHT - LEFT)
        out += mapLines(env, a)
        val stops = listOf(
            Stop("1 Rameses", 30.80f, 31.83f), Stop("2 Succoth", 30.55f, 32.10f), Stop("3 Crossing the sea", 29.95f, 32.55f),
            Stop("4 Marah", 29.60f, 32.75f), Stop("5 Elim", 29.30f, 33.00f), Stop("6 Wilderness of Sin", 28.90f, 33.30f),
            Stop("7 Rephidim", 28.70f, 33.65f), Stop("8 Mount Sinai", 28.54f, 33.97f), Stop("9 Kadesh Barnea", 30.65f, 34.42f),
            Stop("10 Ezion-geber", 29.55f, 34.97f), Stop("11 Mount Hor", 30.32f, 35.41f), Stop("12 Plains of Moab", 31.85f, 35.62f),
            Stop("13 Jericho", 31.87f, 35.44f),
        )
        out += route(a, stops, ROUTE1, true, HashSet())
        val events = listOf(
            "1 Rameses: the Passover night; Israel leaves Egypt (Exodus 12:37).",
            "2–3 Led by the pillar of cloud and fire; the sea divided (Exodus 13:20-22; 14).",
            "4 Marah: bitter water made sweet (Exodus 15:22-25). 5 Elim: twelve springs, seventy palms (Exodus 15:27).",
            "6 Manna and quail (Exodus 16). 7 Water from the rock; victory over Amalek (Exodus 17).",
            "8 Mount Sinai: the law, the covenant and the tabernacle; about a year there (Exodus 19 – Numbers 10).",
            "9 Kadesh: the twelve spies; forty years in the wilderness (Numbers 13–14).",
            "10–11 Round Edom; Aaron dies on Mount Hor; the bronze serpent (Numbers 20–21).",
            "12 Plains of Moab: Balaam; Deuteronomy; Moses sees the land from Mount Nebo (Deuteronomy 34).",
            "13 Across the Jordan to Jericho (Joshua 3–6).",
        )
        var y = a.y0 + a.h + 30f
        for (e in events) {
            out += DrawnBox(LEFT, y, RIGHT - LEFT, e, size = 16f)
            y += 50f
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The life of Christ, with the Gospels side by side
    // ---------------------------------------------------------------------------------------------

    private class Event(val name: String, val mt: String = "", val mk: String = "", val lk: String = "", val jn: String = "")

    fun lifeOfChrist(): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "The main events in order, with where each Gospel tells them. Dates are approximate: born about 5 BC, baptised about AD 26, " +
                "crucified about AD 30 (some date it AD 33).", size = 17f)
        val events = listOf(
            Event("Birth in Bethlehem (c. 5 BC)", "Matt 1:18-25", "", "Luke 2:1-7"),
            Event("Shepherds; presented in the temple", "", "", "Luke 2:8-38"),
            Event("The Magi; flight to Egypt", "Matt 2:1-18"),
            Event("In the temple at twelve", "", "", "Luke 2:41-52"),
            Event("John the Baptist preaches", "Matt 3:1-12", "Mark 1:1-8", "Luke 3:1-18", "John 1:19-28"),
            Event("Baptism (c. AD 26)", "Matt 3:13-17", "Mark 1:9-11", "Luke 3:21-22"),
            Event("Temptation in the wilderness", "Matt 4:1-11", "Mark 1:12-13", "Luke 4:1-13"),
            Event("Water into wine at Cana", "", "", "", "John 2:1-11"),
            Event("Nicodemus", "", "", "", "John 3:1-21"),
            Event("The woman at the well", "", "", "", "John 4:1-42"),
            Event("Rejected at Nazareth", "", "", "Luke 4:16-30"),
            Event("Calling the fishermen", "Matt 4:18-22", "Mark 1:16-20", "Luke 5:1-11"),
            Event("Sermon on the Mount", "Matt 5-7", "", "Luke 6:20-49"),
            Event("The Twelve chosen", "Matt 10:1-4", "Mark 3:13-19", "Luke 6:12-16"),
            Event("Parables by the sea", "Matt 13:1-52", "Mark 4:1-34", "Luke 8:4-18"),
            Event("Feeding the five thousand", "Matt 14:13-21", "Mark 6:30-44", "Luke 9:10-17", "John 6:1-14"),
            Event("Peter's confession", "Matt 16:13-20", "Mark 8:27-30", "Luke 9:18-21"),
            Event("The transfiguration", "Matt 17:1-8", "Mark 9:2-8", "Luke 9:28-36"),
            Event("Lazarus raised", "", "", "", "John 11:1-44"),
            Event("Entry into Jerusalem", "Matt 21:1-11", "Mark 11:1-11", "Luke 19:28-44", "John 12:12-19"),
            Event("The Last Supper", "Matt 26:17-30", "Mark 14:12-26", "Luke 22:7-23", "John 13:1-30"),
            Event("Gethsemane and arrest", "Matt 26:36-56", "Mark 14:32-52", "Luke 22:39-53", "John 18:1-12"),
            Event("The trials", "Matt 26:57-27:26", "Mark 14:53-15:15", "Luke 22:54-23:25", "John 18:13-19:16"),
            Event("Crucifixion and burial (c. AD 30)", "Matt 27:27-66", "Mark 15:16-47", "Luke 23:26-56", "John 19:16-42"),
            Event("The resurrection", "Matt 28:1-10", "Mark 16:1-8", "Luke 24:1-12", "John 20:1-18"),
            Event("Appearances; the Great Commission", "Matt 28:16-20", "", "Luke 24:13-49", "John 20:19-21:25"),
            Event("The ascension (Acts 1:9-11)", "", "Mark 16:19", "Luke 24:50-53"),
        )
        val cols = listOf(LEFT, LEFT + 430f, LEFT + 630f, LEFT + 830f, LEFT + 1030f, RIGHT)
        val rowH = 46f
        var y = 110f
        val heads = listOf("Event", "Matthew", "Mark", "Luke", "John")
        heads.forEachIndexed { i, h -> out += DrawnBox(cols[i], y, cols[i + 1] - cols[i], h, size = 18f, color = 0xFF7A5C2E.toInt()) }
        y += rowH
        val top = y
        events.forEachIndexed { r, e ->
            val bg = if (r % 2 == 0) 0x18000000 else 0
            out += DrawnBox(cols[0], y, cols[1] - cols[0], e.name, size = 15f, background = bg)
            listOf(e.mt, e.mk, e.lk, e.jn).forEachIndexed { i, ref ->
                if (ref.isNotEmpty()) out += DrawnBox(cols[i + 1], y, cols[i + 2] - cols[i + 1], ref, size = 14f, background = bg)
            }
            y += rowH
        }
        for (x in cols.drop(1).dropLast(1)) out += line(x - 4f to top - 4f, x - 4f to y, color = GREY, width = 1.5f)
        out += line(LEFT to top - 4f, RIGHT to top - 4f, color = GREY, width = 1.5f)
        out += DrawnVerse(LEFT, y + 20f, RIGHT - LEFT, "John 20:31")
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // The twelve tribes
    // ---------------------------------------------------------------------------------------------

    fun twelveTribes(env: TemplateEnv): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "Jacob's twelve sons by their mothers (Genesis 29:31–30:24; 35:16-18), blessed in Genesis 49. Levi received cities, not land; " +
                "Joseph's sons Ephraim and Manasseh each became a tribe (Genesis 48). The lands are from Joshua 13–19.", size = 17f)
        val mothers = listOf(
            Triple("Leah", 0x40F48FB1, listOf("Reuben", "Simeon", "Levi", "Judah", "Issachar", "Zebulun")),
            Triple("Rachel", 0x4081C784, listOf("Joseph (Ephraim, Manasseh)", "Benjamin")),
            Triple("Bilhah, Rachel's servant", 0x4090CAF9, listOf("Dan", "Naphtali")),
            Triple("Zilpah, Leah's servant", 0x40FFD54F, listOf("Gad", "Asher")),
        )
        var y = 110f
        out += DrawnBox(LEFT + 500f, y, 220f, "Jacob (Israel)", size = 22f)
        val jacobBottom = y + 45f
        y += 90f
        val colW = (RIGHT - LEFT) / 4f
        mothers.forEachIndexed { i, (mother, bg, sons) ->
            val x = LEFT + i * colW
            out += line(LEFT + 610f to jacobBottom, LEFT + 610f to jacobBottom + 20f, x + colW / 2 to jacobBottom + 20f, x + colW / 2 to y)
            out += DrawnBox(x + 6f, y, colW - 12f, mother, size = 17f, background = bg)
            sons.forEachIndexed { j, son -> out += DrawnBox(x + 20f, y + 50f + j * 40f, colW - 30f, son, size = 16f) }
        }
        val a = MapArea(34.15f, 36.75f, 29.9f, 33.45f, LEFT + 150f, y + 330f, RIGHT - LEFT - 300f)
        out += mapLines(env, a)
        val lands = listOf(
            "Dan (north)" to (33.25f to 35.65f), "Naphtali" to (33.0f to 35.5f), "Asher" to (33.0f to 35.2f), "Zebulun" to (32.75f to 35.28f),
            "Issachar" to (32.6f to 35.45f), "Manasseh" to (32.35f to 35.1f), "Manasseh (east)" to (32.65f to 35.95f),
            "Ephraim" to (32.1f to 35.2f), "Gad" to (32.1f to 35.75f), "Dan" to (31.9f to 34.85f), "Benjamin" to (31.85f to 35.3f),
            "Reuben" to (31.6f to 35.75f), "Judah" to (31.4f to 35.0f), "Simeon" to (31.15f to 34.7f),
        )
        for ((name, ll) in lands) {
            val (x, yy) = a.at(ll.first, ll.second)
            out += DrawnBox(x - 60f, yy - 14f, 140f, name, size = 14f, background = 0x30FFFFFF)
        }
        out += DrawnVerse(LEFT, a.y0 + a.h + 30f, RIGHT - LEFT, "Genesis 49:10")
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Solomon's and Herod's temples
    // ---------------------------------------------------------------------------------------------

    fun temples(): List<Drawn> {
        val out = ArrayList<Drawn>()
        out += DrawnBox(LEFT, 0f, RIGHT - LEFT,
            "Plans from above, entrance to the east (right). Solomon's temple: 1 Kings 6–7; 2 Chronicles 3–4. " +
                "Herod's temple, where Jesus taught: described by Josephus and the Mishnah; the layout is approximate.", size = 17f)
        // Solomon's temple: the house 60 x 20 cubits, with a porch 10 deep and side rooms around.
        val c = 9f
        val sx = LEFT + 160f; val sy = 150f
        out += DrawnBox(LEFT, sy - 50f, 600f, "Solomon's temple (built c. 966–959 BC)", size = 20f, color = 0xFF7A5C2E.toInt())
        out += rect(sx - 5f * c, sy - 5f * c, 80f * c, 30f * c, GREY, 2f) // side rooms
        out += rect(sx, sy, 60f * c, 20f * c, INK, 4f)
        out += line(sx + 20f * c to sy, sx + 20f * c to sy + 20f * c, color = 0xFFC62828.toInt(), width = 4f)
        out += rect(sx + 60f * c, sy, 10f * c, 20f * c, INK, 3f) // porch
        out += DrawnBox(sx + 10f, sy + 60f, 20f * c - 20f, "Most Holy Place (20 cubits): the ark under two cherubim", size = 13f)
        out += DrawnBox(sx + 20f * c + 10f, sy + 60f, 40f * c - 20f, "Holy Place (40 cubits): golden altar, ten lampstands, ten tables", size = 13f)
        out += DrawnBox(sx + 60f * c + 4f, sy + 40f, 10f * c, "Porch", size = 13f)
        out += dot(sx + 70f * c + 14f, sy + 30f, INK, 7f); out += dot(sx + 70f * c + 14f, sy + 20f * c - 30f, INK, 7f)
        out += DrawnBox(sx + 70f * c + 26f, sy + 14f, 120f, "Boaz", size = 13f)
        out += DrawnBox(sx + 70f * c + 26f, sy + 20f * c - 46f, 120f, "Jachin", size = 13f)
        out += rect(sx + 80f * c, sy + 4f * c, 9f * c, 9f * c, 0xFF8D6E63.toInt(), 3f) // altar
        out += DrawnBox(sx + 80f * c, sy + 13f * c + 6f, 160f, "Bronze altar", size = 13f)
        out += dot(sx + 75f * c, sy + 25f * c, 0xFF8D6E63.toInt(), 22f) // the sea
        out += DrawnBox(sx + 75f * c + 28f, sy + 25f * c - 10f, 200f, "The bronze Sea", size = 13f)
        // Herod's temple: courts within courts.
        val hy = sy + 40f * c + 60f
        out += DrawnBox(LEFT, hy - 50f, 700f, "Herod's temple (rebuilt from 20 BC; destroyed AD 70)", size = 20f, color = 0xFF7A5C2E.toInt())
        val hx = LEFT + 20f; val hw = RIGHT - LEFT - 40f; val hh = 560f
        out += rect(hx, hy, hw, hh, INK, 3f)
        out += DrawnBox(hx + 10f, hy + 10f, 400f, "Court of the Gentiles (open to all)", size = 14f)
        out += rect(hx + 220f, hy + 110f, hw - 440f, hh - 220f, 0xFFC62828.toInt(), 2f)
        out += DrawnBox(hx + 230f, hy + 80f, 520f, "The Soreg: a low wall with warnings to Gentiles not to pass", size = 13f, color = 0xFFC62828.toInt())
        val inner = hx + 260f; val innerW = hw - 520f
        out += rect(inner + innerW * 0.62f, hy + 160f, innerW * 0.38f, hh - 320f, INK, 3f)
        out += DrawnBox(inner + innerW * 0.62f + 8f, hy + 170f, innerW * 0.38f - 16f, "Court of the Women (treasury, Mark 12:41-44)", size = 13f)
        out += DrawnBox(inner + innerW - 10f, hy + hh / 2 - 12f, 200f, "← Beautiful Gate (Acts 3:2)", size = 13f)
        out += rect(inner, hy + 140f, innerW * 0.62f, hh - 280f, INK, 3f)
        out += DrawnBox(inner + 8f, hy + 148f, innerW * 0.62f - 16f, "Court of Israel and Court of the Priests", size = 13f)
        out += rect(inner + 30f, hy + 220f, innerW * 0.30f, hh - 440f, INK, 4f)
        out += DrawnBox(inner + 36f, hy + 228f, innerW * 0.30f - 12f, "Sanctuary: Holy Place and Most Holy Place, veil between (Matthew 27:51)", size = 12f)
        out += rect(inner + innerW * 0.40f, hy + hh / 2 - 30f, 60f, 60f, 0xFF8D6E63.toInt(), 3f)
        out += DrawnBox(inner + innerW * 0.40f - 10f, hy + hh / 2 + 34f, 140f, "Altar", size = 13f)
        out += DrawnBox(hx + 10f, hy + hh - 40f, 600f, "Solomon's Porch along the east side (John 10:23; Acts 3:11)", size = 13f)
        out += DrawnVerse(LEFT, hy + hh + 30f, RIGHT - LEFT, "John 2:19-21")
        return out
    }
}

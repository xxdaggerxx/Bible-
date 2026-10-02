package com.biblestudy.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** What one of a tab's (at most two) panels shows (SPLIT-7). */
sealed class Slot {
    /** Bible panel [index] of the tab: a passage in a version, or a sketch page. */
    data class Bible(val index: Int) : Slot()
    /** A study view: cross-references, dictionary, topics and the rest. */
    data class Study(val kind: PaneKind) : Slot()
}

/**
 * One tab (TAB-1): one or two panels, side by side or one above the other (SPLIT-8).
 *
 * The Bible panels and the study views are kept apart so the rest of the app can keep working with
 * "the Bible panels" of the tab in front. A tab always has at least one Bible panel: when both of
 * its panels show study views, that panel is kept out of sight ([bibleHidden]) and holds the
 * passage the study views work with, which then stays put.
 */
class TabState {
    /** The Bible panels (one or two; a hidden one when both panels show study views). */
    val panels = mutableStateListOf<PanelState>()
    /** The study views shown (none, one, or two when the Bible panel is hidden). */
    val studies = mutableStateListOf<PaneKind>()
    /** With a Bible panel and a study view: the study view comes first (left, or top). */
    var studyFirst by mutableStateOf(false)
    /** True when every panel shows a study view and the Bible panel is out of sight. */
    var bibleHidden by mutableStateOf(false)
    /** Top and bottom (true), side by side (false), or by the screen's orientation (null). */
    var stacked by mutableStateOf<Boolean?>(null)
    /** The first panel's share of the space. */
    var split by mutableFloatStateOf(0.5f)
    /** Two Bible panels follow each other verse by verse (SPLIT-3). */
    var linked by mutableStateOf(false)
    var activePanel by mutableIntStateOf(0)
    /** A name given by the user (TAB-2), or null to name the tab after what it shows. */
    var name by mutableStateOf<String?>(null)
    /** Study views stay on this passage instead of following the Bible panel (pinned). */
    var pinned by mutableStateOf<PanelState?>(null)

    /** The panels on screen, in order. */
    fun slots(): List<Slot> = when {
        bibleHidden || panels.isEmpty() -> studies.take(2).map { Slot.Study(it) }
        studies.isNotEmpty() -> {
            val s = Slot.Study(studies[0]); val b = Slot.Bible(0)
            if (studyFirst) listOf(s, b) else listOf(b, s)
        }
        else -> panels.indices.take(2).map { Slot.Bible(it) }
    }

    /** How many panels are on screen. */
    val shown: Int get() = slots().size

    fun toJson(): JSONObject = JSONObject().apply {
        put("panels", JSONArray(panels.map { p ->
            JSONObject().put("b", p.book).put("c", p.chapter).put("v", p.version).put("t", p.topVerse)
                .put("zl", p.lastZoomRel.toDouble())
                .put("zland", (p.zoomRel["land"] ?: 1f).toDouble()).put("zport", (p.zoomRel["port"] ?: 1f).toDouble())
        }))
        put("studies", JSONArray(studies.map { it.name }))
        put("studyFirst", studyFirst)
        put("hidden", bibleHidden)
        put("stacked", when (stacked) { null -> -1; true -> 1; false -> 0 })
        put("split", split.toDouble())
        put("linked", linked)
        put("active", activePanel)
        name?.let { put("name", it) }
    }

    companion object {
        /**
         * Reads a tab saved by [toJson]; [place] checks a saved passage and version (book, chapter,
         * version) and returns a valid one.
         */
        fun fromJson(o: JSONObject, place: (Int, Int, String?) -> Triple<Int, Int, String>): TabState? = runCatching {
            TabState().apply {
                val ps = o.getJSONArray("panels")
                for (i in 0 until minOf(ps.length(), 2)) {
                    val j = ps.getJSONObject(i)
                    val (b, c, v) = place(j.optInt("b", 43), j.optInt("c", 3), j.optString("v", null))
                    panels.add(PanelState(b, c).apply {
                        version = v
                        val t = j.optInt("t", 1)
                        if (t > 1 && b == j.optInt("b") && c == j.optInt("c")) pendingVerse = t
                        lastZoomRel = j.optDouble("zl", 2.0).toFloat()
                        zoomRel["land"] = j.optDouble("zland", 1.0).toFloat()
                        zoomRel["port"] = j.optDouble("zport", 1.0).toFloat()
                    })
                }
                val ss = o.optJSONArray("studies") ?: JSONArray()
                for (i in 0 until ss.length()) PaneKind.entries.firstOrNull { it.name == ss.optString(i) }?.let { if (it !in studies) studies.add(it) }
                studyFirst = o.optBoolean("studyFirst")
                bibleHidden = o.optBoolean("hidden")
                stacked = when (o.optInt("stacked", -1)) { 1 -> true; 0 -> false; else -> null }
                split = o.optDouble("split", 0.5).toFloat().coerceIn(SPLIT_MIN, 1f - SPLIT_MIN)
                linked = o.optBoolean("linked")
                activePanel = o.optInt("active", 0)
                name = o.optString("name", "").ifBlank { null }
                normalize()
                if (panels.isEmpty()) return@runCatching null
            }
        }.getOrNull()

        /** Reads a list of tabs; null if [json] isn't one. */
        fun listFromJson(json: String, place: (Int, Int, String?) -> Triple<Int, Int, String>): List<TabState>? = runCatching {
            val a = JSONArray(json)
            List(a.length()) { fromJson(a.getJSONObject(it), place) }.filterNotNull()
        }.getOrNull()

        fun listToJson(tabs: List<TabState>): String = JSONArray(tabs.map { it.toJson() }).toString()

        /** The smallest share of the space either panel can be given. */
        const val SPLIT_MIN = 0.2f
    }

    /** Keeps the tab within its rules: at most two panels on screen, at least one Bible panel. */
    fun normalize() {
        while (studies.size > 2) studies.removeAt(studies.lastIndex)
        if (studies.size == 2) bibleHidden = true
        if (studies.isEmpty()) bibleHidden = false
        if (!bibleHidden && studies.isNotEmpty()) while (panels.size > 1) panels.removeAt(panels.lastIndex)
        while (panels.size > 2) panels.removeAt(panels.lastIndex)
        activePanel = activePanel.coerceIn(0, (panels.size - 1).coerceAtLeast(0))
        if (panels.size < 2) linked = false
    }
}

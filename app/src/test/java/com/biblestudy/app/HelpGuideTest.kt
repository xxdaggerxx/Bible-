package com.biblestudy.app

import com.biblestudy.app.ui.HelpGuide
import com.biblestudy.app.ui.PaneKind
import com.biblestudy.app.ui.SketchTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Help guide must keep up with the app: each feature named in the UI is explained there. */
class HelpGuideTest {
    private val text = File("src/main/assets/help/guide.md").readText()
    private val sections = HelpGuide.parse(text).second

    @Test
    fun parsesIntoSections() {
        assertTrue(sections.size > 20)
        assertTrue(sections.all { it.lines.isNotEmpty() })
        assertEquals(sections.size, sections.map { it.title }.toSet().size)
    }

    @Test
    fun explainsEveryStudyPaneChoiceAndReadyMadePage() {
        for (k in PaneKind.entries) assertTrue("Help doesn't mention ${k.label}", text.contains(k.label))
        // Every view is in exactly one of the panel menu's groups.
        assertEquals(PaneKind.entries.sorted(), PaneKind.groups.flatMap { it.second }.sorted())
        for (t in SketchTemplates.all) assertTrue("Help doesn't mention ${t.name}", text.contains(t.name))
    }

    @Test
    fun explainsTheMenusAndNewFeatures() {
        for (s in listOf(
            "Reading stats", "Export chapter as PDF", "Back up my notes", "Settings", "Text box", "Sketch page", "Verse card",
            "Person or place card", "Read mode", "Lasso", "Layers", "Family tree", "Words of Jesus in red", "Compare versions",
            "Mark word differences", "Save what\u2019s shown", "Import a Bible", "Link to another passage", "Restore from backup",
            "Ready-made pages", "Unlink", "Put back deleted ready-made pages", "Writing sounds",
            "Write full screen", "Hover",
            "New tab", "Open in new tab", "Add a panel beside", "Top and bottom", "Keep on this passage", "Close panel",
            "Writing on study views", "About the book", "New tab", "Verse details in a panel", "Back to the verse", "Ask AI", "Save key", "Save sites", "Use the suggested sites", "New chat", "Edit", "Verses", "Copy", "Only search my sites", "This verse", "Notes, search and AI", "Reading", "Whole chapter beside the text", "little window", "Try again", "Recently read", "Bookmarked", "Clear this list", "Tabs remember where you were", "Start fresh", "Add a note", "Jump back several steps", "AI Commentary", "Where Christians differ", "Other views", "Chapter at a glance", "Key verse", "Where it fits", "Hard words explained", "Read more", "About this book", "Margin or words?", "Add an online Bible", "The ESV", "The NLT", "Save for offline", "Online Bibles", "Ink & Word AI Commentary", "Bible aids", "People & places", "Customs & feasts", "Symbols & numbers", "More", "On a phone", "The toolbar slides", "Two panels, top and bottom", "Margins fold away", "Online Bibles · tap to add", "Text size",
        )) assertTrue("Help doesn't mention $s", text.contains(s, ignoreCase = true))
    }

    @Test
    fun boldAndItalic() {
        val a = HelpGuide.inline("Tap **Save** then *Done*, or lov\\*.")
        assertEquals("Tap Save then Done, or lov*.", a.text)
        assertTrue(a.spanStyles.any { a.text.substring(it.start, it.end) == "Save" && it.item.fontWeight != null })
    }
}

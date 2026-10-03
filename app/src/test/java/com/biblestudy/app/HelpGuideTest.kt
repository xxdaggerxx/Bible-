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
            "Writing on study views", "About the book", "New tab", "Verse details in a panel", "Back to the verse", "Ask AI", "Save key", "Save sites", "Use the suggested sites", "New chat", "Edit", "Verses", "Copy",
        )) assertTrue("Help doesn't mention $s", text.contains(s, ignoreCase = true))
    }

    @Test
    fun boldAndItalic() {
        val a = HelpGuide.inline("Tap **Save** then *Done*, or lov\\*.")
        assertEquals("Tap Save then Done, or lov*.", a.text)
        assertTrue(a.spanStyles.any { a.text.substring(it.start, it.end) == "Save" && it.item.fontWeight != null })
    }
}

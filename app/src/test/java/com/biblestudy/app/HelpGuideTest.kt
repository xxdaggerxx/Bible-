package com.biblestudy.app

import com.biblestudy.app.ui.HelpGuide
import com.biblestudy.app.ui.HelpTopic
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
    fun everyPictureIsThereAndUsed() {
        val named = sections.flatMap { it.lines }.mapNotNull { HelpGuide.picture(it) }
        assertTrue("Help should have pictures", named.size >= 20)
        val files = File("src/main/assets/help/img").list()!!.toSet()
        for ((file, caption) in named) {
            assertTrue("No picture $file", file in files)
            assertTrue("$file has no caption", caption.isNotBlank())
        }
        assertEquals("Pictures not in the guide", emptySet<String>(), files - named.map { it.first }.toSet())
        // Search finds a picture by its caption, not its file name.
        assertTrue(sections.any { "Paul's missionary journeys" in it.text })
        assertTrue(sections.none { ".webp" in it.text })
    }

    @Test
    fun everyHelpButtonOpensASection() {
        val titles = sections.map { it.title }.toSet()
        val used = HelpTopic.all + PaneKind.entries.map { HelpTopic.of(it) } + HelpTopic.of(null)
        for (t in used) assertTrue("No Help section called $t", t in titles)
    }

    @Test
    fun parsesIntoSections() {
        assertTrue(sections.size > 20)
        assertTrue(sections.all { it.lines.isNotEmpty() })
        assertEquals(sections.size, sections.map { it.title }.toSet().size)
    }

    @Test
    fun explainsEveryStudyPaneChoiceAndReadyMadePage() {
        for (k in PaneKind.entries - PaneKind.offMenu) assertTrue("Help doesn't mention ${k.label}", text.contains(k.label))
        // Every view is in exactly one of the panel menu's groups.
        assertEquals(PaneKind.entries.sorted(), (PaneKind.main + PaneKind.folders.flatMap { it.second } + PaneKind.offMenu).sorted())
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
            "Writing on study views", "New tab", "Verse details in a panel", "Back to the verse", "Ask AI", "Save key", "Save sites", "Use the suggested sites", "New chat", "Edit", "Verses", "Copy", "Only search my sites", "tap it to open it", "Whole chapter beside the text", "little window", "Try again", "Recently read", "Bookmarked", "Clear this list", "Tabs remember where you were", "Start fresh", "Add a note", "Jump back several steps", "AI Commentary", "Where Christians differ", "Other views", "Chapter at a glance", "Key verse", "Where it fits", "Hard words explained", "Read more", "About this book", "Margin or words?", "Add an online Bible", "The ESV", "The NLT", "Online Bibles", "Ink & Word AI Commentary", "Reflections", "Ask yourself", "See also", "Also in", "Bible aids", "People & places", "Customs & feasts", "Symbols & numbers", "More", "On a phone", "The toolbar slides", "Two panels, top and bottom", "Margins fold away", "Online Bibles · tap to add", "Text size", "Sync with Google Drive", "Sync now", "Sign in again", "arrow at the toolbar", "times are added together", "Milestones", "New sketch page", "Copy reference", "Copy verse", "Points to Christ", "Ink always shows on the page", "Through the Bible", "Daily reading", "Every chapter", "Help: panels", "small ?", "Seeing it sync", "⋮ → Layers", "Layers", "Highlights", "Start fresh", "They show while you're drawing",
        )) assertTrue("Help doesn't mention $s", text.contains(s, ignoreCase = true))
    }

    @Test
    fun boldAndItalic() {
        val a = HelpGuide.inline("Tap **Save** then *Done*, or lov\\*.")
        assertEquals("Tap Save then Done, or lov*.", a.text)
        assertTrue(a.spanStyles.any { a.text.substring(it.start, it.end) == "Save" && it.item.fontWeight != null })
    }
}

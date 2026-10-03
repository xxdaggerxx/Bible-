package com.biblestudy.app

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.biblestudy.app.data.AiChat
import com.biblestudy.app.data.ChatResult
import com.biblestudy.app.data.ChatSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI chat shows only answers grounded in the chosen sites (AI-2, AI-3). */
class AiChatTest {
    private val sites = listOf("gotquestions.org", "ligonier.org")

    private fun message(content: String): Message = jsonMapper().readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"${AiChat.MODEL}","stop_reason":"end_turn","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":10},"content":[$content]}""",
        Message::class.java,
    )

    private val search = """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"grace"}},
        {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
          {"type":"web_search_result","url":"https://www.gotquestions.org/grace.html","title":"What is grace?","encrypted_content":"x","page_age":null}]}"""

    private fun cited(text: String, url: String, title: String) =
        """{"type":"text","text":"$text","citations":[{"type":"web_search_result_location","url":"$url","title":"$title","encrypted_index":"x","cited_text":"grace"}]}"""

    @Test
    fun citedAnswerKeepsItsSourcesAndMarks() {
        val m = message("$search, {\"type\":\"text\",\"text\":\"Here is what they say. \"}, " + cited("Grace is unearned favour (Ephesians 2:8).", "https://www.gotquestions.org/grace.html", "What is grace?"))
        val r = AiChat.read(m.content(), sites) as ChatResult.Answer
        assertEquals("Here is what they say. Grace is unearned favour (Ephesians 2:8).[1]", r.text)
        assertEquals(listOf(ChatSource("What is grace?", "https://www.gotquestions.org/grace.html")), r.sources)
        assertEquals("gotquestions.org", r.sources[0].site)
    }

    @Test
    fun noSearchOrNoCitationsMeansNoAnswer() {
        // Answered from its own knowledge, without searching.
        assertEquals(ChatResult.NotFound, AiChat.read(message("{\"type\":\"text\",\"text\":\"Grace is favour.\"}").content(), sites))
        // Searched, but cited nothing.
        assertEquals(ChatResult.NotFound, AiChat.read(message("$search, {\"type\":\"text\",\"text\":\"Grace is favour.\"}").content(), sites))
        // Cited a page that isn't on the chosen sites.
        assertEquals(ChatResult.NotFound, AiChat.read(message("$search, " + cited("Grace is favour.", "https://example.com/g", "Elsewhere")).content(), sites))
    }

    @Test
    fun requestSearchesOnlyTheChosenSites() {
        val p = AiChat.params(sites, listOf(com.biblestudy.app.data.ChatTurn(true, "Q1"), com.biblestudy.app.data.ChatTurn(false, "A1")), "Q2")
        val json = jsonMapper().writeValueAsString(p._body())
        assertTrue(json, json.contains("\"type\":\"web_search_20260209\""))
        assertTrue(json, json.contains("\"allowed_domains\":[\"gotquestions.org\",\"ligonier.org\"]"))
        assertTrue(json, json.contains("\"model\":\"claude-opus-5-5\""))
        assertTrue(json, json.contains("\"fallbacks\":\"default\""))
        assertEquals(3, p.messages().size)
        assertEquals("server-side-fallback-2026-07-01", p._headers().values("anthropic-beta").single())
    }

    @Test
    fun siteListsAreCleaned() {
        assertEquals(listOf("ligonier.org", "biblehub.com"), AiChat.cleanSites(listOf(" https://www.Ligonier.org/ ", "", "biblehub.com", "not a site", "ligonier.org")))
        assertTrue(AiChat.allowed(ChatSource("t", "https://learn.ligonier.org/x"), sites))
        assertFalse(AiChat.allowed(ChatSource("t", "https://notligonier.org/x"), sites))
    }
}

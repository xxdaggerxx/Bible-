package com.biblestudy.app

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.biblestudy.app.data.AiChat
import com.biblestudy.app.data.ChatResult
import com.biblestudy.app.data.ChatSource
import com.biblestudy.app.data.ChatTurn
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

    /** A stand-in for the Claude API: answers with [replies] in turn and keeps each request's body. */
    private class FakeApi(vararg replies: Pair<Int, String>) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        /** The bodies of the requests received so far. */
        val bodies: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val url = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            runCatching {
                while (true) socket.accept().use { c ->
                    val input = java.io.BufferedInputStream(c.getInputStream())
                    // Headers, then a body of Content-Length bytes.
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    bodies += String(input.readNBytes(length))
                    val (code, body) = replies[minOf(bodies.size, replies.size) - 1]
                    val out = body.toByteArray()
                    c.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n").toByteArray() + out)
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() = socket.close()
    }

    private fun reply(content: String, stop: String = "end_turn") =
        200 to """{"id":"msg_1","type":"message","role":"assistant","model":"${AiChat.MODEL}","stop_reason":"$stop","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":10},"content":[$content]}"""

    private val hasVerses = { t: String -> Regex("\\d+:\\d+").containsMatchIn(t) }

    @Test
    fun anAnswerWithoutVersesAsksOnceMoreForThem() {
        FakeApi(
            reply("$search, " + cited("Holiness means being set apart for God.", "https://www.gotquestions.org/grace.html", "What is grace?")),
            reply("$search, " + cited("Key verses: Leviticus 11:44; 1 Peter 1:15-16.", "https://www.ligonier.org/holy", "Be holy")),
        ).use { api ->
            val r = AiChat.claude(api.url).ask("sk-test", sites, emptyList(), "What is holiness?", hasVerses) as ChatResult.Answer
            assertEquals(2, api.bodies.size)
            assertTrue(api.bodies[1], api.bodies[1].contains(AiChat.VERSES_PLEASE) && api.bodies[1].contains("web_search_tool_result"))
            assertEquals("Holiness means being set apart for God.[1]\n\nKey verses: Leviticus 11:44; 1 Peter 1:15-16.[2]", r.text)
            assertEquals(2, r.sources.size)
        }
    }

    @Test
    fun aPausedSearchCarriesOnAndBadKeysAreExplained() {
        FakeApi(
            reply(search, stop = "pause_turn"),
            reply(cited("Holy means set apart (Leviticus 11:44).", "https://www.gotquestions.org/grace.html", "What is grace?")),
        ).use { api ->
            val r = AiChat.claude(api.url).ask("sk-test", sites, listOf(ChatTurn(true, "Q1"), ChatTurn(false, "A1")), "Q2", hasVerses) as ChatResult.Answer
            assertEquals(2, api.bodies.size)
            assertTrue(api.bodies[0], api.bodies[0].contains("\"allowed_domains\":[\"gotquestions.org\",\"ligonier.org\"]"))
            assertTrue(api.bodies[1], api.bodies[1].contains("server_tool_use") && !api.bodies[1].contains("null,\"type\":\"server"))
            assertEquals("Holy means set apart (Leviticus 11:44).[1]", r.text)
        }
        FakeApi(401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").use { api ->
            val r = AiChat.claude(api.url).ask("bad", sites, emptyList(), "Q", hasVerses) as ChatResult.Failed
            assertTrue(r.message, r.message.contains("API key wasn't accepted"))
        }
    }

    @Test
    fun siteListsAreCleaned() {
        assertEquals(listOf("ligonier.org", "biblehub.com"), AiChat.cleanSites(listOf(" https://www.Ligonier.org/ ", "", "biblehub.com", "not a site", "ligonier.org")))
        assertTrue(AiChat.allowed(ChatSource("t", "https://learn.ligonier.org/x"), sites))
        assertFalse(AiChat.allowed(ChatSource("t", "https://notligonier.org/x"), sites))
    }
}

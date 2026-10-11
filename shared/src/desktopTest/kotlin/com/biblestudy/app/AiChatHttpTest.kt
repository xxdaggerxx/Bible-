package com.biblestudy.app

import com.biblestudy.app.data.AiChat
import com.biblestudy.app.data.AiChatHttp
import com.biblestudy.app.data.ChatResult
import com.biblestudy.app.data.ChatSource
import com.biblestudy.app.data.ChatTurn
import com.biblestudy.app.platform.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The chat over plain HTTP (used on iOS) behaves as the SDK version does on Android (AI-2, AI-3). */
class AiChatHttpTest {
    private val sites = listOf("gotquestions.org", "ligonier.org")

    private val search = """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"grace"}},
        {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
          {"type":"web_search_result","url":"https://www.gotquestions.org/grace.html","title":"What is grace?","encrypted_content":"x","page_age":null}]}"""

    private fun cited(text: String, url: String, title: String) =
        """{"type":"text","text":"$text","citations":[{"type":"web_search_result_location","url":"$url","title":"$title","encrypted_index":"x","cited_text":"grace"}]}"""

    private fun blocks(content: String): List<JSONObject> =
        JSONObject("""{"content":[$content]}""").getJSONArray("content").let { a -> List(a.length()) { a.getJSONObject(it) } }

    @Test
    fun citedAnswersKeepTheirSourcesAndUncitedOnesAreDropped() {
        val r = AiChatHttp.readParts(listOf(blocks("$search, {\"type\":\"text\",\"text\":\"Here is what they say. \"}, " +
            cited("Grace is unearned favour (Ephesians 2:8).", "https://www.gotquestions.org/grace.html", "What is grace?"))), sites, true) as ChatResult.Answer
        assertEquals("Here is what they say. Grace is unearned favour (Ephesians 2:8).[1]", r.text)
        assertEquals(listOf(ChatSource("What is grace?", "https://www.gotquestions.org/grace.html")), r.sources)
        assertEquals(ChatResult.NotFound(), AiChatHttp.readParts(listOf(blocks("{\"type\":\"text\",\"text\":\"Grace is favour.\"}")), sites, true))
        assertEquals(ChatResult.NotFound(listOf("grace")), AiChatHttp.readParts(listOf(blocks("$search, " + cited("x", "https://example.com/g", "E"))), sites, true))
        assertEquals("example.com", (AiChatHttp.readParts(listOf(blocks("$search, " + cited("x", "https://example.com/g", "E"))), sites, false) as ChatResult.Answer).sources.single().site)
    }

    @Test
    fun theRequestIsTheSameAsTheSdks() {
        val json = AiChatHttp.body(sites, true, listOf(ChatTurn(true, "Q1"), ChatTurn(false, "A1")), "Q2").toString()
        assertTrue(json.contains("\"allowed_callers\":[\"direct\"]"), json)
        assertTrue(json.contains("\"type\":\"web_search_20260209\""), json)
        assertTrue(json.contains("\"allowed_domains\":[\"gotquestions.org\",\"ligonier.org\"]"), json)
        assertTrue(json.contains("\"model\":\"claude-opus-5-5\""), json)
        assertTrue(json.contains("\"fallbacks\":\"default\""), json)
        assertEquals(3, JSONObject(json).getJSONArray("messages").length())
        assertFalse(AiChatHttp.body(sites, false, emptyList(), "Q").toString().contains("allowed_domains"))
    }

    /** A stand-in for the Claude API: answers with [replies] in turn and keeps each request. */
    private class FakeApi(vararg replies: Pair<Int, String>) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        val bodies: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val heads: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val url = "http://127.0.0.1:${socket.localPort}"
        init {
            Thread {
                runCatching {
                    while (true) socket.accept().use { c ->
                        val input = java.io.BufferedInputStream(c.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        heads += head.toString()
                        bodies += String(input.readNBytes(length))
                        val (code, body) = replies[minOf(bodies.size, replies.size) - 1]
                        val out = body.toByteArray()
                        c.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n").toByteArray() + out)
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        override fun close() = socket.close()
    }

    private fun reply(content: String, stop: String = "end_turn") =
        200 to """{"id":"msg_1","type":"message","role":"assistant","model":"${AiChat.MODEL}","stop_reason":"$stop","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":10},"content":[$content]}"""

    private val hasVerses = { t: String -> Regex("\\d+:\\d+").containsMatchIn(t) }

    @Test
    fun aPausedSearchCarriesOnAndVersesAreAskedFor() {
        FakeApi(
            reply(search, stop = "pause_turn"),
            reply(cited("Holiness means being set apart for God.", "https://www.gotquestions.org/grace.html", "What is grace?")),
            reply("$search, " + cited("Key verses: Leviticus 11:44.", "https://www.ligonier.org/holy", "Be holy")),
        ).use { api ->
            val r = AiChatHttp.service(api.url).ask("sk-test", sites, true, emptyList(), "What is holiness?", hasVerses) as ChatResult.Answer
            assertEquals(3, api.bodies.size)
            assertTrue(api.heads[0].contains("x-api-key: sk-test", ignoreCase = true) && api.heads[0].contains("server-side-fallback-2026-07-01"), api.heads[0])
            assertTrue(api.bodies[1].contains("server_tool_use") && api.bodies[1].contains("\"role\":\"assistant\""), api.bodies[1])
            assertTrue(api.bodies[2].contains(AiChat.VERSES_PLEASE), api.bodies[2])
            assertEquals("Holiness means being set apart for God.[1]\n\nKey verses: Leviticus 11:44.[2]", r.text)
        }
    }

    @Test
    fun badKeysAndRefusalsAreExplained() {
        FakeApi(401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").use { api ->
            val r = AiChatHttp.service(api.url).ask("bad", sites, true, emptyList(), "Q", hasVerses) as ChatResult.Failed
            assertTrue(r.message.contains("API key wasn't accepted"), r.message)
        }
        FakeApi(reply("", stop = "refusal")).use { api ->
            val r = AiChatHttp.service(api.url).ask("k", sites, true, emptyList(), "Q", hasVerses) as ChatResult.Failed
            assertTrue(r.message.contains("declined"), r.message)
        }
    }
}

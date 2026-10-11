package com.biblestudy.app.data

import com.biblestudy.app.data.AiChat.MODEL
import com.biblestudy.app.data.AiChat.VERSES_PLEASE
import com.biblestudy.app.data.AiChat.allowed
import com.biblestudy.app.data.AiChat.system
import com.biblestudy.app.platform.JSONArray
import com.biblestudy.app.platform.JSONObject
import com.biblestudy.app.platform.httpRequest

/**
 * The chat's request and reply as plain HTTP calls to the Claude API, for iOS, where Anthropic's
 * Java SDK can't run. The same request as [AiChatSdk] (Android and computers): Claude with web
 * search straight into the answer, a paused search carried on, server-side fallback, and one more
 * request for verses when the answer names none.
 */
object AiChatHttp {
    private const val API = "https://api.anthropic.com"

    fun service(baseUrl: String?): ChatService = ChatService { apiKey, sites, onlySites, history, question, hasVerses ->
        try {
            val url = (baseUrl ?: API).trimEnd('/') + "/v1/messages"
            // Sends the request; a long search can pause, so its turn is sent back to carry on.
            fun run(start: JSONObject): Pair<JSONObject, List<JSONObject>> {
                val body = start
                val msgs = ArrayList<JSONObject>()
                var msg = send(url, apiKey, body)
                msgs += msg
                var resumed = 0
                while (msg.optString("stop_reason") == "pause_turn" && resumed < 4) {
                    body.getJSONArray("messages").put(assistant(msg))
                    msg = send(url, apiKey, body)
                    msgs += msg
                    resumed++
                }
                body.getJSONArray("messages").put(assistant(msg))
                return body to msgs
            }
            val refused = { m: List<JSONObject> -> m.last().optString("stop_reason") == "refusal" }
            val (sent, first) = run(body(sites, onlySites, history, question))
            if (refused(first)) return@ChatService ChatResult.Failed("The AI declined to answer this question.")
            val parts = arrayListOf(first.flatMap { it.content() })
            val answer = readParts(parts, sites, onlySites)
            if (answer !is ChatResult.Answer || hasVerses(answer.text)) return@ChatService answer
            // No verses named: ask once more for the ones its sources give.
            sent.getJSONArray("messages").put(JSONObject().put("role", "user").put("content", VERSES_PLEASE))
            val (_, more) = run(sent)
            if (!refused(more)) parts += more.flatMap { it.content() }
            readParts(parts, sites, onlySites)
        } catch (e: ApiError) {
            when (e.status) {
                401 -> ChatResult.Failed("Your API key wasn't accepted. Check it in Settings → AI chat.")
                403 -> ChatResult.Failed("Your API key isn't allowed to do this. Check your Claude account.")
                429 -> ChatResult.Failed("Too many questions at once. Wait a minute and try again.")
                else -> ChatResult.Failed("The AI service had a problem (${e.status}). Try again.")
            }
        } catch (e: okio.IOException) {
            ChatResult.Failed("Couldn't reach the AI. Check your internet connection.")
        }
    }

    private class ApiError(val status: Int) : Exception("HTTP $status")

    private fun send(url: String, apiKey: String, body: JSONObject): JSONObject {
        val r = httpRequest(
            url, "POST",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                // If a safeguard declines, the server retries on a suitable model (server-side fallback).
                "anthropic-beta" to "server-side-fallback-2026-07-01",
                "content-type" to "application/json",
            ),
            body.toString().encodeToByteArray(),
            connectTimeoutMs = 30_000, readTimeoutMs = 600_000,
        )
        if (r.status !in 200..299) throw ApiError(r.status)
        return JSONObject(r.text)
    }

    /** The reply as the next assistant turn, its content blocks sent back as they came. */
    private fun assistant(msg: JSONObject) = JSONObject().put("role", "assistant").put("content", msg.getJSONArray("content"))

    private fun JSONObject.content(): List<JSONObject> = optJSONArray("content")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()

    /** The request: Claude with web search, the conversation so far, and [question]. */
    fun body(sites: List<String>, onlySites: Boolean, history: List<ChatTurn>, question: String): JSONObject {
        val search = JSONObject()
            .put("type", "web_search_20260209")
            .put("name", "web_search")
            .put("max_uses", 6)
            // Results straight into the answer, so every statement can cite its page (AI-3).
            .put("allowed_callers", JSONArray().put("direct"))
        if (onlySites) search.put("allowed_domains", JSONArray(sites))
        val messages = JSONArray()
        for (t in history) messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", t.text))
        messages.put(JSONObject().put("role", "user").put("content", question))
        return JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 16000)
            .put("system", system(sites, onlySites))
            .put("tools", JSONArray().put(search))
            .put("fallbacks", "default")
            .put("messages", messages)
    }

    /** As [AiChatSdk.readParts]: the answer with [n] marks after each cited part, and the pages cited. */
    fun readParts(parts: List<List<JSONObject>>, sites: List<String>, onlySites: Boolean): ChatResult {
        var results = 0
        val queries = ArrayList<String>()
        val sources = ArrayList<ChatSource>()
        val text = StringBuilder()
        for ((n, blocks) in parts.withIndex()) {
            if (n > 0 && text.isNotBlank()) text.append("\n\n")
            for (b in blocks) {
                when (b.optString("type")) {
                    "server_tool_use" -> b.optJSONObject("input")?.optString("query")?.ifEmpty { null }?.let { queries += it }
                    "web_search_tool_result" -> b.optJSONArray("content")?.let { a ->
                        results += (0 until a.length()).count { a.optJSONObject(it)?.optString("type") == "web_search_result" }
                    }
                    "text" -> {
                        text.append(b.optString("text"))
                        val cites = b.optJSONArray("citations")
                        val marks = (0 until (cites?.length() ?: 0)).mapNotNull { i ->
                            val c = cites!!.optJSONObject(i)?.takeIf { it.optString("type") == "web_search_result_location" } ?: return@mapNotNull null
                            val url = c.optString("url")
                            val s = ChatSource(c.optString("title").ifEmpty { url }, url)
                            if (onlySites && !allowed(s, sites)) return@mapNotNull null
                            val at = sources.indexOfFirst { it.url == s.url }.takeIf { it >= 0 } ?: run { sources += s; sources.lastIndex }
                            at + 1
                        }.distinct()
                        if (marks.isNotEmpty()) text.append(marks.joinToString("") { "[$it]" })
                    }
                }
            }
        }
        if (results == 0 || sources.isEmpty() || text.isBlank()) return ChatResult.NotFound(queries.distinct())
        return ChatResult.Answer(text.toString().trim(), sources)
    }
}

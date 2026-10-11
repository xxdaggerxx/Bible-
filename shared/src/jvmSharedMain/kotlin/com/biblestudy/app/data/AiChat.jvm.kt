package com.biblestudy.app.data

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20260209
import com.biblestudy.app.data.AiChat.MODEL
import com.biblestudy.app.data.AiChat.VERSES_PLEASE
import com.biblestudy.app.data.AiChat.allowed
import com.biblestudy.app.data.AiChat.system

/** The chat's request and reply, through Anthropic's Java SDK (Android and computers). */
object AiChatSdk {
    /** The service, sending requests to [baseUrl] (tests use a local stand-in) or Anthropic. */
    fun service(baseUrl: String?): ChatService = ChatService { apiKey, sites, onlySites, history, question, hasVerses ->
        try {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).apply { baseUrl?.let { baseUrl(it) } }.build()
            // Sends the request; a long search can pause, so its turn is sent back to carry on.
            fun run(start: MessageCreateParams): Pair<MessageCreateParams, List<Message>> {
                var params = start
                val msgs = ArrayList<Message>()
                var msg = client.messages().create(params)
                msgs += msg
                var resumed = 0
                while (msg.stopReason().orElse(null) == StopReason.PAUSE_TURN && resumed < 4) {
                    params = params.toBuilder().addMessage(msg).build()
                    msg = client.messages().create(params)
                    msgs += msg
                    resumed++
                }
                return params.toBuilder().addMessage(msg).build() to msgs
            }
            val refused = { m: List<Message> -> m.last().stopReason().orElse(null) == StopReason.REFUSAL }
            val (sent, first) = run(params(sites, onlySites, history, question))
            if (refused(first)) return@ChatService ChatResult.Failed("The AI declined to answer this question.")
            val parts = arrayListOf(first.flatMap { it.content() })
            val answer = readParts(parts, sites, onlySites)
            if (answer !is ChatResult.Answer || hasVerses(answer.text)) return@ChatService answer
            // No verses named: ask once more for the ones its sources give.
            val (_, more) = run(sent.toBuilder().addUserMessage(VERSES_PLEASE).build())
            if (!refused(more)) parts += more.flatMap { it.content() }
            readParts(parts, sites, onlySites)
        } catch (e: UnauthorizedException) {
            ChatResult.Failed("Your API key wasn't accepted. Check it in Settings → AI chat.")
        } catch (e: PermissionDeniedException) {
            ChatResult.Failed("Your API key isn't allowed to do this. Check your Claude account.")
        } catch (e: RateLimitException) {
            ChatResult.Failed("Too many questions at once. Wait a minute and try again.")
        } catch (e: AnthropicServiceException) {
            ChatResult.Failed("The AI service had a problem (${e.statusCode()}). Try again.")
        } catch (e: AnthropicIoException) {
            ChatResult.Failed("Couldn't reach the AI. Check your internet connection.")
        }
    }

    /** The request: Claude with web search, the conversation so far, and [question]. */
    fun params(sites: List<String>, onlySites: Boolean, history: List<ChatTurn>, question: String): MessageCreateParams {
        val search = WebSearchTool20260209.builder()
            .maxUses(6L)
            // Results straight into the answer, so every statement can cite its page (AI-3).
            // (Filtering them through code first left answers without citations.)
            .addAllowedCaller(WebSearchTool20260209.AllowedCaller.DIRECT)
        if (onlySites) search.allowedDomains(sites)
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(system(sites, onlySites))
            .addTool(search.build())
            // If a safeguard declines, the server retries on a suitable model (server-side fallback).
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        for (t in history) if (t.user) builder.addUserMessage(t.text) else builder.addAssistantMessage(t.text)
        return builder.addUserMessage(question).build()
    }

    /**
     * Turns the reply into an answer: its text with a [n] mark after each cited part, and the
     * pages cited. With no search results or no citations (from the chosen sites, when it may only
     * use those), nothing is shown.
     */
    fun read(blocks: List<ContentBlock>, sites: List<String>, onlySites: Boolean = true): ChatResult = readParts(listOf(blocks), sites, onlySites)

    /** As [read], for a reply in several [parts] (an answer and its follow-up), one after another. */
    fun readParts(parts: List<List<ContentBlock>>, sites: List<String>, onlySites: Boolean): ChatResult {
        var results = 0
        val queries = ArrayList<String>()
        val sources = ArrayList<ChatSource>()
        val text = StringBuilder()
        for ((n, blocks) in parts.withIndex()) {
            if (n > 0 && text.isNotBlank()) text.append("\n\n")
            for (b in blocks) {
                b.serverToolUse().ifPresent { u ->
                    runCatching { u._input().convert(Map::class.java)?.get("query") as? String }.getOrNull()?.let { queries += it }
                }
                b.webSearchToolResult().ifPresent { r ->
                    r.content().resultBlocks().ifPresent { results += it.size }
                }
                b.text().ifPresent { t ->
                    text.append(t.text())
                    val marks = t.citations().orElse(emptyList()).mapNotNull { c ->
                        c.webSearchResultLocation().orElse(null)?.let { w ->
                            val s = ChatSource(w.title().orElse(null) ?: w.url(), w.url())
                            if (onlySites && !allowed(s, sites)) return@mapNotNull null
                            val at = sources.indexOfFirst { it.url == s.url }.takeIf { it >= 0 } ?: run { sources += s; sources.lastIndex }
                            at + 1
                        }
                    }.distinct()
                    if (marks.isNotEmpty()) text.append(marks.joinToString("") { "[$it]" })
                }
            }
        }
        if (results == 0 || sources.isEmpty() || text.isBlank()) return ChatResult.NotFound(queries.distinct())
        return ChatResult.Answer(text.toString().trim(), sources)
    }

}

actual fun claudeChat(baseUrl: String?): ChatService = AiChatSdk.service(baseUrl)

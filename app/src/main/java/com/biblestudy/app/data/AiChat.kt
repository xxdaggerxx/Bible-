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
import java.net.URI

/** One web page an answer drew on (AI-3). */
data class ChatSource(val title: String, val url: String) {
    val site: String get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}

/** One earlier message in the conversation, as text. */
data class ChatTurn(val user: Boolean, val text: String)

sealed class ChatResult {
    /** A summary of what the sites say; [text] carries [n] marks pointing into [sources]. */
    data class Answer(val text: String, val sources: List<ChatSource>) : ChatResult()
    /** The search found nothing on the chosen sites, so there is no answer (AI-2). */
    data object NotFound : ChatResult()
    data class Failed(val message: String) : ChatResult()
}

/** Asks the online AI (AI-1). Swapped for a stand-in in tests. */
fun interface ChatService {
    /**
     * Blocking: call off the main thread. [hasVerses] tells whether a text names a Bible passage;
     * an answer must (AI-8).
     */
    fun ask(apiKey: String, sites: List<String>, history: List<ChatTurn>, question: String, hasVerses: (String) -> Boolean): ChatResult
}

/**
 * The online AI chat (AI-1 to AI-5): Claude searches only the chosen sites with the web search
 * tool and reports what they say. It may not answer from its own knowledge: an answer with no
 * search results or no citations is not shown (AI-2).
 */
object AiChat {
    const val MODEL = "claude-opus-5-5"

    /** The sites searched until the list is changed in Settings (AI-4). */
    val DEFAULT_SITES = listOf(
        "gotquestions.org", "ligonier.org", "desiringgod.org", "thegospelcoalition.org",
        "blueletterbible.org", "biblehub.com", "ccel.org", "monergism.com",
    )

    /** Cleans a typed site list: plain host names, at most 64 (the search tool's limit). */
    fun cleanSites(lines: List<String>): List<String> = lines
        .map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/') }
        .filter { it.contains('.') && !it.contains(' ') }
        .distinct()
        .take(64)

    val SYSTEM = """
        You are the research helper in a Bible study app. Your only job is to search the allowed websites with the web_search tool, read what they say, and report it back.

        Rules:
        - Search before every answer, including follow-up questions. Never answer from your own knowledge, and add no opinions or reasoning of your own: only summarise what the search results say.
        - Back every statement with a citation to the search result it comes from. Don't write anything you can't cite.
        - If the results don't answer the question, say only: "I couldn't find this on your chosen sites." Don't fill the gap yourself.
        - Where the sources disagree, say so and say which source holds which view.
        - Give the traditional reading of the Bible as the sources present it.
        - Name the Bible passages the sources rely on as plain references, such as John 3:16 or Romans 8:28-30.
        - End with a short list headed "Key verses": the Bible passages your sources give for this, each as a plain reference with a few words on why, and cited.
        - Keep it short: a few short paragraphs or a short list, in plain English.
        - The user may include Bible passages they selected; they are the subject of the question.
    """.trimIndent()

    /** Asked when an answer names no Bible passages (AI-8). */
    const val VERSES_PLEASE = "Your answer doesn't name any Bible verses. From your sources (search again if you need to), " +
        "list the Key verses they give for this, as plain references like John 3:16, each cited."

    /** Calls the Claude API with web search limited to the chosen sites. */
    val claude: ChatService = claude(null)

    /** The service, sending requests to [baseUrl] (tests use a local stand-in) or Anthropic. */
    fun claude(baseUrl: String?): ChatService = ChatService { apiKey, sites, history, question, hasVerses ->
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
            val (sent, first) = run(params(sites, history, question))
            if (refused(first)) return@ChatService ChatResult.Failed("The AI declined to answer this question.")
            val parts = arrayListOf(first.flatMap { it.content() })
            val answer = readParts(parts, sites)
            if (answer !is ChatResult.Answer || hasVerses(answer.text)) return@ChatService answer
            // No verses named: ask once more for the ones its sources give.
            val (_, more) = run(sent.toBuilder().addUserMessage(VERSES_PLEASE).build())
            if (!refused(more)) parts += more.flatMap { it.content() }
            readParts(parts, sites)
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

    /** The request: Claude with web search limited to [sites], the conversation so far, and [question]. */
    fun params(sites: List<String>, history: List<ChatTurn>, question: String): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM)
            .addTool(WebSearchTool20260209.builder().maxUses(5L).allowedDomains(sites).build())
            // If a safeguard declines, the server retries on a suitable model (server-side fallback).
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        for (t in history) if (t.user) builder.addUserMessage(t.text) else builder.addAssistantMessage(t.text)
        return builder.addUserMessage(question).build()
    }

    /**
     * Turns the reply into an answer: its text with a [n] mark after each cited part, and the
     * pages cited. With no search results or no citations from the chosen sites, nothing is shown.
     */
    fun read(blocks: List<ContentBlock>, sites: List<String>): ChatResult = readParts(listOf(blocks), sites)

    /** As [read], for a reply in several [parts] (an answer and its follow-up), one after another. */
    fun readParts(parts: List<List<ContentBlock>>, sites: List<String>): ChatResult {
        var results = 0
        val sources = ArrayList<ChatSource>()
        val text = StringBuilder()
        for ((n, blocks) in parts.withIndex()) {
            if (n > 0 && text.isNotBlank()) text.append("\n\n")
            for (b in blocks) {
                b.webSearchToolResult().ifPresent { r ->
                    r.content().resultBlocks().ifPresent { results += it.size }
                }
                b.text().ifPresent { t ->
                    text.append(t.text())
                    val marks = t.citations().orElse(emptyList()).mapNotNull { c ->
                        c.webSearchResultLocation().orElse(null)?.let { w ->
                            val s = ChatSource(w.title().orElse(null) ?: w.url(), w.url())
                            if (!allowed(s, sites)) return@mapNotNull null
                            val at = sources.indexOfFirst { it.url == s.url }.takeIf { it >= 0 } ?: run { sources += s; sources.lastIndex }
                            at + 1
                        }
                    }.distinct()
                    if (marks.isNotEmpty()) text.append(marks.joinToString("") { "[$it]" })
                }
            }
        }
        if (results == 0 || sources.isEmpty() || text.isBlank()) return ChatResult.NotFound
        return ChatResult.Answer(text.toString().trim(), sources)
    }

    /** Whether a page is on one of the chosen sites (the search tool enforces this too). */
    fun allowed(s: ChatSource, sites: List<String>): Boolean {
        val host = s.site.lowercase()
        return sites.any { host == it || host.endsWith(".$it") }
    }
}

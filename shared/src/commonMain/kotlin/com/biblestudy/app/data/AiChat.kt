package com.biblestudy.app.data

import com.biblestudy.app.platform.urlHost

/** One web page an answer drew on (AI-3). */
data class ChatSource(val title: String, val url: String) {
    val site: String get() = urlHost(url)?.removePrefix("www.") ?: url
}

/** One earlier message in the conversation, as text. */
data class ChatTurn(val user: Boolean, val text: String)

sealed class ChatResult {
    /** A summary of what the sites say; [text] carries [n] marks pointing into [sources]. */
    data class Answer(val text: String, val sources: List<ChatSource>) : ChatResult()
    /** The search found nothing to cite, so there is no answer (AI-2); [queries] are what it searched for. */
    data class NotFound(val queries: List<String> = emptyList()) : ChatResult()
    data class Failed(val message: String) : ChatResult()
}

/** Asks the online AI (AI-1). Swapped for a stand-in in tests. */
fun interface ChatService {
    /**
     * Blocking: call off the main thread. It searches the whole web with [sites] first, or only
     * [sites] when [onlySites] (AI-4). [hasVerses] tells whether a text names a Bible passage;
     * an answer must (AI-8).
     */
    fun ask(
        apiKey: String, sites: List<String>, onlySites: Boolean, history: List<ChatTurn>, question: String,
        hasVerses: (String) -> Boolean,
    ): ChatResult
}

/**
 * The online AI chat (AI-1 to AI-9), like a search overview: Claude searches the web (the chosen
 * sites first, or only them) with the web search tool, reads the best articles and sums them up.
 * It may not answer from its own knowledge: an answer with no search results or no citations is
 * not shown (AI-2).
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

    /** What the AI is told; [sites] are searched first, or only them when [onlySites]. */
    fun system(sites: List<String>, onlySites: Boolean): String = """
        You are the research helper in a Bible study app. Answer like a search overview: search the web, read the best articles, and sum up what they say.

        How to search:
        - Search before every answer, including follow-up questions. If the first results are thin, search again with other words.
        - ${if (onlySites) "You can only search these sites: ${sites.joinToString(", ")}." else "Search these trusted sites first: ${sites.joinToString(", ")}. Other sound Christian sources may be used too."}
        - Read several articles, from different sites where you can.

        How to answer:
        - Start with a short overview, two or three sentences, that answers the question directly.
        - Then give the main points as a short list.
        - End with a list headed "Key verses": the Bible passages your sources give for this, each as a plain reference such as John 3:16 or Romans 8:28-30, with a few words on why.
        - Back every statement with a citation to the search result it comes from. Never answer from your own knowledge and add no opinions of your own: only what the sources say.
        - If the search finds nothing that answers the question, say only: "I couldn't find this in my search."
        - Where the sources disagree, say so and say which source holds which view.
        - Give the traditional reading of the Bible as the sources present it.
        - Plain English, short. The user may include Bible passages they selected; they are the subject of the question.
    """.trimIndent()

    /** Asked when an answer names no Bible passages (AI-8). */
    const val VERSES_PLEASE = "Your answer doesn't name any Bible verses. From your sources (search again if you need to), " +
        "list the Key verses they give for this, as plain references like John 3:16, each cited."

    /** Calls the Claude API with web search limited to the chosen sites. */
    val claude: ChatService get() = claudeChat(null)

    /** The service, sending requests to [baseUrl] (tests use a local stand-in) or Anthropic. */
    fun claude(baseUrl: String?): ChatService = claudeChat(baseUrl)

    /** Whether a page is on one of the chosen sites (the search tool enforces this too). */
    fun allowed(s: ChatSource, sites: List<String>): Boolean {
        val host = s.site.lowercase()
        return sites.any { host == it || host.endsWith(".$it") }
    }
}

/**
 * The Claude API call behind the chat. On Android and computers it is Anthropic's Java SDK
 * ([AiChatSdk]); on iOS, where the Java SDK can't run, the same request over HTTP.
 */
expect fun claudeChat(baseUrl: String?): ChatService

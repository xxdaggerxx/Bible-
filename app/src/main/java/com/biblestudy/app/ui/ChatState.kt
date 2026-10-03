package com.biblestudy.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.biblestudy.app.data.AiChat
import com.biblestudy.app.data.ChatResult
import com.biblestudy.app.data.ChatService
import com.biblestudy.app.data.ChatSource
import com.biblestudy.app.data.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bible text sent to the chat (AI-6): a reference, its version and its words. */
data class ChatPassage(val label: String, val text: String)

/**
 * One message in the chat. A reply is either an answer with its [sources], or a [note] saying
 * nothing was found or something went wrong.
 */
data class ChatEntry(
    val user: Boolean,
    val text: String,
    val passages: List<ChatPassage> = emptyList(),
    val sources: List<ChatSource> = emptyList(),
    val note: String? = null,
)

/**
 * The online AI chat (AI-1 to AI-7): the conversation, passages waiting to be sent, the API key
 * and the sites it may search. The key is kept on this tablet only, apart from your notes and
 * backups.
 */
class ChatState(
    context: Context,
    private val scope: CoroutineScope,
    /** Whether a text names a Bible passage (AI-8). */
    private val hasVerses: (String) -> Boolean,
) {
    private val prefs = context.getSharedPreferences("ai_chat", Context.MODE_PRIVATE)
    private val secret = context.getSharedPreferences("ai_key", Context.MODE_PRIVATE)

    /** Shows the chat bubble and the AI chat panel view. */
    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var apiKey by mutableStateOf(secret.getString("key", "") ?: "")
        private set
    val sites = mutableStateListOf<String>().apply {
        addAll(prefs.getString("sites", null)?.split('\n')?.filter { it.isNotBlank() } ?: AiChat.DEFAULT_SITES)
    }
    /** Search only [sites]; otherwise the whole web, those first (AI-4). */
    var onlySites by mutableStateOf(prefs.getBoolean("onlySites", false))
        private set
    val entries = mutableStateListOf<ChatEntry>()
    /** Passages to go with the next question. */
    val attached = mutableStateListOf<ChatPassage>()
    var busy by mutableStateOf(false)
        private set
    /** The question being edited (AI-9): sending replaces it and everything after it. */
    var editing by mutableStateOf<Int?>(null)
        private set
    /** The online service; tests put a stand-in here. */
    var service: ChatService = AiChat.claude

    init {
        runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) entryFrom(a.getJSONObject(i))?.let { entries += it }
        }
    }

    fun changeEnabled(on: Boolean) { enabled = on; prefs.edit { putBoolean("enabled", on) } }

    fun changeOnlySites(on: Boolean) { onlySites = on; prefs.edit { putBoolean("onlySites", on) } }

    fun changeKey(key: String) { apiKey = key.trim(); secret.edit { putString("key", apiKey) } }

    fun changeSites(lines: List<String>) {
        val clean = AiChat.cleanSites(lines).ifEmpty { AiChat.DEFAULT_SITES }
        sites.clear(); sites.addAll(clean)
        prefs.edit { putString("sites", clean.joinToString("\n")) }
    }

    fun attach(p: ChatPassage) { if (p !in attached) attached += p }

    fun newChat() { if (!busy) { editing = null; entries.clear(); save() } }

    /** Starts editing question [i]: its passages go back above the box; returns its words. */
    fun startEdit(i: Int): String? {
        val e = entries.getOrNull(i)?.takeIf { it.user && !busy } ?: return null
        editing = i
        attached.clear(); attached.addAll(e.passages)
        return e.text
    }

    fun cancelEdit() { editing = null; attached.clear() }

    /** Sends [question] with the attached passages; the reply is added when it comes. */
    fun ask(question: String) {
        val q = question.trim()
        if (busy || (q.isEmpty() && attached.isEmpty())) return
        if (apiKey.isBlank()) return
        val passages = attached.toList()
        // An edited question replaces the one asked and everything after it.
        editing?.let { i -> if (i in entries.indices) entries.removeRange(i, entries.size) }
        editing = null
        val history = entries.filter { it.note == null }.map { ChatTurn(it.user, if (it.user) prompt(it.text, it.passages) else it.text) }
        entries += ChatEntry(true, q, passages)
        attached.clear()
        busy = true
        save()
        val key = apiKey
        val siteList = sites.toList()
        val only = onlySites
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { service.ask(key, siteList, only, history, prompt(q, passages), hasVerses) }
                    .getOrElse { ChatResult.Failed("Something went wrong: ${it.message ?: it.javaClass.simpleName}") }
            }
            entries += when (result) {
                is ChatResult.Answer -> ChatEntry(false, result.text, sources = result.sources)
                is ChatResult.NotFound -> ChatEntry(false, "", note = buildString {
                    append(if (only) "Nothing on your chosen sites answers this" else "The search found nothing it could cite for this")
                    append(", so there's no answer.")
                    if (result.queries.isNotEmpty()) append(" Searched for: ").append(result.queries.joinToString("; ") { "\u201c$it\u201d" }).append('.')
                    append(if (only) " Try other words, or search the whole web in Settings \u2192 AI chat." else " Try asking another way.")
                })
                is ChatResult.Failed -> ChatEntry(false, "", note = result.message)
            }
            busy = false
            save()
        }
    }

    /** The question as sent: the chosen passages first, then what was asked. */
    private fun prompt(q: String, passages: List<ChatPassage>): String = buildString {
        if (passages.isNotEmpty()) {
            append("Bible passages I selected:\n")
            for (p in passages) append(p.label).append(": ").append(p.text).append('\n')
            append('\n')
        }
        append(q.ifEmpty { "What do my sites say about these passages?" })
    }

    private fun save() {
        val a = JSONArray(entries.takeLast(100).map { e ->
            JSONObject().put("u", e.user).put("t", e.text).put("n", e.note ?: "")
                .put("p", JSONArray(e.passages.map { JSONObject().put("l", it.label).put("t", it.text) }))
                .put("s", JSONArray(e.sources.map { JSONObject().put("t", it.title).put("u", it.url) }))
        })
        prefs.edit { putString("entries", a.toString()) }
    }

    private fun entryFrom(o: JSONObject): ChatEntry? = runCatching {
        val ps = o.optJSONArray("p") ?: JSONArray()
        val ss = o.optJSONArray("s") ?: JSONArray()
        ChatEntry(
            o.getBoolean("u"), o.optString("t"),
            List(ps.length()) { ps.getJSONObject(it).let { p -> ChatPassage(p.getString("l"), p.getString("t")) } },
            List(ss.length()) { ss.getJSONObject(it).let { s -> ChatSource(s.getString("t"), s.getString("u")) } },
            o.optString("n").ifEmpty { null },
        )
    }.getOrNull()
}

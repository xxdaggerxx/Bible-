package com.biblestudy.app.data

import com.biblestudy.app.platform.AppContext
import com.biblestudy.app.platform.Lock
import com.biblestudy.app.platform.lineSequence
import com.biblestudy.app.platform.locked
import com.biblestudy.app.platform.readText

/** A section of a book's outline, with the passage it covers. */
data class OutlineItem(val title: String, val passage: Passage)

/**
 * A study introduction to one book of the Bible (STD-12): who wrote it, when, where and for whom,
 * its background, purpose and themes, an outline, key people, places and verses, and how it
 * connects to the rest of the Bible. Authorship and dates give the traditional view (STD-14).
 */
data class BookIntro(
    val book: Int,
    val name: String,
    val author: String,
    val date: String,
    val place: String,
    val audience: String,
    val type: String,
    val background: String,
    val purpose: String,
    val themes: String,
    val outline: List<OutlineItem>,
    val people: String,
    val places: String,
    val keyVerses: List<Passage>,
    val connections: String,
)

/**
 * The book introductions bundled in assets/intros/books.txt. Each book is a block starting
 * "## <id> <name>", with "field: text" lines; outline lines are "- Title | 1:1-3:24" and key
 * verses are references within the book separated by semicolons.
 */
object BookIntros {
    private val lock = Lock()
    @kotlin.concurrent.Volatile private var cache: Map<Int, BookIntro>? = null

    fun get(context: AppContext, book: Int): BookIntro? = all(context)[book]

    fun all(context: AppContext): Map<Int, BookIntro> = cache ?: locked(lock) {
        cache ?: parse(context.assets.readText("intros/books.txt")).also { cache = it }
    }

    fun parse(text: String): Map<Int, BookIntro> {
        val out = LinkedHashMap<Int, BookIntro>()
        for (block in text.split(Regex("^## ", RegexOption.MULTILINE)).drop(1)) {
            val lines = block.lines()
            val (idText, name) = lines[0].trim().split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val book = idText.toIntOrNull() ?: continue
            val fields = HashMap<String, String>()
            val outline = ArrayList<OutlineItem>()
            for (line in lines.drop(1)) {
                if (line.startsWith("- ")) {
                    val parts = line.removePrefix("- ").split('|')
                    if (parts.size == 2) passage(book, parts[1])?.let { outline += OutlineItem(parts[0].trim(), it) }
                    continue
                }
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).all { it.isLetter() }) {
                    fields[line.substring(0, colon)] = line.substring(colon + 1).trim()
                }
            }
            out[book] = BookIntro(
                book = book,
                name = name,
                author = fields["author"].orEmpty(),
                date = fields["date"].orEmpty(),
                place = fields["place"].orEmpty(),
                audience = fields["audience"].orEmpty(),
                type = fields["type"].orEmpty(),
                background = fields["background"].orEmpty(),
                purpose = fields["purpose"].orEmpty(),
                themes = fields["themes"].orEmpty(),
                outline = outline,
                people = fields["people"].orEmpty(),
                places = fields["places"].orEmpty(),
                keyVerses = fields["keyverses"].orEmpty().split(';').mapNotNull { passage(book, it) },
                connections = fields["connections"].orEmpty(),
            )
        }
        return out
    }

    /** "3:16", "12:1-3" or "1:1-3:24" within [book]. */
    fun passage(book: Int, ref: String): Passage? {
        val m = Regex("""(\d+):(\d+)(?:-(?:(\d+):)?(\d+))?""").matchEntire(ref.trim()) ?: return null
        val c = m.groupValues[1].toInt()
        val v = m.groupValues[2].toInt()
        val c2 = m.groupValues[3].toIntOrNull() ?: c
        val v2 = m.groupValues[4].toIntOrNull() ?: v
        return Passage(book, c, v, c2, v2)
    }
}

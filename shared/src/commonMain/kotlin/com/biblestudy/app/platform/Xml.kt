package com.biblestudy.app.platform

/**
 * A small XML pull parser with the calls the app used from Android's XmlPullParser (namespaces
 * not processed), for reading OSIS Bibles on every platform. Comments, processing instructions
 * and the DOCTYPE are skipped; CDATA and entities become text; adjacent text is one TEXT event.
 */
class XmlPull(private val s: String) {
    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
    }

    private var i = 0
    var eventType = START_DOCUMENT
        private set
    var name: String? = null
        private set
    var text: String = ""
        private set
    var isEmptyElementTag = false
        private set
    private var attrs: Map<String, String> = emptyMap()
    private var pendingEnd: String? = null
    private var open = 0
    private var popNext = false

    /** How many elements are open, counting this one on a start or end tag (the root is 1). */
    var depth = 0
        private set

    fun getAttributeValue(namespace: String?, attr: String): String? = attrs[attr]

    fun next(): Int {
        if (popNext) { open--; popNext = false }
        depth = open
        pendingEnd?.let { n ->
            pendingEnd = null
            name = n; attrs = emptyMap(); isEmptyElementTag = false
            depth = open; popNext = true
            eventType = END_TAG
            return eventType
        }
        val sb = StringBuilder()
        while (i < s.length) {
            if (s[i] != '<') {
                val lt = s.indexOf('<', i).let { if (it < 0) s.length else it }
                sb.append(decode(s.substring(i, lt)))
                i = lt
                continue
            }
            when {
                s.startsWith("<!--", i) -> { i = s.indexOf("-->", i + 4).let { if (it < 0) s.length else it + 3 } }
                s.startsWith("<![CDATA[", i) -> {
                    val end = s.indexOf("]]>", i + 9).let { if (it < 0) s.length else it }
                    sb.append(s, i + 9, end); i = (end + 3).coerceAtMost(s.length)
                }
                s.startsWith("<?", i) -> { i = s.indexOf("?>", i + 2).let { if (it < 0) s.length else it + 2 } }
                s.startsWith("<!", i) -> { i = skipDecl(i) }
                else -> {
                    if (sb.isNotEmpty()) return textEvent(sb)
                    return tag()
                }
            }
        }
        if (sb.isNotEmpty()) return textEvent(sb)
        eventType = END_DOCUMENT
        return eventType
    }

    private fun textEvent(sb: StringBuilder): Int {
        text = sb.toString(); name = null; attrs = emptyMap(); isEmptyElementTag = false
        eventType = TEXT
        return eventType
    }

    /** Skips <!DOCTYPE ...> with any [internal subset]. */
    private fun skipDecl(start: Int): Int {
        var j = start + 2
        var depth = 0
        while (j < s.length) {
            when (s[j]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return j + 1
            }
            j++
        }
        return s.length
    }

    private fun tag(): Int {
        val end = findTagEnd(i)
        val body = s.substring(i + 1, end)
        i = end + 1
        if (body.startsWith("/")) {
            name = body.substring(1).trim(); attrs = emptyMap(); isEmptyElementTag = false
            depth = open; popNext = true
            eventType = END_TAG
            return eventType
        }
        val selfClosing = body.endsWith("/")
        val inner = if (selfClosing) body.dropLast(1) else body
        val n = inner.takeWhile { !it.isWhitespace() }
        name = n
        attrs = parseAttrs(inner.substring(n.length))
        isEmptyElementTag = selfClosing
        open++; depth = open
        if (selfClosing) pendingEnd = n
        eventType = START_TAG
        return eventType
    }

    /** The '>' ending the tag at [start], skipping any in quoted attribute values. */
    private fun findTagEnd(start: Int): Int {
        var j = start + 1
        var quote: Char? = null
        while (j < s.length) {
            val c = s[j]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return j
            j++
        }
        return s.length - 1
    }

    private fun parseAttrs(t: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var j = 0
        while (j < t.length) {
            while (j < t.length && t[j].isWhitespace()) j++
            val ks = j
            while (j < t.length && t[j] != '=' && !t[j].isWhitespace()) j++
            val key = t.substring(ks, j)
            while (j < t.length && t[j].isWhitespace()) j++
            if (j < t.length && t[j] == '=') {
                j++
                while (j < t.length && t[j].isWhitespace()) j++
                if (j < t.length && (t[j] == '"' || t[j] == '\'')) {
                    val q = t[j]
                    val vs = j + 1
                    val ve = t.indexOf(q, vs).let { if (it < 0) t.length else it }
                    if (key.isNotEmpty()) out[key] = decode(t.substring(vs, ve))
                    j = ve + 1
                } else {
                    val vs = j
                    while (j < t.length && !t[j].isWhitespace()) j++
                    if (key.isNotEmpty()) out[key] = decode(t.substring(vs, j))
                }
            } else if (key.isNotEmpty()) out[key] = key
        }
        return out
    }

    private fun decode(t: String): String {
        if ('&' !in t) return t
        val sb = StringBuilder(t.length)
        var j = 0
        while (j < t.length) {
            val c = t[j]
            if (c == '&') {
                val semi = t.indexOf(';', j)
                if (semi > j) {
                    val ent = t.substring(j + 1, semi)
                    val rep = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { codePoint(it) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); j = semi + 1; continue }
                }
            }
            sb.append(c)
            j++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String = if (cp < 0x10000) cp.toChar().toString() else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

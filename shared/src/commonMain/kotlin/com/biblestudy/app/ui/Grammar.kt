package com.biblestudy.app.ui

/**
 * Plain-English grammar for the interlinear (STD-4): "H:Vqp3ms" (Hebrew, Open Scriptures codes),
 * "A:…" (Aramaic) or "G:V-AAI-3S" (Greek, Robinson codes) → "verb, Qal perfect, 3rd person
 * masculine singular". Unknown letters are skipped rather than guessed.
 */
object Grammar {
    fun describe(code: String): String {
        val lang = code.substringBefore(':', "")
        val c = code.substringAfter(':')
        return when (lang) {
            "G" -> greek(c)
            "H", "A" -> hebrew(c, lang == "A")
            else -> c
        }.ifBlank { c }
    }

    /** A short label for the word card: "verb", "noun", "preposition"… */
    fun short(code: String): String = describe(code).substringBefore(',').substringBefore(" (")

    // ---------- Hebrew and Aramaic ----------

    private val HEB_STEM = mapOf(
        'q' to "Qal", 'N' to "Niphal", 'p' to "Piel", 'P' to "Pual", 'h' to "Hiphil", 'H' to "Hophal", 't' to "Hithpael",
        'o' to "Polel", 'O' to "Polal", 'r' to "Hithpolel", 'm' to "Poel", 'M' to "Poal", 'k' to "Palel", 'K' to "Pulal",
        'Q' to "Qal passive", 'l' to "Pilpel", 'L' to "Polpal", 'f' to "Hithpalpel", 'D' to "Nithpael", 'j' to "Pealal",
        'i' to "Pilel", 'u' to "Hothpaal", 'c' to "Tiphil", 'v' to "Hishtaphel", 'w' to "Nithpalel", 'y' to "Nithpoel",
        'z' to "Hithpoel",
    )
    private val ARAM_STEM = mapOf(
        'q' to "Peal", 'Q' to "Peil", 'u' to "Hithpeel", 'p' to "Pael", 'P' to "Ithpaal", 'M' to "Hithpaal", 'a' to "Aphel",
        'h' to "Haphel", 's' to "Saphel", 'e' to "Shaphel", 'H' to "Hophal", 'i' to "Ithpeel", 't' to "Hishtaphel",
        'v' to "Ishtaphel", 'w' to "Hithaphel", 'o' to "Polel", 'z' to "Ithpoel", 'r' to "Hithpolel", 'f' to "Hithpalpel",
        'b' to "Hephal", 'c' to "Tiphel", 'm' to "Poel", 'l' to "Palpel", 'L' to "Ithpalpel", 'O' to "Ithpolel", 'G' to "Ittaphal",
    )
    private val HEB_CONJ = mapOf(
        'p' to "perfect", 'q' to "perfect with waw (sequential)", 'i' to "imperfect", 'w' to "imperfect with waw (sequential)",
        'h' to "cohortative", 'j' to "jussive", 'v' to "imperative", 'r' to "participle", 's' to "passive participle",
        'a' to "infinitive absolute", 'c' to "infinitive construct",
    )
    private val PERSON = mapOf('1' to "1st person", '2' to "2nd person", '3' to "3rd person")
    private val HEB_GENDER = mapOf('m' to "masculine", 'f' to "feminine", 'b' to "masculine or feminine", 'c' to "common")
    private val HEB_NUMBER = mapOf('s' to "singular", 'p' to "plural", 'd' to "dual")
    private val HEB_STATE = mapOf('a' to "", 'c' to "construct (“of”)", 'd' to "determined")

    private fun hebrew(c: String, aramaic: Boolean): String {
        if (c.isEmpty()) return ""
        val rest = c.drop(1)
        fun pgn(s: String) = listOfNotNull(
            s.getOrNull(0)?.let { PERSON[it] }, s.getOrNull(1)?.let { HEB_GENDER[it] }, s.getOrNull(2)?.let { HEB_NUMBER[it] },
        ).joinToString(" ")
        fun gns(s: String) = listOfNotNull(
            s.getOrNull(0)?.let { HEB_GENDER[it] }, s.getOrNull(1)?.let { HEB_NUMBER[it] },
            s.getOrNull(2)?.let { HEB_STATE[it] }?.takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        val parts: List<String> = when (c[0]) {
            'V' -> {
                val stem = rest.getOrNull(0)?.let { (if (aramaic) ARAM_STEM else HEB_STEM)[it] }
                val conj = rest.getOrNull(1)
                val tail = rest.drop(2)
                listOf("verb", listOfNotNull(stem, conj?.let { HEB_CONJ[it] }).joinToString(" "),
                    if (conj == 'r' || conj == 's') gns(tail) else pgn(tail))
            }
            'N' -> listOf(when (rest.getOrNull(0)) { 'p' -> "proper name"; 'g' -> "noun (people or nation)"; 't' -> "title"; else -> "noun" }, gns(rest.drop(1)))
            'A' -> listOf(when (rest.getOrNull(0)) { 'c' -> "number"; 'o' -> "ordinal number"; 'g' -> "adjective (people or nation)"; else -> "adjective" }, gns(rest.drop(1)))
            'P' -> listOf(when (rest.getOrNull(0)) {
                'd' -> "demonstrative pronoun"; 'f' -> "indefinite pronoun"; 'i' -> "interrogative pronoun"
                'r' -> "relative pronoun"; else -> "pronoun"
            }, pgn(rest.drop(1)))
            'S' -> listOf(when (rest.getOrNull(0)) {
                'd' -> "directional ending (“-ward”)"; 'h' -> "added -ah ending"; 'n' -> "added -n ending"
                else -> "pronoun suffix"
            }, pgn(rest.drop(1)))
            'R' -> listOf(if (rest.startsWith("d")) "preposition with “the”" else "preposition")
            'C' -> listOf("conjunction")
            'D' -> listOf("adverb")
            'T' -> listOf(when (rest.getOrNull(0)) {
                'a' -> "particle of affirmation"; 'd' -> "the (article)"; 'e' -> "particle of urging"
                'i' -> "question particle"; 'j' -> "interjection"; 'm' -> "demonstrative particle"; 'n' -> "negative (not)"
                'o' -> "object marker (not translated)"; 'r' -> "relative particle"; else -> "particle"
            })
            else -> emptyList()
        }
        val lang = if (aramaic) " (Aramaic)" else ""
        return parts.filter { it.isNotBlank() }.joinToString(", ").let { if (it.isEmpty()) it else it.replaceFirst(Regex("^([^,]+)"), "$1$lang") }
    }

    // ---------- Greek ----------

    private val TENSE = mapOf(
        'P' to "present", 'I' to "imperfect", 'F' to "future", 'A' to "aorist", 'R' to "perfect", 'L' to "pluperfect",
    )
    private val VOICE = mapOf(
        'A' to "active", 'M' to "middle", 'P' to "passive", 'D' to "middle", 'O' to "passive", 'N' to "middle or passive",
        'E' to "middle or passive", 'Q' to "", 'X' to "",
    )
    private val MOOD = mapOf(
        'I' to "indicative", 'S' to "subjunctive", 'O' to "optative", 'M' to "imperative", 'N' to "infinitive", 'P' to "participle",
    )
    private val CASE = mapOf('N' to "nominative", 'G' to "genitive", 'D' to "dative", 'A' to "accusative", 'V' to "vocative")
    private val NUMBER = mapOf('S' to "singular", 'P' to "plural")
    private val GENDER = mapOf('M' to "masculine", 'F' to "feminine", 'N' to "neuter")
    private val SIMPLE = mapOf(
        "ADV" to "adverb", "CONJ" to "conjunction", "PREP" to "preposition", "PRT" to "particle", "INJ" to "interjection",
        "COND" to "conditional (if)", "HEB" to "Hebrew word", "ARAM" to "Aramaic word", "ARAMAIC" to "Aramaic word",
    )
    private val POS = mapOf(
        "N" to "noun", "A" to "adjective", "T" to "the (article)", "R" to "relative pronoun", "C" to "reciprocal pronoun",
        "D" to "demonstrative pronoun", "K" to "correlative pronoun", "I" to "interrogative pronoun", "X" to "indefinite pronoun",
        "Q" to "correlative pronoun", "F" to "reflexive pronoun", "S" to "possessive pronoun", "P" to "personal pronoun",
    )

    private fun cng(s: String) = listOfNotNull(
        s.getOrNull(0)?.let { CASE[it] }, s.getOrNull(1)?.let { NUMBER[it] }, s.getOrNull(2)?.let { GENDER[it] },
    ).joinToString(" ")

    private fun greek(c: String): String {
        val f = c.split('-')
        val head = f[0]
        SIMPLE[head]?.let { name ->
            return if (head == "PRT" && f.getOrNull(1) == "N") "negative particle (not)" else
                if (head == "ADV" && f.getOrNull(1) == "C") "adverb (comparative)" else name
        }
        if (head == "V") {
            val t = f.getOrNull(1) ?: return "verb"
            val second = t.startsWith("2")
            val tvm = t.trimStart('2')
            val tense = TENSE[tvm.getOrNull(0) ?: ' ']?.let { if (second) "$it (2nd form)" else it }
            val mood = tvm.getOrNull(2)
            val form = listOfNotNull(tense, VOICE[tvm.getOrNull(1) ?: ' ']?.takeIf { it.isNotEmpty() }, mood?.let { MOOD[it] }).joinToString(" ")
            val tail = f.getOrNull(2)
            val who = when {
                tail == null -> ""
                mood == 'P' -> cng(tail)
                else -> listOfNotNull(tail.getOrNull(0)?.let { PERSON[it] }, tail.getOrNull(1)?.let { NUMBER[it] }).joinToString(" ")
            }
            return listOf("verb", form, who).filter { it.isNotBlank() }.joinToString(", ")
        }
        val pos = POS[head] ?: return c
        val tail = f.getOrNull(1) ?: return pos
        val desc = if (head in setOf("P", "F", "S") && tail.firstOrNull()?.isDigit() == true) {
            // Personal pronouns: person, then case and number (and the owner's number for S).
            listOfNotNull(PERSON[tail[0]], cng(tail.drop(if (head == "S") 2 else 1))).joinToString(" ")
        } else cng(tail)
        val kind = when (f.getOrNull(2)) {
            "P" -> " (name of a person)"; "L" -> " (name of a place)"; "T" -> " (title)"; "G" -> " (people or nation)"
            else -> ""
        }
        return listOf(pos + kind, desc).filter { it.isNotBlank() }.joinToString(", ")
    }
}

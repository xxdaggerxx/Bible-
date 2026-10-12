package com.biblestudy.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shared code runs on iOS with Kotlin's own regex engine, which knows fewer \p{…} classes than
 * Java's: \p{N}, for one, isn't there (use \p{Nd}\p{Nl}\p{No}), and a pattern using it crashes the
 * app when it's made. This checks every \p{…} in shared code is one iOS knows.
 */
class RegexOnIosTest {
    /** Classes Kotlin/Native's regex accepts (checked by running them there). */
    private val known = setOf(
        "L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me", "Nd", "Nl", "No",
        "IsL", "IsLu", "IsM", "Alpha", "Digit", "Alnum", "Upper", "Lower", "Punct", "Space",
    )

    @Test
    fun everyCharacterClassInSharedCodeWorksOnIos() {
        val bad = File("src/commonMain/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""\\\\[pP]\{([A-Za-z_=]+)\}""").findAll(f.readText())
                .map { it.groupValues[1] }.filter { it !in known }.map { "${f.name}: \\p{$it}" }
        }.toList()
        assertTrue(bad.isEmpty(), "Not known to Kotlin/Native's regex: $bad")
    }
}

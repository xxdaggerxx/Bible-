package com.biblestudy.app.platform

/** What came back from a web request: the status code and the body. */
class HttpResult(val status: Int, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/**
 * A blocking web request (call off the main thread). Throws okio.IOException when the server
 * can't be reached; any answer, error statuses included, is returned.
 */
expect fun httpRequest(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 20_000,
): HttpResult

/** Waits [ms] milliseconds on this thread. */
expect fun sleepMillis(ms: Long)

/** Encodes text for a URL's query, as a form does (space as +), like Java's URLEncoder. */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> append(ch)
            ch == ' ' -> append('+')
            else -> { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }
}

/** A web address's host, e.g. "www.ligonier.org" (null if it has none). */
fun urlHost(url: String): String? =
    url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').ifEmpty { null }

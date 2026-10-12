package com.biblestudy.app.platform

import java.net.HttpURLConnection
import java.net.URL

actual fun httpRequest(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: ByteArray?,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): HttpResult {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        return HttpResult(status, stream?.use { it.readBytes() } ?: ByteArray(0))
    } finally {
        c.disconnect()
    }
}

actual fun sleepMillis(ms: Long) = Thread.sleep(ms)

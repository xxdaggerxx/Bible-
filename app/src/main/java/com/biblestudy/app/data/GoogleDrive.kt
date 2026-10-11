package com.biblestudy.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Google said the sign-in has run out or was taken back: the user has to allow access again. */
class DriveAuthException(message: String) : IOException(message)

/**
 * The sync folder in the user's own Google Drive (SYNC-1): Drive's hidden app folder, which only
 * this app can see and which doesn't show among their files. Plain HTTPS calls to the Drive API with
 * the access token from Google sign-in (drive.appdata, the narrowest permission Drive has).
 */
class DriveStore(private val token: String) : SyncStore {

    override fun list(): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=" + enc("nextPageToken,files(id,name)") +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = JSONObject(String(call("GET", url), Charsets.UTF_8))
            val files = json.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += RemoteFile(f.getString("id"), f.getString("name"))
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    override fun read(f: RemoteFile): ByteArray = call("GET", "$API/files/${f.id}?alt=media")

    override fun write(name: String, data: ByteArray) {
        val boundary = "inkandword" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        call("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
    }

    override fun delete(f: RemoteFile) {
        call("DELETE", "$API/files/${f.id}")
    }

    /** The Google account's address, to show in Settings. */
    fun email(): String? = runCatching {
        JSONObject(String(call("GET", "$API/about?fields=" + enc("user(emailAddress)")), Charsets.UTF_8))
            .getJSONObject("user").optString("emailAddress").ifEmpty { null }
    }.getOrNull()

    private fun call(method: String, url: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == 401) throw DriveAuthException("Google sign-in needs renewing")
            if (code !in 200..299) {
                val err = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val reason = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("Google Drive: ${reason ?: "error $code"}")
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

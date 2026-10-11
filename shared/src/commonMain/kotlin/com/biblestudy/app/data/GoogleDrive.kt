package com.biblestudy.app.data

import com.biblestudy.app.platform.*
import okio.Buffer
import okio.IOException

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
            val json = JSONObject(call("GET", url).decodeToString())
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
        val boundary = "inkandword" + randomHex(16)
        val meta = JSONObject().put("name", name).put("parents", com.biblestudy.app.platform.JSONArray().put("appDataFolder")).toString()
        val body = Buffer()
            .writeUtf8("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
            .writeUtf8("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n")
            .write(data)
            .writeUtf8("\r\n--$boundary--\r\n")
            .readByteArray()
        call("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
    }

    override fun delete(f: RemoteFile) {
        call("DELETE", "$API/files/${f.id}")
    }

    /** The Google account's address, to show in Settings. */
    fun email(): String? = runCatching {
        JSONObject(call("GET", "$API/about?fields=" + enc("user(emailAddress)")).decodeToString())
            .getJSONObject("user").optString("emailAddress").ifEmpty { null }
    }.getOrNull()

    private fun call(method: String, url: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val headers = mapOf("Authorization" to "Bearer $token") + (type?.let { mapOf("Content-Type" to it) } ?: emptyMap())
        val r = httpRequest(url, method, headers, body, connectTimeoutMs = 20_000, readTimeoutMs = 60_000)
        if (r.status == 401) throw DriveAuthException("Google sign-in needs renewing")
        if (r.status !in 200..299) {
            val reason = runCatching { JSONObject(r.text).getJSONObject("error").getString("message") }.getOrNull()
            throw IOException("Google Drive: ${reason ?: "error ${r.status}"}")
        }
        return r.body
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private fun enc(s: String) = urlEncode(s)
    }
}

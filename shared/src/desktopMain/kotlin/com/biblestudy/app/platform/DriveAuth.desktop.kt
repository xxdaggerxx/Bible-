package com.biblestudy.app.platform

import com.biblestudy.app.AppConfig
import com.biblestudy.app.data.DriveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google sign-in for sync on a computer (SYNC-1): the browser opens Google's page, the user allows
 * the app its own hidden Drive folder, and Google sends the answer back to the app on this computer
 * (OAuth for installed apps, with PKCE). After that the app keeps a refresh token and doesn't ask
 * again. Needs a Google "Desktop app" client in the build (AppConfig.googleDesktopId).
 */
class DesktopDriveSignIn(private val prefs: Prefs) : DriveSignIn {
    private val id get() = AppConfig.googleDesktopId
    private val secret get() = AppConfig.googleDesktopSecret

    override suspend fun authorize(): DriveSignIn.Result = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext DriveSignIn.Result.Failed("sync with Google Drive isn't set up for computers in this version")
        val refresh = prefs.getString("refresh", null) ?: return@withContext DriveSignIn.Result.NeedsSignIn(this@DesktopDriveSignIn)
        val r = token("grant_type=refresh_token&refresh_token=${urlEncode(refresh)}")
        when {
            r.status == 200 -> DriveSignIn.Result.Token(JSONObject(r.text).getString("access_token"))
            r.status in 400..401 -> { prefs.edit { remove("refresh") }; DriveSignIn.Result.NeedsSignIn(this@DesktopDriveSignIn) }
            else -> DriveSignIn.Result.Failed("Google answered ${r.status}")
        }
    }

    /** The access token [signInInBrowser] got, handed back by the sign-in screen. */
    override fun tokenFrom(data: Any?): String? = data as? String

    /** Shows Google's page in the browser and waits (up to five minutes) for the answer. Blocks. */
    fun signInInBrowser(): String? {
        val verifier = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5 * 60_000
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code" +
                "&client_id=${urlEncode(id)}&redirect_uri=${urlEncode(redirect)}&scope=${urlEncode(DriveStore.SCOPE)}" +
                "&code_challenge=$challenge&code_challenge_method=S256&state=$state&access_type=offline&prompt=consent"
            Desktop.getDesktop().browse(URI(url))
            val query = server.accept().use { s ->
                val line = s.getInputStream().bufferedReader().readLine().orEmpty() // GET /?code=…&state=… HTTP/1.1
                val q = line.substringAfter('?', "").substringBefore(' ')
                val page = "<html><body style='font-family:sans-serif'><h2>Ink &amp; Word</h2><p>You can close this window and go back to the app.</p></body></html>"
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$page").toByteArray())
                q.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            }
            val code = query["code"]?.takeIf { query["state"] == state } ?: return null
            val r = token("grant_type=authorization_code&code=${urlEncode(code)}&code_verifier=$verifier&redirect_uri=${urlEncode(redirect)}")
            if (r.status != 200) return null
            val o = JSONObject(r.text)
            o.optString("refresh_token").ifEmpty { null }?.let { prefs.edit(now = true) { putString("refresh", it) } }
            return o.getString("access_token")
        }
    }

    private fun token(form: String): HttpResult = httpRequest(
        "https://oauth2.googleapis.com/token", "POST",
        mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        "$form&client_id=${urlEncode(id)}&client_secret=${urlEncode(secret)}".encodeToByteArray(),
    )
}

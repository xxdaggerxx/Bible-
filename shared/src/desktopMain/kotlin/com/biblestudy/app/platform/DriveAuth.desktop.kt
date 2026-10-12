package com.biblestudy.app.platform

import com.biblestudy.app.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/**
 * Google sign-in for sync on a computer (SYNC-1): the browser opens Google's page and Google sends
 * the answer back to the app on this computer ([GoogleOAuth]). Needs a Google "Desktop app" client
 * in the build (AppConfig.googleDesktopId).
 */
class DesktopDriveSignIn(prefs: Prefs) : DriveSignIn {
    private val oauth = GoogleOAuth(AppConfig.googleDesktopId, AppConfig.googleDesktopSecret, prefs)

    override suspend fun authorize(): DriveSignIn.Result = withContext(Dispatchers.IO) {
        if (!oauth.configured) DriveSignIn.Result.Failed("sync with Google Drive isn't set up for computers in this version")
        else oauth.refresh(this@DesktopDriveSignIn)
    }

    /** The access token [signInInBrowser] got, handed back by the sign-in screen. */
    override fun tokenFrom(data: Any?): String? = data as? String

    /** Shows Google's page in the browser and waits (up to five minutes) for the answer. Blocks. */
    fun signInInBrowser(): String? =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5 * 60_000
            val pending = oauth.start("http://127.0.0.1:${server.localPort}")
            Desktop.getDesktop().browse(URI(pending.url))
            val query = server.accept().use { s ->
                val line = s.getInputStream().bufferedReader().readLine().orEmpty() // GET /?code=…&state=… HTTP/1.1
                val page = "<html><body style='font-family:sans-serif'><h2>Ink &amp; Word</h2><p>You can close this window and go back to the app.</p></body></html>"
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$page").toByteArray())
                line.substringAfter('?', "").substringBefore(' ')
            }
            oauth.finish(pending, query)
        }
}

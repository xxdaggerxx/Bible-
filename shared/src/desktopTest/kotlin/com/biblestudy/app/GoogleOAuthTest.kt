package com.biblestudy.app

import com.biblestudy.app.platform.DriveSignIn
import com.biblestudy.app.platform.FilePrefs
import com.biblestudy.app.platform.GoogleOAuth
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Google sign-in for sync on computers and iOS (SYNC-1): PKCE, the kept refresh token, refusals. */
class GoogleOAuthTest {
    private val prefs = FilePrefs(Files.createTempDirectory("oauth").toOkioPath() / "driveAuth.json")

    /** A stand-in for Google's token address: answers with [replies] in turn, keeps the forms sent. */
    private class FakeGoogle(vararg replies: Pair<Int, String>) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        val forms: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val url = "http://127.0.0.1:${socket.localPort}/token"
        init {
            Thread {
                runCatching {
                    while (true) socket.accept().use { c ->
                        val input = java.io.BufferedInputStream(c.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        forms += String(input.readNBytes(length))
                        val (code, body) = replies[minOf(forms.size, replies.size) - 1]
                        val out = body.toByteArray()
                        c.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n").toByteArray() + out)
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        override fun close() = socket.close()
    }

    private fun param(form: String, key: String) = form.split('&').first { it.startsWith("$key=") }.substringAfter('=')

    @Test
    fun signingInSwapsTheCodeAndKeepsTheRefreshToken() {
        FakeGoogle(
            200 to """{"access_token":"at1","refresh_token":"rt1","expires_in":3599}""",
            200 to """{"access_token":"at2","expires_in":3599}""",
        ).use { g ->
            val oauth = GoogleOAuth("id1", "", prefs, g.url)
            assertIs<DriveSignIn.Result.NeedsSignIn>(oauth.refresh("ask"))
            val p = oauth.start("com.googleusercontent.apps.id1:/oauth2redirect")
            // Google is sent the SHA-256 of the secret, never the secret.
            val challenge = Regex("code_challenge=([^&]+)").find(p.url)!!.groupValues[1]
            val state = Regex("state=([^&]+)").find(p.url)!!.groupValues[1]
            assertTrue("access_type=offline" in p.url && "drive.appdata" in p.url, p.url)
            // A reply that doesn't carry this sign-in's state is ignored.
            assertNull(oauth.finish(p, "code=c1&state=wrong"))
            assertEquals("at1", oauth.finish(p, "code=c%2F1&state=$state"))
            val form = g.forms[0]
            assertEquals("c%2F1", param(form, "code"))
            val verifier = param(form, "code_verifier")
            assertEquals(challenge, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(verifier.encodeUtf8().sha256().toByteArray()))
            assertTrue("client_secret" !in form, form)
            // Later, the kept refresh token gives a new access token without asking.
            assertEquals("at2", (oauth.refresh("ask") as DriveSignIn.Result.Token).token)
            assertEquals("rt1", param(g.forms[1], "refresh_token"))
        }
    }

    @Test
    fun aRefusedRefreshTokenAsksToSignInAgain() {
        prefs.edit(now = true) { putString("refresh", "old") }
        FakeGoogle(400 to """{"error":"invalid_grant"}""").use { g ->
            val oauth = GoogleOAuth("id1", "secret", prefs, g.url)
            assertIs<DriveSignIn.Result.NeedsSignIn>(oauth.refresh("ask"))
            assertTrue("client_secret=secret" in g.forms[0])
            assertNull(prefs.getString("refresh", null))
        }
    }
}

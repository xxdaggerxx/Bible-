package com.biblestudy.app.platform

import com.biblestudy.app.data.DriveStore
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Random bytes fit for secrets (sign-in codes). */
expect fun secureRandomBytes(n: Int): ByteArray

/**
 * Google sign-in for sync on computers and iOS (SYNC-1), as Google's guide for installed apps
 * says: Google's page asks the user to allow the app its own hidden Drive folder and sends a code
 * back to the app (with PKCE, so a code caught on the way is useless); the app swaps it for an
 * access token and a refresh token, and keeps the refresh token in [prefs] so it doesn't ask
 * again. Each platform shows Google's page its own way. [clientSecret] is empty for an iOS client.
 */
class GoogleOAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val prefs: Prefs,
    private val tokenUrl: String = TOKEN_URL,
) {
    val configured: Boolean get() = clientId.isNotBlank()

    /** A sign-in under way: Google's page to show, and what's needed to finish it. */
    class Pending(val url: String, val redirect: String, internal val verifier: String, internal val state: String)

    /** An access token from the kept refresh token; NeedsSignIn when there's none or Google refused it. Blocks. */
    fun refresh(needsSignIn: Any): DriveSignIn.Result {
        val refresh = prefs.getString(KEY, null) ?: return DriveSignIn.Result.NeedsSignIn(needsSignIn)
        val r = token("grant_type=refresh_token&refresh_token=${urlEncode(refresh)}")
        return when {
            r.status == 200 -> DriveSignIn.Result.Token(JSONObject(r.text).getString("access_token"))
            r.status in 400..401 -> { prefs.edit { remove(KEY) }; DriveSignIn.Result.NeedsSignIn(needsSignIn) }
            else -> DriveSignIn.Result.Failed("Google answered ${r.status}")
        }
    }

    /** Starts a sign-in that comes back to [redirect]. */
    @OptIn(ExperimentalEncodingApi::class)
    fun start(redirect: String): Pending {
        val verifier = Base64.UrlSafe.encode(secureRandomBytes(48)).trimEnd('=')
        val challenge = Base64.UrlSafe.encode(verifier.encodeToByteArray().toByteString().sha256().toByteArray()).trimEnd('=')
        val state = secureRandomBytes(16).toByteString().hex()
        val url = "$AUTH_URL?response_type=code" +
            "&client_id=${urlEncode(clientId)}&redirect_uri=${urlEncode(redirect)}&scope=${urlEncode(DriveStore.SCOPE)}" +
            "&code_challenge=$challenge&code_challenge_method=S256&state=$state&access_type=offline&prompt=consent"
        return Pending(url, redirect, verifier, state)
    }

    /**
     * Finishes [p] with the query Google sent back to the redirect address (code=…&state=…).
     * Returns the access token, or null if the user said no or it failed. Blocks.
     */
    fun finish(p: Pending, query: String): String? {
        val q = query.split('&').associate { it.substringBefore('=') to urlDecode(it.substringAfter('=', "")) }
        val code = q["code"]?.takeIf { q["state"] == p.state } ?: return null
        val r = token("grant_type=authorization_code&code=${urlEncode(code)}&code_verifier=${p.verifier}&redirect_uri=${urlEncode(p.redirect)}")
        if (r.status != 200) return null
        val o = JSONObject(r.text)
        o.optString("refresh_token").ifEmpty { null }?.let { prefs.edit(now = true) { putString(KEY, it) } }
        return o.optString("access_token").ifEmpty { null }
    }

    private fun token(form: String): HttpResult = httpRequest(
        tokenUrl, "POST",
        mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        ("$form&client_id=${urlEncode(clientId)}" + (if (clientSecret.isEmpty()) "" else "&client_secret=${urlEncode(clientSecret)}")).encodeToByteArray(),
    )

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val KEY = "refresh"

        /** Decodes a URL query value (+ for space, %XX). */
        fun urlDecode(s: String): String {
            val out = okio.Buffer()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '+' -> { out.writeByte(' '.code); i++ }
                    c == '%' && i + 2 <= s.lastIndex -> { out.writeByte(s.substring(i + 1, i + 3).toInt(16)); i += 3 }
                    else -> { out.writeUtf8(c.toString()); i++ }
                }
            }
            return out.readUtf8()
        }
    }
}

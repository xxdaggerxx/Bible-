package com.biblestudy.app.platform

import com.biblestudy.app.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * Google sign-in for sync on iPad and iPhone (SYNC-1): Google's page in a sign-in sheet
 * (ASWebAuthenticationSession), coming back to the app's own address ([GoogleOAuth]). Needs a
 * Google "iOS" client in the build (AppConfig.googleIosId, like 1234-abc.apps.googleusercontent.com).
 */
class IosDriveSignIn(prefs: Prefs) : DriveSignIn {
    private val id = AppConfig.googleIosId
    private val oauth = GoogleOAuth(id, "", prefs)
    /** Google's iOS clients answer at the client id turned around: com.googleusercontent.apps.1234-abc. */
    private val scheme = "com.googleusercontent.apps." + id.removeSuffix(".apps.googleusercontent.com")
    private var session: ASWebAuthenticationSession? = null
    private var anchor: Anchor? = null

    override suspend fun authorize(): DriveSignIn.Result = withContext(Dispatchers.IO) {
        if (!oauth.configured) DriveSignIn.Result.Failed("sync with Google Drive isn't set up for iPad and iPhone in this version")
        else oauth.refresh(this@IosDriveSignIn)
    }

    override fun tokenFrom(data: Any?): String? = data as? String

    /** Shows Google's sign-in sheet; [onToken] gets the access token, or null (on the main thread). */
    fun signIn(onToken: (String?) -> Unit) {
        val pending = oauth.start("$scheme:/oauth2redirect")
        val s = ASWebAuthenticationSession(NSURL(string = pending.url), scheme) { url, _ ->
            session = null
            val query = url?.query
            if (query == null) onMain { onToken(null) }
            else CoroutineScope(Dispatchers.IO).launch {
                val token = runCatching { oauth.finish(pending, query) }.getOrNull()
                onMain { onToken(token) }
            }
        }
        val anchor = Anchor()
        s.presentationContextProvider = anchor
        session = s
        this.anchor = anchor // held while the sheet is up (the session holds it weakly)
        if (!s.start()) { session = null; onToken(null) }
    }

    /** Where the sign-in sheet shows. (A class, not an object: Kotlin can't make one Objective-C object.) */
    private class Anchor : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        @Suppress("DEPRECATION")
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}

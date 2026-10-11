package com.biblestudy.app.platform

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google sign-in for sync (SYNC-1), through Google Play services: the user picks their account and
 * allows the app its own hidden folder in their Drive, once per device. After that Google hands out
 * fresh access without asking again. A sign-in screen to show is an IntentSender.
 */
class DriveAuth(private val context: Context) : DriveSignIn {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(com.biblestudy.app.data.DriveStore.SCOPE)))
        .build()

    override suspend fun authorize(): DriveSignIn.Result = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r -> cont.resume(read(r)) }
            .addOnFailureListener { e -> cont.resume(DriveSignIn.Result.Failed(e.message ?: "Google sign-in isn't available")) }
    }

    /** The access token from Google's screen, or null if the user said no. */
    override fun tokenFrom(data: Any?): String? =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data as? Intent).accessToken

    private fun read(r: AuthorizationResult): DriveSignIn.Result {
        val pending = r.pendingIntent
        return when {
            r.hasResolution() && pending != null -> DriveSignIn.Result.NeedsSignIn(pending.intentSender)
            r.accessToken != null -> DriveSignIn.Result.Token(r.accessToken!!)
            else -> DriveSignIn.Result.Failed("no access from Google")
        }
    }
}

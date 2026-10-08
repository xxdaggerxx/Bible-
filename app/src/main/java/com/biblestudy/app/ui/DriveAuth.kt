package com.biblestudy.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google sign-in for sync (SYNC-1), through Google Play services: the user picks their account and
 * allows the app its own hidden folder in their Drive, once per device. After that Google hands out
 * fresh access without asking again.
 */
class DriveAuth(private val context: Context) {
    sealed interface Result {
        data class Token(val token: String) : Result
        /** Google's account and permission screen has to be shown. */
        data class NeedsSignIn(val intent: IntentSender) : Result
        data class Failed(val reason: String) : Result
    }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(com.biblestudy.app.data.DriveStore.SCOPE)))
        .build()

    suspend fun authorize(): Result = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r -> cont.resume(read(r)) }
            .addOnFailureListener { e -> cont.resume(Result.Failed(e.message ?: "Google sign-in isn't available")) }
    }

    /** The access token from Google's screen, or null if the user said no. */
    fun tokenFrom(data: Intent?): String? =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken

    private fun read(r: AuthorizationResult): Result {
        val pending = r.pendingIntent
        return when {
            r.hasResolution() && pending != null -> Result.NeedsSignIn(pending.intentSender)
            r.accessToken != null -> Result.Token(r.accessToken!!)
            else -> Result.Failed("no access from Google")
        }
    }
}

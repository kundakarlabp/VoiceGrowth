package com.voicegrowth.app.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await


data class DriveAuthorization(
    val accessToken: String,
    val accountEmail: String?
)

sealed interface DriveAuthorizationAttempt {
    data class Authorized(val authorization: DriveAuthorization) : DriveAuthorizationAttempt
    data class NeedsResolution(val pendingIntent: PendingIntent) : DriveAuthorizationAttempt
}

/** Current Google Identity Services authorization flow for the narrow Drive file scope. */
object GoogleAuthManager {
    private val driveScope = Scope(DriveSyncService.DRIVE_FILE_SCOPE)

    suspend fun authorize(
        context: Context,
        forceAccountPicker: Boolean = false
    ): DriveAuthorizationAttempt {
        val result = Identity.getAuthorizationClient(context)
            .authorize(buildRequest(forceAccountPicker))
            .await()
        return result.toAttempt()
    }

    fun authorizationFromIntent(context: Context, data: Intent?): DriveAuthorization {
        requireNotNull(data) { "Google authorization returned no result" }
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return result.toAuthorization()
    }

    suspend fun revoke(context: Context): Result<Unit> = runCatching {
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(driveScope))
            .build()
        Identity.getAuthorizationClient(context).revokeAccess(request).await()
    }

    fun userFacingError(context: Context, error: Throwable): String {
        val api = error as? ApiException
        return when (api?.statusCode) {
            10 -> AppIdentityDiagnostics.oauthClientFixMessage(context)
            7 -> "Google Drive authorization could not reach Google services. Check the network and Google Play services, then retry."
            12501 -> "Google Drive connection was cancelled."
            else -> "Google Drive authorization failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
        }
    }

    private fun buildRequest(forceAccountPicker: Boolean): AuthorizationRequest {
        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(driveScope))
        if (forceAccountPicker) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        return builder.build()
    }

    private fun AuthorizationResult.toAttempt(): DriveAuthorizationAttempt {
        return if (hasResolution()) {
            DriveAuthorizationAttempt.NeedsResolution(
                requireNotNull(pendingIntent) { "Google authorization requires resolution but returned no PendingIntent" }
            )
        } else {
            DriveAuthorizationAttempt.Authorized(toAuthorization())
        }
    }

    private fun AuthorizationResult.toAuthorization(): DriveAuthorization {
        val token = accessToken?.takeIf(String::isNotBlank)
            ?: error("Google authorization did not return a Drive access token")
        val email = runCatching { toGoogleSignInAccount()?.email }.getOrNull()
        return DriveAuthorization(token, email)
    }
}

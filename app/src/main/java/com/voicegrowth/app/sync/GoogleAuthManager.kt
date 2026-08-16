package com.voicegrowth.app.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

object GoogleAuthManager {
    private val DRIVE_FILE_SCOPE = Scope(DriveSyncService.DRIVE_FILE_SCOPE)

    fun getSignInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_FILE_SCOPE)
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return account.takeIf { GoogleSignIn.hasPermissions(it, DRIVE_FILE_SCOPE) }
    }
}

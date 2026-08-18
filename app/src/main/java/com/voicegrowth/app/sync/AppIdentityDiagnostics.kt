package com.voicegrowth.app.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

object AppIdentityDiagnostics {
    fun packageName(context: Context): String = context.packageName

    fun signingSha1(context: Context): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo) { "Signing information unavailable" }
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            requireNotNull(signatures.firstOrNull()) { "Signing certificate unavailable" }.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            requireNotNull(packageInfo.signatures?.firstOrNull()) { "Signing certificate unavailable" }.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString(":") { byte -> String.format(Locale.US, "%02X", byte.toInt() and 0xFF) }
    }

    fun oauthClientFixMessage(context: Context): String =
        "Google OAuth Android client mismatch. In Google Cloud Console, enable the Drive API and configure an Android OAuth client with package ${packageName(context)} and SHA-1 ${runCatching { signingSha1(context) }.getOrDefault("unavailable")}. Then reconnect Drive."
}

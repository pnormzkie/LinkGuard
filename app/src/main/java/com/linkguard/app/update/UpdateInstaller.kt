package com.linkguard.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.URI

/**
 * Downloads a release APK via DownloadManager and fires the system install
 * prompt when the download completes.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * Hosts permitted to serve update APKs. The URL must be HTTPS and its host must
     * be one of these exactly, or a subdomain of one. A substring/`contains` check is
     * deliberately avoided: it would accept lookalikes such as `github.com.evil.com`
     * (subdomain trick) or `evil.com/github.com` (path trick).
     */
    private val ALLOWED_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    /**
     * Returns true only when [apkUrl] is an HTTPS URL whose host is an allowed
     * GitHub release host (exact match or a subdomain of one).
     */
    fun isTrustedUpdateUrl(apkUrl: String?): Boolean {
        if (apkUrl.isNullOrBlank()) return false
        val uri = try {
            URI(apkUrl)
        } catch (_: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        return ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    fun downloadAndInstall(context: Context, apkUrl: String, fileName: String) {
        val appContext = context.applicationContext

        if (!isTrustedUpdateUrl(apkUrl)) {
            Log.e(TAG, "Refusing update from untrusted URL")
            return
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        var downloadId = -1L

        // Registered on the application context so the install prompt still fires
        // if the user leaves the screen while the download is running.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == -1L || id != downloadId) return
                appContext.unregisterReceiver(this)

                val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
                if (apkUri == null) {
                    Log.e(TAG, "Update download failed (no file for download $downloadId)")
                    return
                }

                // Verify the downloaded APK is signed by the same certificate as the
                // running app before handing it to the system installer. This blocks a
                // tampered/swapped APK even if it somehow reached the download directory.
                val apkFile = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.let { java.io.File(it, fileName) }
                if (apkFile == null || !apkFile.exists() ||
                    !signatureMatchesInstalledApp(appContext, apkFile.absolutePath)
                ) {
                    Log.e(TAG, "Refusing to install update: signature verification failed")
                    runCatching { apkFile?.delete() }
                    return
                }

                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(install) }
                    .onFailure { Log.e(TAG, "Could not launch installer: ${it.message}") }
            }
        }

        // ACTION_DOWNLOAD_COMPLETE is sent by the system Downloads provider,
        // which requires an exported receiver on Android 14+.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(fileName)
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)

        downloadId = downloadManager.enqueue(request)
    }

    /**
     * True when the APK at [apkPath] is signed by the exact same certificate set as
     * the currently installed app. Returns false on any error or mismatch so the
     * install is aborted rather than proceeding with an unverified package.
     */
    private fun signatureMatchesInstalledApp(context: Context, apkPath: String): Boolean {
        return try {
            val pm = context.packageManager
            val installed = currentSignatures(pm, context.packageName)
            val downloaded = archiveSignatures(pm, apkPath)
            installed.isNotEmpty() && downloaded.isNotEmpty() && installed == downloaded
        } catch (e: Exception) {
            Log.e(TAG, "Signature check error: ${e.message}")
            false
        }
    }

    private fun currentSignatures(pm: PackageManager, packageName: String): Set<String> {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        return signatureDigests(extractSignatures(info))
    }

    private fun archiveSignatures(pm: PackageManager, apkPath: String): Set<String> {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return emptySet()
        return signatureDigests(extractSignatures(info))
    }

    private fun extractSignatures(info: android.content.pm.PackageInfo): Array<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyArray()
            return if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners ?: emptyArray()
            } else {
                signingInfo.signingCertificateHistory ?: emptyArray()
            }
        }
        @Suppress("DEPRECATION")
        return info.signatures ?: emptyArray()
    }

    private fun signatureDigests(signatures: Array<Signature>): Set<String> =
        signatures.map { sig ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
}

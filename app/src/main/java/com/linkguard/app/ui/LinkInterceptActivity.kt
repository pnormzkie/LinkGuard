package com.linkguard.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.linkguard.app.R
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ActivityLinkInterceptBinding
import com.linkguard.app.domain.mapper.toLegacy
import com.linkguard.app.domain.scoring.ScoringEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Intercepts tapped http/https links (when LinkGuard is the default browser
 * or chosen from the app picker), scans them, and only forwards safe links
 * to the user's real browser. Dangerous links are blocked with an override.
 *
 * Trust boundary: this activity is intentionally exported (it is the browser-intercept
 * entry point), so any app on the device can launch it with an arbitrary intent.
 * Treat all callers and intent extras as untrusted. Specifically:
 *  - `intent.data` is validated to http/https only; anything else is dropped and the
 *    activity finishes without acting on it.
 *  - The activity must never auto-open a URL in the browser without first showing its
 *    verdict UI. Today the only auto-forward path is the SAFE verdict, which surfaces a
 *    user-visible "checked — safe"/"local checks only" toast before forwarding. Unchecked
 *    and blocked verdicts require an explicit user tap. Do not add a silent forward path.
 *  - CATEGORY_BROWSABLE callers are deliberately accepted (rejecting them would break
 *    legitimate link taps); the verdict UI, not the caller's identity, is the safeguard.
 */
class LinkInterceptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkInterceptBinding
    private var scanJob: Job? = null

    /** True while providers are running and no verdict UI has been shown yet. */
    private val isScanning: Boolean
        get() = scanJob?.isActive == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkInterceptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)

        val url = intent?.data?.toString()
        if (url.isNullOrBlank() ||
            !(url.startsWith("http://", true) || url.startsWith("https://", true))
        ) {
            finish()
            return
        }

        binding.tvUrl.text = url
        binding.btnDontOpen.setOnClickListener { finish() }
        binding.btnOpenAnyway.setOnClickListener {
            openInBrowser(url)
            finish()
        }
        // Fail-safe exit while the scan is running: cancel the scan and close WITHOUT
        // opening the link. Never forwards the URL.
        binding.btnCancelScan.setOnClickListener { cancelScanAndFinish() }

        scanAndDecide(url)
    }

    /** Cancels the in-flight scan (if any) and finishes without opening the link. */
    private fun cancelScanAndFinish() {
        scanJob?.cancel()
        finish()
    }

    /** During scanning, the system back action behaves exactly like Cancel: abort and
     *  close without opening. Once a verdict UI is shown, back closes normally. */
    @Deprecated("Deprecated in AppCompatActivity")
    override fun onBackPressed() {
        if (isScanning) {
            cancelScanAndFinish()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun scanAndDecide(url: String) {
        scanJob = lifecycleScope.launch {
            val legacy = runCatching {
                ScannerProvider.orchestrator.scan(url)
                    .toLegacy(sourceApp = "Link Tap", senderInfo = "Tapped link")
            }.getOrNull()

            legacy?.let { result ->
                runCatching { ScanRepository(applicationContext).saveScan(result) }
            }

            if (isFinishing || isDestroyed) return@launch

            when {
                legacy == null -> {
                    // The scan itself failed — nothing (not even local heuristics) ran.
                    // Don't silently forward an unchecked link; let the user decide.
                    showUncheckedScreen()
                }
                legacy.threatLevel == ThreatLevel.SAFE -> {
                    val unvetted =
                        legacy.flags.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON)
                    Toast.makeText(
                        this@LinkInterceptActivity,
                        getString(
                            if (unvetted) R.string.link_unverified_toast
                            else R.string.link_safe_toast
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    openInBrowser(url)
                    finish()
                }
                else -> showBlockScreen(legacy)
            }
        }
    }

    private fun showBlockScreen(result: ScanResult) {
        val isDanger = result.threatLevel == ThreatLevel.DANGER
        binding.scanProgress.visibility = View.GONE
        binding.btnCancelScan.visibility = View.GONE
        binding.tvStatus.text = getString(
            if (isDanger) R.string.link_blocked_title else R.string.link_suspicious_title
        )
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (isDanger) R.color.red else R.color.yellow)
        )
        binding.tvReason.text = buildString {
            append(getString(R.string.link_risk_format, result.riskScore, result.category))
            result.flags.take(3).forEach { append("\n• ").append(it) }
        }
        binding.tvReason.visibility = View.VISIBLE
        binding.buttonRow.visibility = View.VISIBLE
    }

    /** Shown when the scan failed entirely (no verdict at all) — let the user choose
     *  rather than silently forwarding an unchecked link. Reuses the choice buttons. */
    private fun showUncheckedScreen() {
        binding.scanProgress.visibility = View.GONE
        binding.btnCancelScan.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.link_unchecked_title)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.yellow))
        binding.tvReason.text = getString(R.string.link_unchecked_reason)
        binding.tvReason.visibility = View.VISIBLE
        binding.buttonRow.visibility = View.VISIBLE
    }

    private fun openInBrowser(url: String) {
        // Target the device's actual browser app so the intent can never
        // loop back into LinkGuard when it is set as the default browser.
        val browserIntent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER
        ).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(browserIntent)
        } catch (e: ActivityNotFoundException) {
            // No browser-category app — fall back to a chooser that excludes us
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                getString(R.string.open_with)
            ).apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(this@LinkInterceptActivity, LinkInterceptActivity::class.java))
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(chooser) }
        }
    }
}

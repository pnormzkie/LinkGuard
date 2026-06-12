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
import kotlinx.coroutines.launch

/**
 * Intercepts tapped http/https links (when LinkGuard is the default browser
 * or chosen from the app picker), scans them, and only forwards safe links
 * to the user's real browser. Dangerous links are blocked with an override.
 */
class LinkInterceptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkInterceptBinding

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

        scanAndDecide(url)
    }

    private fun scanAndDecide(url: String) {
        lifecycleScope.launch {
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

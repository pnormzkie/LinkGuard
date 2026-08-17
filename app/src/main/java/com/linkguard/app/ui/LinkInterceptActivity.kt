package com.linkguard.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.linkguard.app.R
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ActivityLinkInterceptBinding
import com.linkguard.app.databinding.ItemFlagBinding
import com.linkguard.app.domain.mapper.toLegacy
import com.linkguard.app.domain.scoring.ScoringEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Intercepts tapped http/https links (when LinkGuard is the default browser
 * or chosen from the app picker), scans them, and shows a verdict card before
 * any link is opened. Dangerous links require a confirm-gated override.
 *
 * Trust boundary: this activity is intentionally exported (it is the browser-intercept
 * entry point), so any app on the device can launch it with an arbitrary intent.
 * Treat all callers and intent extras as untrusted. Specifically:
 *  - `intent.data` is validated to http/https only; anything else is dropped and the
 *    activity finishes without acting on it.
 *  - The activity NEVER auto-opens a URL in the browser. Every verdict — including SAFE —
 *    shows a card and only opens on an explicit user tap. Do not add a silent forward path.
 *  - CATEGORY_BROWSABLE callers are deliberately accepted (rejecting them would break
 *    legitimate link taps); the verdict UI, not the caller's identity, is the safeguard.
 */
class LinkInterceptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkInterceptBinding
    private lateinit var targetUrl: String
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

        targetUrl = url
        binding.tvScanningUrl.text = url
        // Verdict buttons are wired per-screen (their meaning depends on the verdict).
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
                legacy.threatLevel == ThreatLevel.SAFE -> showSafeScreen(legacy)
                else -> showBlockScreen(legacy)
            }
        }
    }

    /** SAFE verdict: green card. No silent auto-open — the user explicitly chooses
     *  "Open Link" or "Close", so an accidental tap can still be backed out of. */
    private fun showSafeScreen(result: ScanResult) {
        val color = ContextCompat.getColor(this, R.color.e_green)
        hideScanningState()

        setBadge(color, R.drawable.ic_stat_safe, getString(R.string.status_safe_link))
        setScoreRing(color, result.riskScore)
        binding.scoreFrame.visibility = View.VISIBLE
        setTappedLink(result.url, result.resolvedUrl, color)

        val unvetted = result.flags.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON)
        binding.tvReason.text = getString(
            if (unvetted) R.string.link_safe_local_only else R.string.link_safe_verified
        )
        binding.tvReason.setTextColor(ContextCompat.getColor(this, R.color.e_muted))
        binding.tvReason.visibility = View.VISIBLE

        // Recommended action for a safe link is to open it.
        configurePrimary(R.string.btn_open_link) {
            openInBrowser(result.verifiedResolvedUrl ?: result.url)
            finish()
        }
        configureSecondary(R.string.btn_close, R.color.e_muted) { finish() }
        binding.tvDangerOverride.visibility = View.GONE
        binding.buttonRow.visibility = View.VISIBLE
    }

    private fun showBlockScreen(result: ScanResult) {
        val isDanger = result.threatLevel == ThreatLevel.DANGER
        val color = ContextCompat.getColor(this, if (isDanger) R.color.e_red else R.color.e_amber)
        hideScanningState()

        setBadge(
            color,
            if (isDanger) R.drawable.ic_stat_threat else R.drawable.ic_stat_suspicious,
            getString(if (isDanger) R.string.status_dangerous_link else R.string.status_suspicious_link)
        )
        setScoreRing(color, result.riskScore)
        binding.scoreFrame.visibility = View.VISIBLE
        setTappedLink(result.url, result.resolvedUrl, color)
        populateFlags(result, color)

        // Recommended action for a flagged link is to NOT open it.
        configurePrimary(R.string.btn_dont_open) { finish() }
        if (isDanger) {
            // High-risk: no co-equal override. Demote it to a low-emphasis, confirm-gated link.
            binding.btnSecondary.visibility = View.GONE
            binding.tvDangerOverride.visibility = View.VISIBLE
            binding.tvDangerOverride.setOnClickListener {
                confirmDangerOpen(result.verifiedResolvedUrl ?: result.url)
            }
        } else {
            configureSecondary(R.string.btn_open_anyway) {
                openInBrowser(result.verifiedResolvedUrl ?: result.url)
                finish()
            }
            binding.tvDangerOverride.visibility = View.GONE
        }
        binding.buttonRow.visibility = View.VISIBLE
    }

    /** Shown when the scan failed entirely (no verdict at all) — let the user choose
     *  rather than silently forwarding an unchecked link. Treated like SUSPICIOUS:
     *  "Don't Open" recommended, "Open anyway" available without a confirm step. */
    private fun showUncheckedScreen() {
        val color = ContextCompat.getColor(this, R.color.e_amber)
        hideScanningState()

        setBadge(color, R.drawable.ic_stat_suspicious, getString(R.string.link_unchecked_title))
        setTappedLink(targetUrl, null, color)
        binding.tvReason.text = getString(R.string.link_unchecked_reason)
        binding.tvReason.setTextColor(ContextCompat.getColor(this, R.color.e_text2))
        binding.tvReason.visibility = View.VISIBLE

        configurePrimary(R.string.btn_dont_open) { finish() }
        configureSecondary(R.string.btn_open_anyway) { openInBrowser(targetUrl); finish() }
        binding.tvDangerOverride.visibility = View.GONE
        binding.buttonRow.visibility = View.VISIBLE
    }

    /** DANGER override is a two-step action so it can't be triggered by reflex. */
    private fun confirmDangerOpen(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.danger_confirm_title)
            .setMessage(R.string.danger_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.btn_open_anyway) { _, _ ->
                openInBrowser(url)
                finish()
            }
            .show()
    }

    // ─── Shared verdict-UI helpers ──────────────────────────────────────────────

    /**
     * Shows the tapped URL and, when the link redirected to a different destination
     * (shortener/wrapper), the resolved URL tinted to the verdict colour. Hidden when there
     * was no redirect, so a plain link looks exactly as before.
     */
    private fun setTappedLink(url: String, resolvedUrl: String?, color: Int) {
        binding.tvUrl.text = url
        if (!resolvedUrl.isNullOrBlank() && resolvedUrl != url) {
            binding.tvResolvedUrl.text = resolvedUrl
            binding.tvResolvedUrl.setTextColor(color)
            binding.tvGoesToLabel.visibility = View.VISIBLE
            binding.tvResolvedUrl.visibility = View.VISIBLE
        } else {
            binding.tvGoesToLabel.visibility = View.GONE
            binding.tvResolvedUrl.visibility = View.GONE
        }
        binding.urlChip.visibility = View.VISIBLE
    }

    private fun setBadge(color: Int, iconRes: Int, text: String) {
        binding.ivStatusIcon.setImageResource(iconRes)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(color)
        binding.statusBadgeContainer.backgroundTintList = ColorStateList.valueOf(color).withAlpha(30)
        binding.statusBadgeContainer.visibility = View.VISIBLE
    }

    private fun setScoreRing(color: Int, score: Int) {
        (binding.scoreFrame.background?.mutate() as? GradientDrawable)?.setStroke(
            (2 * resources.displayMetrics.density).toInt(), color
        )
        binding.tvScoreLarge.text = "$score%"
        binding.tvScoreLarge.setTextColor(color)
    }

    private fun populateFlags(result: ScanResult, color: Int) {
        binding.flagsContainer.removeAllViews()
        val groups = result.flagGroups
        if (groups.isNotEmpty()) {
            // A lone group auto-expands (no extra tap); with 2+ groups everything starts collapsed.
            val autoExpand = groups.size == 1
            groups.forEach { addFlagGroup(layoutInflater, binding.flagsContainer, it, color, autoExpand) }
            // The only non-signal flat flag is the "external checks unavailable" meta-note;
            // surface it under the groups. (Signal titles are already represented by the groups.)
            result.flags.filter { it == ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON }
                .forEach { addFlagNote(layoutInflater, binding.flagsContainer, it) }
        } else {
            // Fallback when there is no category data: the flat list, as before.
            result.flags.take(3).forEach { flag ->
                val flagBinding = ItemFlagBinding.inflate(layoutInflater, binding.flagsContainer, false)
                flagBinding.tvFlag.text = flag
                styleFlagRow(flagBinding, color)
                binding.flagsContainer.addView(flagBinding.root)
            }
        }
        val hasFlags = binding.flagsContainer.childCount > 0
        binding.flagsHeader.visibility = if (hasFlags) View.VISIBLE else View.GONE
        binding.flagsContainer.visibility = if (hasFlags) View.VISIBLE else View.GONE
    }

    private fun configurePrimary(textRes: Int, onClick: () -> Unit) {
        binding.btnPrimary.text = getString(textRes)
        binding.btnPrimary.setOnClickListener { onClick() }
    }

    private fun configureSecondary(textRes: Int, textColorRes: Int = R.color.e_red_soft, onClick: () -> Unit) {
        binding.btnSecondary.visibility = View.VISIBLE
        binding.btnSecondary.text = getString(textRes)
        binding.btnSecondary.setTextColor(ContextCompat.getColor(this, textColorRes))
        binding.btnSecondary.setOnClickListener { onClick() }
    }

    /** Hides the in-progress scanning cluster once a verdict UI is ready to show. */
    private fun hideScanningState() {
        binding.scanProgress.visibility = View.GONE
        binding.tvScanning.visibility = View.GONE
        binding.tvScanningUrl.visibility = View.GONE
        binding.btnCancelScan.visibility = View.GONE
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

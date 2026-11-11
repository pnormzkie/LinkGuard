package com.linkguard.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import com.linkguard.app.scanner.SharedTextIntake
import com.linkguard.app.util.MonitorPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 *  - The activity only auto-opens URLs that exactly match the user's explicit exclusion list.
 *    Every scanned verdict — including SAFE — shows a card and requires a user tap.
 *  - CATEGORY_BROWSABLE callers are deliberately accepted (rejecting them would break
 *    legitimate link taps); the verdict UI, not the caller's identity, is the safeguard.
 *  - Shared text (ACTION_SEND / ACTION_PROCESS_TEXT) is read through [SharedTextIntake], which
 *    caps its size before any pattern runs. A shared link is never auto-opened, excluded or not:
 *    the user asked for a check, so they always get a card.
 */
class LinkInterceptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkInterceptBinding
    private var scanJob: Job? = null

    /** History source for this scan: the literals [scanSourceLabel] reads back. */
    private var sourceApp = "Link Tap"
    private var senderInfo = "Tapped link"

    /** Caption over the link on a verdict card, and the one for a single unverified link. */
    private var linkLabel = ""
    private var singleLinkLabel = ""

    /** Links in shared text beyond [SharedTextIntake.MAX_LINKS]; always 0 for a tapped link. */
    private var linksNotScanned = 0

    /** True while providers are running and no verdict UI has been shown yet. */
    private val isScanning: Boolean
        get() = scanJob?.isActive == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkInterceptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        // Verdict buttons are wired per-screen (their meaning depends on the verdict).
        // Fail-safe exit while the scan is running: cancel the scan and close WITHOUT
        // opening the link. Never forwards the URL.
        binding.btnCancelScan.setOnClickListener { cancelScanAndFinish() }

        when (intent?.action) {
            Intent.ACTION_SEND -> startShared(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> startShared(Intent.EXTRA_PROCESS_TEXT)
            else -> startTapped()
        }
    }

    private fun startTapped() {
        val url = intent?.data?.toString()
        if (url.isNullOrBlank() ||
            !(url.startsWith("http://", true) || url.startsWith("https://", true))
        ) {
            finish()
            return
        }

        // This narrow exception is user-created and exact: different paths or query values
        // still follow the normal scan/verdict flow.
        if (MonitorPreferences(applicationContext).isTapUrlExcluded(url)) {
            openInBrowser(url)
            finish()
            return
        }

        singleLinkLabel = getString(R.string.link_tapped_label)
        linkLabel = singleLinkLabel
        binding.tvScanningUrl.text = url
        scanAndDecide(listOf(url), sharedMessage = null)
    }

    /** Text handed over by another app: scan the links in it, or say plainly there were none. */
    private fun startShared(extraName: String) {
        // The sending app controls the extras; one that can't be read is the same as no text.
        val shared = runCatching { intent.getCharSequenceExtra(extraName) }.getOrNull()
        when (val intake = SharedTextIntake.parse(shared)) {
            is SharedTextIntake.Result.Links -> {
                sourceApp = "Shared"
                senderInfo = ""
                linksNotScanned = intake.totalFound - intake.urls.size
                singleLinkLabel = getString(R.string.link_shared_label)
                linkLabel = when {
                    intake.urls.size == 1 && intake.totalFound == 1 -> singleLinkLabel
                    intake.urls.size == intake.totalFound ->
                        getString(R.string.link_shared_multi_label, intake.urls.size)
                    else -> getString(
                        R.string.link_shared_capped_label, intake.urls.size, intake.totalFound
                    )
                }
                binding.tvScanningUrl.text = intake.urls.joinToString("\n")
                scanAndDecide(intake.urls, sharedMessage = intake.messageText)
            }
            is SharedTextIntake.Result.NoLink -> showNoLinkScreen(intake.text)
            SharedTextIntake.Result.Empty -> showNoLinkScreen(null)
        }
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

    /**
     * Scans every link and shows one card. A tap is always one link; shared text can hold a
     * few, and the card then shows the riskiest: checking only the first would let whoever
     * wrote the message put a clean link in front of the bad one.
     */
    private fun scanAndDecide(urls: List<String>, sharedMessage: String?) {
        scanJob = lifecycleScope.launch {
            val results = urls.map { url ->
                async {
                    runCatching {
                        // The words shared with the link are its scan context; a link that
                        // arrived in a notification keeps that message instead.
                        val messageText = sharedMessage
                            ?: ScannerProvider.recentMessageContext.lookup(url)
                        ScannerProvider.orchestrator.scan(url, messageText = messageText)
                            .toLegacy(sourceApp = sourceApp, senderInfo = senderInfo)
                    }.getOrNull()
                }
            }.awaitAll()

            val repository = ScanRepository(applicationContext)
            results.filterNotNull().forEach { result ->
                runCatching { repository.saveScan(result) }
            }

            if (isFinishing || isDestroyed) return@launch

            when (val outcome = cardOutcomeOf(urls, results, linksNotScanned)) {
                is CardOutcome.Block -> showBlockScreen(outcome.result)
                is CardOutcome.ScanFailed -> showUncheckedScreen(outcome.url)
                CardOutcome.PartlyChecked -> showPartlyCheckedScreen()
                is CardOutcome.Safe -> showSafeScreen(outcome.result)
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

        // Partial coverage is its own claim: some providers answered and the link passed them.
        // Reporting that as "local rules only" would understate what was actually checked.
        binding.tvReason.text = getString(
            when (ScoringEngine.coverageStateOf(result.flags)) {
                ScoringEngine.CoverageState.LOCAL_ONLY -> R.string.link_safe_local_only
                ScoringEngine.CoverageState.PARTIAL -> R.string.link_safe_partial
                ScoringEngine.CoverageState.FULL -> R.string.link_safe_verified
            }
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
    private fun showUncheckedScreen(url: String) {
        val color = ContextCompat.getColor(this, R.color.e_amber)
        hideScanningState()

        setBadge(color, R.drawable.ic_stat_suspicious, getString(R.string.link_unchecked_title))
        setTappedLink(url, null, color, singleLinkLabel)
        binding.tvReason.text = getString(R.string.link_unchecked_reason)
        binding.tvReason.setTextColor(ContextCompat.getColor(this, R.color.e_text2))
        binding.tvReason.visibility = View.VISIBLE

        configurePrimary(R.string.btn_dont_open) { finish() }
        configureSecondary(R.string.btn_open_anyway) { openInBrowser(url); finish() }
        binding.tvDangerOverride.visibility = View.GONE
        binding.buttonRow.visibility = View.VISIBLE
    }

    /**
     * Shared text with more links than were scanned, where the scanned ones found nothing.
     * Not a green card: whoever wrote the message chose the order of the links.
     */
    private fun showPartlyCheckedScreen() {
        val color = ContextCompat.getColor(this, R.color.e_amber)
        hideScanningState()

        setBadge(color, R.drawable.ic_stat_suspicious, getString(R.string.share_partly_checked_title))
        binding.tvReason.text = getString(R.string.share_partly_checked_reason)
        binding.tvReason.setTextColor(ContextCompat.getColor(this, R.color.e_text2))
        binding.tvReason.visibility = View.VISIBLE

        configurePrimary(R.string.btn_close) { finish() }
        binding.btnSecondary.visibility = View.GONE
        binding.tvDangerOverride.visibility = View.GONE
        binding.buttonRow.visibility = View.VISIBLE
    }

    /**
     * Shared text with no link in it. Nothing was scanned, so the card makes no claim about
     * the message either way. [text] is null when nothing readable was shared.
     */
    private fun showNoLinkScreen(text: String?) {
        val color = ContextCompat.getColor(this, R.color.e_muted)
        hideScanningState()

        setBadge(color, iconRes = null, getString(R.string.share_no_link_title))
        if (text != null) {
            binding.tvUrlLabel.text = getString(R.string.share_text_label)
            binding.tvUrl.typeface = Typeface.DEFAULT
            binding.tvUrl.text = text
            binding.urlChip.visibility = View.VISIBLE
        }
        binding.tvReason.text = getString(R.string.share_no_link_reason)
        binding.tvReason.setTextColor(ContextCompat.getColor(this, R.color.e_text2))
        binding.tvReason.visibility = View.VISIBLE

        configurePrimary(R.string.btn_close) { finish() }
        binding.btnSecondary.visibility = View.GONE
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
    private fun setTappedLink(url: String, resolvedUrl: String?, color: Int, label: String = linkLabel) {
        binding.tvUrlLabel.text = label
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

    private fun setBadge(color: Int, iconRes: Int?, text: String) {
        if (iconRes != null) {
            binding.ivStatusIcon.setImageResource(iconRes)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
        } else {
            binding.ivStatusIcon.visibility = View.GONE
        }
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
            result.flags.filter(ScoringEngine::isCoverageWarning)
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

/**
 * The result a card should show when several links were checked: the highest threat level,
 * then the highest score. Ties keep the earliest link, so an all-safe text shows its first.
 */
internal fun riskiestOf(results: List<ScanResult>): ScanResult? =
    results.maxWithOrNull(compareBy<ScanResult> { it.threatLevel }.thenBy { it.riskScore })

/** Which card to show once every scan has returned. */
internal sealed interface CardOutcome {
    data class Block(val result: ScanResult) : CardOutcome
    data class ScanFailed(val url: String) : CardOutcome
    object PartlyChecked : CardOutcome
    data class Safe(val result: ScanResult) : CardOutcome
}

/**
 * A flagged link always wins. After that, anything that stops the card from vouching for the
 * whole text comes before SAFE: a scan that failed outright (nothing ran for that link, not
 * even the local rules), then links that were never scanned because the text held more than
 * the limit.
 *
 * @param results one entry per [urls], null where that scan failed
 * @param linksNotScanned links found in the text beyond the ones in [urls]
 */
internal fun cardOutcomeOf(
    urls: List<String>,
    results: List<ScanResult?>,
    linksNotScanned: Int,
): CardOutcome {
    val riskiest = riskiestOf(results.filterNotNull())
    val failedUrl = urls.filterIndexed { i, _ -> results[i] == null }.firstOrNull()
    return when {
        riskiest != null && riskiest.threatLevel != ThreatLevel.SAFE -> CardOutcome.Block(riskiest)
        failedUrl != null -> CardOutcome.ScanFailed(failedUrl)
        linksNotScanned > 0 -> CardOutcome.PartlyChecked
        // Every url has a result here, so riskiest is non-null whenever urls is non-empty.
        else -> CardOutcome.Safe(riskiest!!)
    }
}

package com.linkguard.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.linkguard.app.BuildConfig
import com.linkguard.app.R
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ActivityMainBinding
import com.linkguard.app.databinding.DialogUpdateAvailableBinding
import com.linkguard.app.databinding.DialogUpdateProgressBinding
import com.linkguard.app.databinding.ItemUpdateNoteBinding
import com.linkguard.app.scanner.PaymentQrValidator
import com.linkguard.app.scanner.QrType
import com.linkguard.app.scanner.QrTypeDetector
import com.linkguard.app.scanner.UrlInputNormalizer
import com.linkguard.app.update.UpdateChecker
import com.linkguard.app.update.UpdateInfo
import com.linkguard.app.update.UpdateInstaller
import com.linkguard.app.update.formatReleaseNotes
import com.linkguard.app.util.AlertCapabilities
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.ExcludedUrlMatcher
import com.linkguard.app.util.MonitorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage

@OptIn(ExperimentalGetImage::class)
class MainActivity : AppCompatActivity() {

    companion object {
        // Process-wide so foreground returns don't re-check more often than the configured interval
        private var lastUpdateCheckMs = 0L
        // Ask for the notification permission at most once per process so we don't nag on every resume.
        private var notificationPermissionAsked = false
        // How often the download progress dialog polls DownloadManager for bytes/state.
        private const val POLL_INTERVAL_MS = 350L
    }

    // ─── Notification permission (POST_NOTIFICATIONS, API 33+) ─────────────────

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showSnackbar(getString(R.string.notifications_permission_needed))
        }

    private var updateDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogUpdateProgressBinding? = null
    private var downloadId: Long = UpdateInstaller.NO_DOWNLOAD
    private var pollJob: Job? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: ScanHistoryAdapter
    private val monitorPrefs by lazy { MonitorPreferences(this) }

    // ─── Animations ───────────────────────────────────────────────────────────

    private var dotPulseAnimator: ObjectAnimator? = null

    // ─── QR Scanner Launcher ──────────────────────────────────────────────────

    private val qrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val qr = result.data?.getStringExtra("QR_RESULT") ?: return@registerForActivityResult
            handleQrResult(qr)
        }

    // ─── Broadcast Receiver ───────────────────────────────────────────────────

    private val scanUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.loadData()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupRecyclerView()
        setupManualScan()
        setupButtons()
        setupAnimations()
        observeViewModel()
        registerScanReceiver()
        setupVersionFooter()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadData()
        updateExcludedUrlsLabel()
        updateProtectionStatus()
        maybeRequestNotificationPermission()
        startAnimations()
        checkForUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(scanUpdateReceiver) }
        updateDialog?.dismiss()
        stopPolling()
        progressDialog?.dismiss()
    }

    // ─── Animations ───────────────────────────────────────────────────────────

    private fun setupAnimations() {
        // Live "heartbeat" on the status dot so the badge reads as actively monitoring.
        dotPulseAnimator = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1400
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
    }

    private fun startAnimations() {
        dotPulseAnimator?.start()
    }

    private fun stopAnimations() {
        dotPulseAnimator?.cancel()
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnScanQR.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }

        binding.btnClearHistory.setOnClickListener {
            if (viewModel.scans.value.isNullOrEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.yes) { _, _ -> viewModel.clearHistory() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnHistory.setOnClickListener { openHistory() }
        binding.btnViewAll.setOnClickListener { openHistory() }
        binding.cardThreats.setOnClickListener { openHistory(ThreatLevel.DANGER.name) }
        binding.cardSuspicious.setOnClickListener { openHistory(ThreatLevel.SUSPICIOUS.name) }
        binding.cardSafe.setOnClickListener { openHistory(ThreatLevel.SAFE.name) }

        binding.tvProtectionStatus.setOnClickListener {
            if (!isNotificationServiceEnabled()) {
                showNotificationAccessDialog()
            } else {
                // Protection is on; make sure alerts can actually reach the user.
                val gaps = alertDeliveryGaps()
                if (gaps.isNotEmpty()) showAlertDeliveryDialog(gaps)
            }
        }

        // Set state before attaching the listener so restoring it doesn't fire the snackbar.
        binding.switchScanAllApps.isChecked = monitorPrefs.scanAllApps
        binding.switchScanAllApps.setOnCheckedChangeListener { _, checked ->
            monitorPrefs.scanAllApps = checked
            showSnackbar(
                getString(if (checked) R.string.scan_all_apps_on else R.string.scan_all_apps_off)
            )
        }

        binding.btnExcludedUrls.setOnClickListener { showExcludedUrlsDialog() }
    }

    private fun showExcludedUrlsDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.excluded_urls_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
            minLines = 4
            maxLines = 8
            setText(monitorPrefs.excludedUrls.sorted().joinToString("\n"))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.excluded_urls)
            .setMessage(R.string.excluded_urls_message)
            .setView(input)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entries = input.text.toString().lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
                val invalid = entries.filter { ExcludedUrlMatcher.normalize(it) == null }
                if (invalid.isNotEmpty()) {
                    input.error = getString(R.string.excluded_urls_invalid, invalid.first())
                    return@setOnClickListener
                }

                monitorPrefs.excludedUrls = entries.mapNotNull(ExcludedUrlMatcher::normalize).toSet()
                updateExcludedUrlsLabel()
                showSnackbar(getString(R.string.excluded_urls_saved))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateExcludedUrlsLabel() {
        binding.btnExcludedUrls.text = getString(
            R.string.excluded_urls_count,
            monitorPrefs.excludedUrls.size,
        )
    }

    private fun openHistory(filter: String? = null) {
        val intent = Intent(this, HistoryActivity::class.java)
        filter?.let { intent.putExtra("FILTER_TYPE", it) }
        startActivity(intent)
    }

    private fun setupRecyclerView() {
        adapter = ScanHistoryAdapter(
            onClick = { scan -> startActivity(ScanDetailActivity.newIntent(this, scan)) },
            onLongClick = { scan -> showDeleteConfirmation(scan) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun showDeleteConfirmation(scan: ScanResult) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_scan)
            .setMessage(R.string.delete_scan_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteScan(scan) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupManualScan() {
        binding.btnScan.setOnClickListener { triggerManualScan() }
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerManualScan(); true
            } else false
        }
    }

    private fun registerScanReceiver() {
        ContextCompat.registerReceiver(
            this,
            scanUpdateReceiver,
            IntentFilter(AppConfig.SCAN_COMPLETE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ─── Scan Logic ───────────────────────────────────────────────────────────

    private fun triggerManualScan() {
        val raw = binding.etUrl.text.toString().trim()
        if (raw.isBlank()) {
            binding.etUrl.error = getString(R.string.error_enter_url)
            return
        }
        val url = UrlInputNormalizer.normalize(raw) ?: run {
            binding.etUrl.error = getString(R.string.error_invalid_url)
            return
        }
        binding.etUrl.error = null
        binding.etUrl.text?.clear()
        viewModel.manualScan(url, sourceApp = "Manual")
    }

    private fun handleQrResult(qr: String) {
        when (QrTypeDetector.detect(qr)) {
            QrType.URL -> {
                val url = UrlInputNormalizer.normalize(qr) ?: run {
                    showSnackbar(getString(R.string.error_invalid_url_qr))
                    return
                }
                viewModel.manualScan(url, "QR")
            }
            QrType.PAYMENT_QR -> {
                val validation = PaymentQrValidator.validate(qr)
                val result = ScanResult(
                    url = "Payment QR: ${validation.merchantName ?: "Unknown"}",
                    // A valid EMV checksum proves format integrity, not that the recipient account
                    // belongs to the person or merchant the user intended to pay.
                    threatLevel = ThreatLevel.SUSPICIOUS,
                    riskScore = if (validation.isValid) 25 else 50,
                    category = if (validation.isValid) "Payment QR — Payee unverified" else "Invalid Payment QR",
                    flags = if (validation.isValid) {
                        listOf("QR format and checksum are valid, but the recipient account was not verified")
                    } else {
                        listOf(validation.message)
                    },
                    sourceApp = "QR",
                    senderInfo = "Physical Code"
                )
                viewModel.saveResultManually(result)

                val message = if (validation.isValid) {
                    buildString {
                        append(getString(R.string.payment_qr_valid_header))
                        validation.merchantName?.let { append(getString(R.string.payment_qr_merchant_line, it)) }
                        validation.merchantCity?.let { append(getString(R.string.payment_qr_city_line, it)) }
                        append(getString(R.string.payment_qr_verify_reminder))
                    }
                } else {
                    getString(R.string.payment_qr_invalid_format, validation.message)
                }
                showSnackbar(message)
            }
            QrType.UNKNOWN -> showSnackbar(getString(R.string.error_unsupported_qr))
        }
    }

    // ─── ViewModel Observers ──────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.scans.observe(this) { scans ->
            adapter.submitList(scans.map { HistoryItem.Scan(it) })
            if (scans.isNotEmpty()) {
                val latestScanTime = scans.first().scannedAt
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    latestScanTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                binding.tvLastScan.text = getString(R.string.last_scan_format, relativeTime)
            } else {
                binding.tvLastScan.text = getString(R.string.no_scans_yet)
            }

            val isEmpty = scans.isEmpty()
            binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.btnClearHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.btnViewAll.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        viewModel.stats.observe(this) { stats ->
            binding.tvDangerCount.text    = stats.dangerCount.toString()
            binding.tvSuspiciousCount.text = stats.suspiciousCount.toString()
            binding.tvSafeCount.text      = stats.safeCount.toString()
        }

        viewModel.isScanning.observe(this) { scanning ->
            binding.btnScan.isEnabled = !scanning
            binding.btnScan.text = getString(if (scanning) R.string.scanning else R.string.scan)
            binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        }

        viewModel.manualScanResult.observe(this) { result ->
            result ?: return@observe
            startActivity(ScanDetailActivity.newIntent(this, result))
            viewModel.clearManualResult()
        }

        viewModel.scanError.observe(this) { error ->
            error ?: return@observe
            showSnackbar(error)
            viewModel.clearScanError()
        }
    }

    // ─── App Updates ──────────────────────────────────────────────────────────

    private fun setupVersionFooter() {
        binding.tvVersion.text = getString(R.string.version_footer, BuildConfig.VERSION_NAME)
        binding.tvVersion.setOnClickListener { checkForUpdates(manual = true) }
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (!manual) {
            // Automatic checks are throttled; runs on every onResume (cold start,
            // back from background, back from another screen).
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheckMs < AppConfig.UPDATE_CHECK_INTERVAL_MS) return
            lastUpdateCheckMs = now
        }
        lifecycleScope.launch {
            val update = UpdateChecker(ScannerProvider.okHttpClient).fetchLatestRelease()
            if (update == null) {
                if (manual) showSnackbar(getString(R.string.update_check_failed))
                return@launch
            }
            if (!UpdateChecker.isNewerVersion(update.versionName, BuildConfig.VERSION_NAME)) {
                if (manual) showSnackbar(getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            if (updateDialog?.isShowing == true) return@launch
            showUpdateDialog(update)
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        val view = DialogUpdateAvailableBinding.inflate(layoutInflater)
        view.tvUpdateVersion.text = getString(R.string.update_version_label, update.versionName)
        view.tvUpdateCurrent.text = getString(R.string.update_current_label, BuildConfig.VERSION_NAME)
        populateNotes(view.notesContainer, update.releaseNotes)

        val dialog = AlertDialog.Builder(this).setView(view.root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.btnUpdateNow.setOnClickListener {
            dialog.dismiss()
            startUpdateDownload(update)
        }
        view.tvUpdateLater.setOnClickListener { dialog.dismiss() }
        updateDialog = dialog
        dialog.show()
    }

    /** Renders the GitHub release notes as a clean bullet/header list in the hero card. */
    private fun populateNotes(container: LinearLayout, raw: String) {
        container.removeAllViews()
        val lines = formatReleaseNotes(raw)
        if (lines.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        lines.forEach { line ->
            val row = ItemUpdateNoteBinding.inflate(layoutInflater, container, false)
            row.tvNote.text = line.text
            if (line.isHeader) {
                row.noteDot.visibility = View.GONE
                row.tvNote.setTextColor(getColor(R.color.text_primary))
                row.tvNote.setTypeface(row.tvNote.typeface, Typeface.BOLD)
            }
            container.addView(row.root)
        }
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        val apkUrl = update.apkUrl
        if (apkUrl == null) {
            // Release has no APK attached — open the release page instead.
            startActivity(Intent(Intent.ACTION_VIEW, update.htmlUrl.toUri()))
            return
        }
        val fileName = update.apkName ?: "LinkGuard-${update.versionName}.apk"
        downloadId = UpdateInstaller.downloadAndInstall(this, apkUrl, fileName)
        if (downloadId == UpdateInstaller.NO_DOWNLOAD) {
            // URL was refused by the allow-list — fall back to the release page.
            startActivity(Intent(Intent.ACTION_VIEW, update.htmlUrl.toUri()))
            return
        }
        showProgressDialog(update)
        startPolling()
    }

    private fun showProgressDialog(update: UpdateInfo) {
        val view = DialogUpdateProgressBinding.inflate(layoutInflater)
        progressBinding = view
        val dialog = AlertDialog.Builder(this)
            .setView(view.root)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.tvCancel.setOnClickListener {
            UpdateInstaller.cancel(this, downloadId)
            stopPolling()
            dialog.dismiss()
        }
        view.btnRetry.setOnClickListener {
            dialog.dismiss()
            startUpdateDownload(update)
        }
        view.tvDismiss.setOnClickListener {
            stopPolling()
            dialog.dismiss()
        }
        progressDialog = dialog
        dialog.show()
    }

    /** Polls DownloadManager so the dialog shows a live percentage and reacts to pause/fail. */
    private fun startPolling() {
        stopPolling()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                val status = withContext(Dispatchers.IO) {
                    UpdateInstaller.queryStatus(this@MainActivity, downloadId)
                }
                renderProgress(status)
                when (status) {
                    is UpdateInstaller.DownloadStatus.Succeeded -> {
                        // The verified install prompt (UpdateInstaller's receiver) takes over.
                        progressDialog?.dismiss()
                        return@launch
                    }
                    is UpdateInstaller.DownloadStatus.Failed -> {
                        showFailedState()
                        return@launch
                    }
                    else -> delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun renderProgress(status: UpdateInstaller.DownloadStatus) {
        val b = progressBinding ?: return
        when (status) {
            is UpdateInstaller.DownloadStatus.Running ->
                updateProgressUi(b, status.percent, status.soFar, status.total,
                    R.string.update_progress_title, R.string.update_status_downloading,
                    R.drawable.ic_download, getColor(R.color.e_blue))
            is UpdateInstaller.DownloadStatus.Paused ->
                updateProgressUi(b, status.percent, status.soFar, status.total,
                    R.string.update_paused_title, R.string.update_status_waiting,
                    R.drawable.ic_cloud_off, getColor(R.color.e_amber))
            is UpdateInstaller.DownloadStatus.Pending ->
                updateProgressUi(b, 0, 0L, 0L,
                    R.string.update_progress_title, R.string.update_status_downloading,
                    R.drawable.ic_download, getColor(R.color.e_blue))
            else -> Unit // Succeeded / Failed handled by the caller
        }
    }

    private fun updateProgressUi(
        b: DialogUpdateProgressBinding, percent: Int, soFar: Long, total: Long,
        titleRes: Int, statusRes: Int, statusIcon: Int, color: Int
    ) {
        b.progressGroup.visibility = View.VISIBLE
        b.failedGroup.visibility = View.GONE
        b.tvProgressTitle.setText(titleRes)
        b.tvPercent.text = getString(R.string.update_percent, percent)
        b.tvPercent.setTextColor(color)
        if (total > 0L) {
            b.tvBytes.visibility = View.VISIBLE
            b.tvBytes.text = getString(R.string.update_bytes, formatBytes(soFar), formatBytes(total))
        } else {
            b.tvBytes.visibility = View.GONE
        }
        b.progressBar.setIndicatorColor(color)
        b.progressBar.setProgressCompat(percent, true)
        b.ivStatus.setImageResource(statusIcon)
        b.ivStatus.imageTintList = ColorStateList.valueOf(color)
        b.tvStatus.setText(statusRes)
        b.tvStatus.setTextColor(color)
    }

    private fun showFailedState() {
        val b = progressBinding ?: return
        b.progressGroup.visibility = View.GONE
        b.failedGroup.visibility = View.VISIBLE
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        return String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isNotificationServiceEnabled(): Boolean {
        val pkg = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(pkg)
    }

    private fun showNotificationAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.realtime_protection)
            .setMessage(R.string.notification_permission_msg)
            .setPositiveButton(R.string.settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // ─── Alert delivery (POST_NOTIFICATIONS + full-screen intent) ───────────────

    /**
     * Threat alerts are delivered as a high-priority full-screen-intent notification
     * (see ThreatAlertHelper). On API 33+ that requires POST_NOTIFICATIONS, and on API 34+
     * the full-screen popup requires the user to allow full-screen intents. Surface and
     * help fix whichever is missing so alerts aren't silently suppressed.
     */
    private fun alertDeliveryGaps(): List<AlertCapabilities.Gap> = AlertCapabilities.missing(
        sdkInt = Build.VERSION.SDK_INT,
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        canUseFullScreenIntent = canUseFullScreenIntent(),
    )

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < AlertCapabilities.SDK_UPSIDE_DOWN_CAKE) return true
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    private fun maybeRequestNotificationPermission() {
        if (notificationPermissionAsked) return
        if (Build.VERSION.SDK_INT < AlertCapabilities.SDK_TIRAMISU) return
        // Only prompt once protection is on — that's the only path that posts alerts.
        if (!isNotificationServiceEnabled()) return
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        notificationPermissionAsked = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showAlertDeliveryDialog(gaps: List<AlertCapabilities.Gap>) {
        val message = buildString {
            if (AlertCapabilities.Gap.NOTIFICATIONS_DISABLED in gaps) {
                append(getString(R.string.alert_gap_notifications))
            }
            if (AlertCapabilities.Gap.FULL_SCREEN_INTENT_BLOCKED in gaps) {
                if (isNotEmpty()) append("\n\n")
                append(getString(R.string.alert_gap_fullscreen))
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.alert_delivery_title)
            .setMessage(message)
            .setNegativeButton(R.string.not_now, null)
        if (AlertCapabilities.Gap.NOTIFICATIONS_DISABLED in gaps) {
            builder.setPositiveButton(R.string.enable_notifications) { _, _ ->
                requestNotificationPermission()
            }
        }
        if (AlertCapabilities.Gap.FULL_SCREEN_INTENT_BLOCKED in gaps) {
            builder.setNeutralButton(R.string.full_screen_settings) { _, _ ->
                openFullScreenIntentSettings()
            }
        }
        builder.show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= AlertCapabilities.SDK_TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < AlertCapabilities.SDK_UPSIDE_DOWN_CAKE) return
        val fsiSettings = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData("package:$packageName".toUri())
        runCatching { startActivity(fsiSettings) }.onFailure {
            // Fall back to the app's notification settings if the OEM lacks the FSI screen.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun updateProtectionStatus() {
        val enabled = isNotificationServiceEnabled()
        binding.tvHeroHeadline.setText(if (enabled) R.string.home_protected else R.string.home_unprotected)
        binding.tvProtectionStatus.text = if (enabled) getString(R.string.protection_active) else getString(R.string.protection_inactive)
        // Option E hero pill: text stays white for contrast on the gradient; the dot carries the state colour.
        binding.tvProtectionStatus.setTextColor(ContextCompat.getColor(this, R.color.e_text))
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (enabled) R.color.e_green_soft else R.color.e_red_soft)
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}

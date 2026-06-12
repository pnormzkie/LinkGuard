package com.linkguard.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import com.linkguard.app.scanner.PaymentQrValidator
import com.linkguard.app.scanner.QrType
import com.linkguard.app.scanner.QrTypeDetector
import com.linkguard.app.update.UpdateChecker
import com.linkguard.app.update.UpdateInfo
import com.linkguard.app.update.UpdateInstaller
import com.linkguard.app.util.AppConfig
import kotlinx.coroutines.launch
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
    }

    private var updateDialog: AlertDialog? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: ScanHistoryAdapter

    // ─── Animations ───────────────────────────────────────────────────────────

    private var rotationAnimator: ObjectAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null

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
        updateProtectionStatus()
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
    }

    // ─── Animations ───────────────────────────────────────────────────────────

    private fun setupAnimations() {
        rotationAnimator = ObjectAnimator.ofFloat(binding.ivShieldPulse, View.ROTATION, 0f, 360f).apply {
            duration = 4000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ivMainIcon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f, 1f)
        ).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
    }

    private fun startAnimations() {
        rotationAnimator?.start()
        pulseAnimator?.start()
    }

    private fun stopAnimations() {
        rotationAnimator?.cancel()
        pulseAnimator?.cancel()
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
            if (!isNotificationServiceEnabled()) showNotificationAccessDialog()
        }
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
        val url = normalizeUrl(raw) ?: run {
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
                val url = normalizeUrl(qr) ?: run {
                    showSnackbar(getString(R.string.error_invalid_url_qr))
                    return
                }
                viewModel.manualScan(url, "QR")
            }
            QrType.PAYMENT_QR -> {
                val validation = PaymentQrValidator.validate(qr)
                val result = ScanResult(
                    url = "Payment QR: ${validation.merchantName ?: "Unknown"}",
                    threatLevel = if (validation.isValid) ThreatLevel.SAFE else ThreatLevel.SUSPICIOUS,
                    riskScore = if (validation.isValid) 0 else 50,
                    category = "Payment QR",
                    flags = if (validation.isValid) emptyList() else listOf(validation.message),
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

    private fun normalizeUrl(input: String): String? {
        var candidate = input.trim()
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)
        ) {
            candidate = "https://$candidate"
        }
        return runCatching {
            val uri = candidate.toUri()
            val host = uri.host
            if (uri.scheme.isNullOrBlank() || host.isNullOrBlank()) return null
            if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) return null
            candidate
        }.getOrNull()
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
        val message = buildString {
            append(getString(R.string.update_available_msg, update.versionName, BuildConfig.VERSION_NAME))
            if (update.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(update.releaseNotes.take(300))
            }
        }
        updateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ -> startUpdateDownload(update) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        val apkUrl = update.apkUrl
        if (apkUrl != null) {
            UpdateInstaller.downloadAndInstall(
                this,
                apkUrl,
                update.apkName ?: "LinkGuard-${update.versionName}.apk"
            )
            showSnackbar(getString(R.string.update_downloading))
        } else {
            // Release has no APK attached — open the release page instead
            startActivity(Intent(Intent.ACTION_VIEW, update.htmlUrl.toUri()))
        }
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

    private fun updateProtectionStatus() {
        val enabled = isNotificationServiceEnabled()
        binding.tvProtectionStatus.text = if (enabled) getString(R.string.protection_active) else getString(R.string.protection_inactive)
        binding.tvProtectionStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.green else R.color.red)
        )
        binding.ivShieldPulse.setColorFilter(
            ContextCompat.getColor(this, if (enabled) R.color.green else R.color.red)
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}

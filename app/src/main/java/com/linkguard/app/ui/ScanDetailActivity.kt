package com.linkguard.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.linkguard.app.R
import com.linkguard.app.data.FlagGroup
import com.linkguard.app.data.ScanResult
import com.linkguard.app.databinding.ActivityScanDetailBinding
import com.linkguard.app.databinding.ItemFlagBinding
import com.linkguard.app.util.AppConfig.Extras

class ScanDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanDetailBinding

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(Extras.URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.invalid_scan_data), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        @Suppress("DEPRECATION")
        val flagGroups: List<FlagGroup> =
            intent.getParcelableArrayListExtra(Extras.FLAG_GROUPS) ?: emptyList()

        setupUI(
            url        = url,
            score      = intent.getIntExtra(Extras.SCORE, 0),
            category   = intent.getStringExtra(Extras.CATEGORY).orEmpty(),
            flags      = intent.getStringArrayExtra(Extras.FLAGS) ?: emptyArray(),
            flagGroups = flagGroups,
            sender     = intent.getStringExtra(Extras.SENDER).orEmpty(),
            app        = intent.getStringExtra(Extras.APP).orEmpty(),
            threatLevel = intent.getStringExtra(Extras.THREAT_LEVEL) ?: "SAFE"
        )
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI(
        url: String, score: Int, category: String,
        flags: Array<String>, flagGroups: List<FlagGroup>,
        sender: String, app: String, threatLevel: String
    ) {
        // Status + color + icon
        val (statusText, colorRes, iconRes) = when (threatLevel) {
            "DANGER"     -> Triple(getString(R.string.status_dangerous_link), R.color.red, R.drawable.ic_stat_threat)
            "SUSPICIOUS" -> Triple(getString(R.string.status_suspicious_link), R.color.yellow, R.drawable.ic_stat_suspicious)
            else         -> Triple(getString(R.string.status_safe_link), R.color.green, R.drawable.ic_stat_safe)
        }
        val color = getColor(colorRes)

        // Score ring & Center text
        binding.scoreRing.setIndicatorColor(color)
        binding.scoreRing.progress = score
        binding.tvScoreLarge.text = "$score%"
        binding.tvScoreLarge.setTextColor(color)

        // Status badge
        binding.tvStatus.text = statusText
        binding.tvStatus.setTextColor(color)
        binding.ivStatusIcon.setImageResource(iconRes)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
        binding.statusBadgeContainer.backgroundTintList = ColorStateList.valueOf(color).withAlpha(30)

        // Other fields
        binding.tvUrl.text      = url
        binding.tvCategory.text = category
        binding.tvSender.text   = if (app == "QR") getString(R.string.source_qr_scan) else getString(R.string.source_manual_scan)

        // Flags
        setupFlags(flags, flagGroups, color)

        // Buttons
        binding.btnCopy.setOnClickListener { copyUrl(url) }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupFlags(flags: Array<String>, groups: List<FlagGroup>, color: Int) {
        if (flags.isEmpty() && groups.isEmpty()) {
            binding.tvNoFlags.visibility    = View.VISIBLE
            binding.flagsContainer.visibility = View.GONE
            return
        }
        binding.tvNoFlags.visibility    = View.GONE
        binding.flagsContainer.visibility = View.VISIBLE
        binding.flagsContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        if (groups.isNotEmpty()) {
            // Fresh scan: collapsible groups. A lone group auto-expands.
            val autoExpand = groups.size == 1
            groups.forEach { addFlagGroup(inflater, binding.flagsContainer, it, color, autoExpand) }
            val grouped = groups.flatMapTo(HashSet()) { it.items }
            flags.filter { it !in grouped }
                .forEach { addFlagNote(inflater, binding.flagsContainer, it) }
        } else {
            // History (no category data persisted): the flat list, as before.
            flags.forEach { flag ->
                val flagBinding = ItemFlagBinding.inflate(inflater, binding.flagsContainer, false)
                flagBinding.tvFlag.text = flag
                styleFlagRow(flagBinding, color)
                binding.flagsContainer.addView(flagBinding.root)
            }
        }
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService<ClipboardManager>()
        clipboard?.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show()
    }

    // ─── Intent Factory ───────────────────────────────────────────────────────

    companion object {
        fun newIntent(
            context: Context,
            result: ScanResult,
            sender: String = result.senderInfo,
            app: String    = result.sourceApp
        ): Intent = baseIntent(context).apply {
            putExtra(Extras.URL,          result.url)
            putExtra(Extras.SCORE,        result.riskScore)
            putExtra(Extras.CATEGORY,     result.category)
            putExtra(Extras.FLAGS,        result.flags.toTypedArray())
            putExtra(Extras.SENDER,       sender)
            putExtra(Extras.APP,          app)
            putExtra(Extras.THREAT_LEVEL, result.threatLevel.name)
            // Category groups for the collapsible flag UI (empty for history-loaded results).
            putParcelableArrayListExtra(Extras.FLAG_GROUPS, ArrayList(result.flagGroups))
        }

        fun newIntent(
            context: Context,
            url: String, score: Int, category: String,
            flags: List<String>, sender: String, app: String, threatLevel: String
        ): Intent = baseIntent(context).apply {
            putExtra(Extras.URL,          url)
            putExtra(Extras.SCORE,        score)
            putExtra(Extras.CATEGORY,     category)
            putExtra(Extras.FLAGS,        flags.toTypedArray())
            putExtra(Extras.SENDER,       sender)
            putExtra(Extras.APP,          app)
            putExtra(Extras.THREAT_LEVEL, threatLevel)
        }

        private fun baseIntent(context: Context) =
            Intent(context, ScanDetailActivity::class.java)
    }
}

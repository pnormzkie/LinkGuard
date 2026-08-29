package com.linkguard.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.linkguard.app.R
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.databinding.ActivityThreatAlertBinding

/**
 * Full-screen dialog activity that pops up over everything when a dangerous or suspicious link is detected.
 * Designed to look like a system emergency/earthquake alert.
 */
class ThreatAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreatAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityThreatAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)

        if (!renderIntent(intent)) {
            finish()
            return
        }

        binding.btnDismiss.setOnClickListener { finish() }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)

        if (!renderIntent(newIntent)) {
            finish()
        }
    }

    private fun renderIntent(sourceIntent: Intent): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableExtra(EXTRA_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            sourceIntent.getParcelableExtra(EXTRA_SCAN_RESULT)
        } ?: return false

        val isDanger = result.threatLevel == ThreatLevel.DANGER
        binding.alertTitle.text =
            if (isDanger) getString(R.string.threat_alert_title)
            else getString(R.string.suspicious_link_title)

        // Set icon based on threat level
        if (isDanger) {
            binding.alertIcon.setImageResource(android.R.drawable.ic_dialog_alert)
        } else {
            binding.alertIcon.setImageResource(R.drawable.ic_stat_suspicious)
        }

        val appSource = result.sourceApp.ifBlank { getString(R.string.threat_alert_source_system) }
        val messagePrefix =
            if (isDanger) getString(R.string.threat_prefix_dangerous)
            else getString(R.string.threat_prefix_suspicious)

        binding.alertMessage.text =
            getString(R.string.threat_alert_message, messagePrefix, result.senderInfo, appSource, result.url)

        binding.btnViewReport.setOnClickListener {
            startActivity(ScanDetailActivity.newIntent(this, result))
            finish()
        }
        return true
    }

    companion object {
        private const val EXTRA_SCAN_RESULT = "scan_result"

        fun newIntent(context: Context, result: ScanResult): Intent {
            return Intent(context, ThreatAlertActivity::class.java).apply {
                putExtra(EXTRA_SCAN_RESULT, result)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}

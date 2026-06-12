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

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("scan_result", ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("scan_result")
        }

        result?.let {
            val isDanger = it.threatLevel == ThreatLevel.DANGER
            binding.alertTitle.text =
                if (isDanger) getString(R.string.threat_alert_title)
                else getString(R.string.suspicious_link_title)
            
            // Set icon based on threat level
            if (isDanger) {
                binding.alertIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            } else {
                binding.alertIcon.setImageResource(R.drawable.ic_stat_suspicious)
            }
            
            val appSource = it.sourceApp.ifBlank { getString(R.string.threat_alert_source_system) }
            val messagePrefix =
                if (isDanger) getString(R.string.threat_prefix_dangerous)
                else getString(R.string.threat_prefix_suspicious)

            binding.alertMessage.text =
                getString(R.string.threat_alert_message, messagePrefix, it.senderInfo, appSource, it.url)
            
            binding.btnViewReport.setOnClickListener { _ ->
                startActivity(ScanDetailActivity.newIntent(this, it))
                finish()
            }
        }

        binding.btnDismiss.setOnClickListener { finish() }
    }

    companion object {
        fun newIntent(context: Context, result: ScanResult): Intent {
            return Intent(context, ThreatAlertActivity::class.java).apply {
                putExtra("scan_result", result)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}

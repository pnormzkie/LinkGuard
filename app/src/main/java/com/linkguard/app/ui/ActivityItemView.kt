package com.linkguard.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.linkguard.app.R
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a single activity row view programmatically to match the provided design.
 */
object ActivityItemView {

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun build(context: Context, item: ScanResult): View {
        val dp = context.resources.displayMetrics.density

        fun Int.dp() = (this * dp).toInt()

        // Colors
        val colorDanger  = ContextCompat.getColor(context, R.color.red)
        val colorWarn    = ContextCompat.getColor(context, R.color.yellow)
        val colorGreen   = ContextCompat.getColor(context, R.color.green)
        val colorText    = ContextCompat.getColor(context, R.color.text_primary)
        val colorMuted   = ContextCompat.getColor(context, R.color.text_muted)
        val colorSurface = ContextCompat.getColor(context, R.color.surface)

        val accentColor = when (item.threatLevel) {
            ThreatLevel.DANGER     -> colorDanger
            ThreatLevel.SUSPICIOUS -> colorWarn
            ThreatLevel.SAFE       -> colorGreen
        }

        // Drawables for backgrounds
        val tagBg = when (item.threatLevel) {
            ThreatLevel.DANGER     -> ContextCompat.getDrawable(context, R.drawable.bg_tag_danger)
            ThreatLevel.SUSPICIOUS -> ContextCompat.getDrawable(context, R.drawable.bg_tag_warn)
            ThreatLevel.SAFE       -> ContextCompat.getDrawable(context, R.drawable.bg_tag_safe)
        }

        val iconBg = when (item.threatLevel) {
            ThreatLevel.DANGER     -> ContextCompat.getDrawable(context, R.drawable.bg_stat_danger)
            ThreatLevel.SUSPICIOUS -> ContextCompat.getDrawable(context, R.drawable.bg_stat_warn)
            ThreatLevel.SAFE       -> ContextCompat.getDrawable(context, R.drawable.bg_stat_safe)
        }

        val riskBg = when (item.threatLevel) {
            ThreatLevel.DANGER     -> ContextCompat.getDrawable(context, R.drawable.bg_risk_danger)
            ThreatLevel.SUSPICIOUS -> ContextCompat.getDrawable(context, R.drawable.bg_risk_warn)
            ThreatLevel.SAFE       -> ContextCompat.getDrawable(context, R.drawable.bg_risk_safe)
        }

        val tagLabel = when (item.threatLevel) {
            ThreatLevel.DANGER     -> "DANGER"
            ThreatLevel.SUSPICIOUS -> "SUSPICIOUS"
            ThreatLevel.SAFE       -> "SAFE"
        }

        // ── Root FrameLayout ──
        val root = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(20.dp(), 0, 20.dp(), 12.dp()) }
        }

        // Card
        val card = CardView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            radius = 24.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(colorSurface)
        }

        // Inner row
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        // Icon box (Left)
        val iconBox = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp(), 48.dp()).also {
                it.setMargins(0, 0, 16.dp(), 0)
            }
            text = when (item.threatLevel) {
                ThreatLevel.DANGER -> "⚠"
                ThreatLevel.SUSPICIOUS -> "⚡"
                ThreatLevel.SAFE -> "✓"
            }
            textSize = 20f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            background = iconBg
        }

        // Info column (Middle)
        val infoCol = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }

        // Tags row
        val tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTag = TextView(context).apply {
            text = tagLabel
            textSize = 9f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(accentColor)
            background = tagBg
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 8.dp(), 0) }
        }

        val tvSubTag = TextView(context).apply {
            text = item.category
            textSize = 9f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(colorMuted)
            background = ContextCompat.getDrawable(context, R.drawable.bg_tag_sub)
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        }

        tagRow.addView(tvTag)
        tagRow.addView(tvSubTag)

        // URL
        val tvUrl = TextView(context).apply {
            text = item.url
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(colorText)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 6.dp(), 0, 6.dp()) }
        }

        // Meta (Source · Time)
        val tvMeta = TextView(context).apply {
            text = "via ${item.sourceApp} · ${dateFormat.format(Date(item.scannedAt))}"
            textSize = 11f
            setTextColor(colorMuted)
        }

        infoCol.addView(tagRow)
        infoCol.addView(tvUrl)
        infoCol.addView(tvMeta)

        // Risk column (Right)
        val riskCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(16.dp(), 0, 0, 0) }
        }

        val tvRisk = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(50.dp(), 50.dp())
            text = "${item.riskScore}%"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            background = riskBg
        }

        val tvRiskLabel = TextView(context).apply {
            text = "RISK"
            textSize = 8f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 4.dp(), 0, 0) }
        }

        riskCol.addView(tvRisk)
        riskCol.addView(tvRiskLabel)

        row.addView(iconBox)
        row.addView(infoCol)
        row.addView(riskCol)
        card.addView(row)
        root.addView(card)

        // Left color stripe
        val stripe = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(3.dp(), FrameLayout.LayoutParams.MATCH_PARENT).also {
                it.gravity = Gravity.START
            }
            setBackgroundColor(accentColor)
        }
        root.addView(stripe)

        return root
    }
}

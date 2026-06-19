package com.linkguard.app.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.linkguard.app.R
import com.linkguard.app.data.FlagGroup
import com.linkguard.app.databinding.ItemFlagBinding
import com.linkguard.app.databinding.ItemFlagGroupBinding
import com.linkguard.app.databinding.ItemFlagGroupLineBinding

/** color with the given alpha byte (0x00–0xFF), keeping the RGB. */
private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * Tints a flat flag row (icon, text, and the glow background) to match the verdict color
 * so suspicious rows read yellow and dangerous rows read red — instead of the always-red
 * look baked into item_flag.xml / bg_flag_item.xml.
 *
 * The background is the shared bg_flag_item shape (a GradientDrawable at runtime); it is
 * mutated so retinting one row never bleeds into others.
 */
fun styleFlagRow(binding: ItemFlagBinding, color: Int) {
    binding.tvFlag.setTextColor(color)
    binding.ivFlagIcon.imageTintList = ColorStateList.valueOf(color)

    val bg = binding.root.background?.mutate() as? GradientDrawable ?: return
    val strokePx = binding.root.resources.displayMetrics.density.toInt().coerceAtLeast(1)
    // Match the original drawable's alphas: ~5% fill, ~10% stroke.
    bg.setColor(withAlpha(color, 0x0D))
    bg.setStroke(strokePx, withAlpha(color, 0x1A))
}

/**
 * Adds one collapsible flag group (e.g. "Heuristic", "Vendors flagged") to [parent]. The header
 * shows the category, a count chip, and a chevron; tapping it toggles the detail lines. All
 * accents are tinted to [color] (the verdict level). [startExpanded] opens it immediately
 * (used when a verdict has only a single group, so it isn't an extra tap).
 */
fun addFlagGroup(
    inflater: LayoutInflater,
    parent: ViewGroup,
    group: FlagGroup,
    color: Int,
    startExpanded: Boolean
) {
    val b = ItemFlagGroupBinding.inflate(inflater, parent, false)
    b.tvGroupName.text = group.category
    b.tvGroupCount.text = group.items.size.toString()
    b.tvGroupName.setTextColor(color)
    b.tvGroupCount.setTextColor(color)
    b.ivGroupIcon.imageTintList = ColorStateList.valueOf(color)
    b.ivChevron.imageTintList = ColorStateList.valueOf(color)

    val strokePx = parent.resources.displayMetrics.density.toInt().coerceAtLeast(1)
    (b.groupRoot.background?.mutate() as? GradientDrawable)?.apply {
        setColor(withAlpha(color, 0x0D))
        setStroke(strokePx, withAlpha(color, 0x1A))
    }
    (b.tvGroupCount.background?.mutate() as? GradientDrawable)?.setColor(withAlpha(color, 0x26))

    group.items.forEach { item ->
        val line = ItemFlagGroupLineBinding.inflate(inflater, b.groupItems, false)
        line.tvLine.text = item
        line.tvLine.setTextColor(color)
        b.groupItems.addView(line.root)
    }

    fun render(expanded: Boolean) {
        b.groupItems.visibility = if (expanded) View.VISIBLE else View.GONE
        b.ivChevron.rotation = if (expanded) 90f else 0f
    }
    render(startExpanded)
    b.groupHeader.setOnClickListener { render(b.groupItems.visibility != View.VISIBLE) }
    parent.addView(b.root)
}

/**
 * Adds a muted, non-grouped note line (e.g. "external checks unavailable") below the groups —
 * for flat flags that don't belong to any signal category.
 */
fun addFlagNote(inflater: LayoutInflater, parent: ViewGroup, text: String) {
    val line = ItemFlagGroupLineBinding.inflate(inflater, parent, false)
    line.tvLine.text = text
    line.tvLine.setTextColor(ContextCompat.getColor(parent.context, R.color.text_muted))
    parent.addView(line.root)
}

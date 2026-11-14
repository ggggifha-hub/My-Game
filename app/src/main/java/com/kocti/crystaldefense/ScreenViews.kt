package com.kocti.crystaldefense

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Activity.label(
    text: CharSequence,
    sizeSp: Float = 15f,
    bold: Boolean = false,
    color: Int? = null,
): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color ?: getColor(R.color.screen_text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Крупный заголовок экрана с акцентным цветом. */
internal fun Activity.screenTitle(text: CharSequence): TextView =
    label(text, sizeSp = 26f, bold = true, color = getColor(R.color.accent))

/** Строка показателя: название, цветная полоса и значение. */
internal fun Activity.statRow(
    name: String,
    value: Int,
    max: Int = 100,
    tint: Int = getColor(R.color.accent),
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(
            label(name).apply { setTextColor(getColor(R.color.text_muted)) },
            LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = max
                progress = value.coerceIn(0, max)
                progressTintList = ColorStateList.valueOf(tint)
                progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
                // Полоса чуть толще, чтобы цвет было хорошо видно.
                scaleY = 1.5f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(10)
            },
        )
        addView(
            label(value.toString(), bold = true, color = tint).apply { textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END },
            LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

internal fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
).apply { this.topMargin = topMargin }

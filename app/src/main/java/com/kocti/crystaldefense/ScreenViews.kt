package com.kocti.crystaldefense

import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Activity.label(text: CharSequence, sizeSp: Float = 15f, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(getColor(R.color.screen_text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Строка показателя: название, полоса и значение. */
internal fun Activity.statRow(name: String, value: Int, max: Int = 100): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        addView(label(name), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = max
                progress = value.coerceIn(0, max)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            label(value.toString()).apply { textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END },
            LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

internal fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
).apply { this.topMargin = topMargin }

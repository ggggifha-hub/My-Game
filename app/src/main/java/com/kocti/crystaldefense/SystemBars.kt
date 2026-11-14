package com.kocti.crystaldefense

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Начиная с Android 15 окно рисуется под системными панелями: интерфейс сдвигается, камера остаётся на весь экран. */
internal fun applySystemBarInsets(hud: View) {
    val padding = hud.paddingLeft
    hud.setOnApplyWindowInsetsListener { view, insets ->
        val (left, top, right, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }
        view.setPadding(padding + left, padding + top, padding + right, padding + bottom)
        insets
    }
}

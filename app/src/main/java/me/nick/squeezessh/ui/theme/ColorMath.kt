package me.nick.squeezessh.ui.theme

import kotlin.math.pow

/** Small ARGB-int color helpers, kept Compose-free so they are unit-testable on the JVM. */
internal object ColorMath {
    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    /** WCAG relative luminance, 0.0 (black) to 1.0 (white). */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(channel(argb, 16)) + 0.7152 * lin(channel(argb, 8)) + 0.0722 * lin(channel(argb, 0))
    }

    /** WCAG contrast ratio between two colors, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun isDark(argb: Int) = luminance(argb) < 0.5

    /** Linear blend from [from] toward [to]; [amount] 0.0 returns [from], 1.0 returns [to]. */
    fun blend(from: Int, to: Int, amount: Float): Int {
        fun mix(shift: Int) =
            (channel(from, shift) + (channel(to, shift) - channel(from, shift)) * amount).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Int): Int =
        if (contrast(background, BLACK) >= contrast(background, WHITE)) BLACK else WHITE

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}

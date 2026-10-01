package com.lumeter.ui.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * UI formatting utilities for exposure parameters.
 */
object FormatUtils {

    /** Format shutter speed as "1/250" or "2\"" */
    fun formatShutter(seconds: Double): String {
        return if (seconds >= 1.0) {
            val rounded = if (seconds == round(seconds)) {
                seconds.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", seconds)
            }
            "$rounded\""
        } else {
            "1/${round(1.0 / seconds).toInt()}"
        }
    }

    /** Format aperture as "f/5.6" */
    fun formatAperture(aperture: Double): String {
        return if (aperture == round(aperture)) {
            "f/${aperture.toInt()}"
        } else {
            String.format(Locale.US, "f/%.1f", aperture)
        }
    }

    fun formatAperture(aperture: Float): String = formatAperture(aperture.toDouble())

    /** Format ISO as "400" */
    fun formatIso(iso: Int): String = iso.toString()

    /** Format EV with one decimal place */
    fun formatEv(ev: Double): String = String.format(Locale.US, "%.1f", ev)

    /** Format exposure compensation with sign: +1.0, -0.5, ±0.0 */
    fun formatExpComp(ev: Double): String {
        val sign = when {
            ev > 0 -> "+"
            ev < 0 -> "−" // Unicode minus, not hyphen
            else -> "±"
        }
        return "$sign${String.format(Locale.US, "%.1f", abs(ev))}"
    }

    /** Find nearest value in a list by log2 distance (for exposure parameters) */
    fun <T : Number> findNearest(values: List<T>, target: Double): T {
        return values.minByOrNull { value ->
            abs(kotlin.math.ln(value.toDouble() / target) / kotlin.math.ln(2.0))
        } ?: values.first()
    }

    /** Calculate lux from EV100 */
    fun evToLux(ev100: Double): Int {
        return round(2.5 * 2.0.pow(ev100)).toInt()
    }

    // Short aliases used by the meter components.

    fun shutter(seconds: Double): String = formatShutter(seconds)

    fun aperture(value: Double): String = formatAperture(value)

    fun evText(ev: Double?): String =
        ev?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"

    fun signed(value: Double): String = formatExpComp(value)
}

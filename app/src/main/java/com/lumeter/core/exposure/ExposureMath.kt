package com.lumeter.core.exposure

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Aperture/shutter scales and EV pairing, adapted from lightstop MeterModels.kt (ExposureMath).
 * Shutter coordinates are log2(seconds): positive = longer than 1s, negative = faster.
 */

enum class ExposureStep(val denominator: Int) {
    FULL(1),
    HALF(2),
    THIRD(3),
}

data class ExposureScaleTick(
    val coordinate: Double,
    val nominalValue: Double,
)

data class ExposurePair(
    val apertureIndex: Int,
    val shutterIndex: Int,
) {
    val aperture: Double get() = ExposureMath.apertures[apertureIndex]
    val shutterSeconds: Double get() = ExposureMath.shutters[shutterIndex]
}

object ExposureMath {
    val apertures = doubleArrayOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8,
        3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0,
        10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0,
        29.0, 32.0,
    )

    val shutters = doubleArrayOf(
        30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0, 5.0, 4.0,
        3.2, 2.5, 2.0, 1.6, 1.3, 1.0, 0.8, 0.6, 0.5, 0.4,
        1.0 / 3.0, 0.25, 0.2, 1.0 / 6.0, 0.125, 0.1, 1.0 / 13.0,
        1.0 / 15.0, 1.0 / 20.0, 1.0 / 25.0, 1.0 / 30.0,
        1.0 / 40.0, 1.0 / 50.0, 1.0 / 60.0, 1.0 / 80.0,
        1.0 / 100.0, 1.0 / 125.0, 1.0 / 160.0, 1.0 / 200.0,
        1.0 / 250.0, 1.0 / 320.0, 1.0 / 400.0, 1.0 / 500.0,
        1.0 / 640.0, 1.0 / 800.0, 1.0 / 1000.0, 1.0 / 1250.0,
        1.0 / 1600.0, 1.0 / 2000.0, 1.0 / 2500.0, 1.0 / 3200.0,
        1.0 / 4000.0, 1.0 / 5000.0, 1.0 / 6400.0, 1.0 / 8000.0,
    )

    private val halfStopApertures = doubleArrayOf(
        1.0, 1.2, 1.4, 1.7, 2.0, 2.4, 2.8, 3.4, 4.0, 4.8,
        5.6, 6.7, 8.0, 9.5, 11.0, 13.0, 16.0, 19.0, 22.0, 27.0, 32.0,
    )
    private val halfStopShutters = doubleArrayOf(
        30.0, 20.0, 15.0, 10.0, 8.0, 6.0, 4.0, 3.0, 2.0, 1.5,
        1.0, 0.7, 0.5, 1.0 / 3.0, 0.25, 1.0 / 6.0, 0.125, 0.1,
        1.0 / 15.0, 1.0 / 20.0, 1.0 / 30.0, 1.0 / 45.0, 1.0 / 60.0,
        1.0 / 90.0, 1.0 / 125.0, 1.0 / 180.0, 1.0 / 250.0,
        1.0 / 350.0, 1.0 / 500.0, 1.0 / 750.0, 1.0 / 1000.0,
        1.0 / 1500.0, 1.0 / 2000.0, 1.0 / 3000.0, 1.0 / 4000.0,
        1.0 / 6000.0, 1.0 / 8000.0,
    )
    private val fullStopApertureTicks = buildApertureTicks(
        apertures.filterIndexed { index, _ -> index % 3 == 0 }.toDoubleArray(),
        ExposureStep.FULL.denominator,
    )
    private val halfStopApertureTicks = buildApertureTicks(
        halfStopApertures,
        ExposureStep.HALF.denominator,
    )
    private val thirdStopApertureTicks = buildApertureTicks(
        apertures,
        ExposureStep.THIRD.denominator,
    )
    private val fullStopShutterTicks = buildShutterTicks(
        shutters.filterIndexed { index, _ -> index % 3 == 0 }.toDoubleArray(),
        ExposureStep.FULL.denominator,
    )
    private val halfStopShutterTicks = buildShutterTicks(
        halfStopShutters,
        ExposureStep.HALF.denominator,
    )
    private val thirdStopShutterTicks = buildShutterTicks(
        shutters,
        ExposureStep.THIRD.denominator,
    )
    val apertureStops: DoubleArray =
        thirdStopApertureTicks.map(ExposureScaleTick::coordinate).toDoubleArray()
    val shutterLogSeconds: DoubleArray =
        thirdStopShutterTicks.map(ExposureScaleTick::coordinate).toDoubleArray()
    val minApertureStop: Double get() = apertureStops.first()
    val maxApertureStop: Double get() = apertureStops.last()
    val minShutterLogSeconds: Double get() = shutterLogSeconds.last()
    val maxMarkedShutterLogSeconds: Double get() = shutterLogSeconds.first()

    /** Long-exposure tail: no printed ticks after 30 seconds, but the value remains available. */
    val maxShutterLogSeconds: Double get() = maxMarkedShutterLogSeconds + 7.0

    fun primaryPair(
        ev100: Double?,
        iso: Int,
        lockedApertureIndex: Int?,
        lockedShutterIndex: Int?,
    ): ExposurePair {
        if (ev100 == null) {
            return ExposurePair(nearestIndex(apertures, 5.6), shutters.indexOfFirst { it <= 1.0 / 125.0 })
        }
        val exposureValueAtIso = ev100 + log2(iso / 100.0)
        lockedApertureIndex?.let { apertureIndex ->
            val aperture = apertures[apertureIndex]
            val desiredTime = aperture * aperture / 2.0.pow(exposureValueAtIso)
            return ExposurePair(apertureIndex, nearestIndex(shutters, desiredTime))
        }
        lockedShutterIndex?.let { shutterIndex ->
            val desiredAperture = sqrt(shutters[shutterIndex] * 2.0.pow(exposureValueAtIso))
            return ExposurePair(nearestIndex(apertures, desiredAperture), shutterIndex)
        }

        var apertureIndex = nearestIndex(apertures, 5.6)
        var desiredTime =
            apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        if (desiredTime > shutters.first()) {
            apertureIndex = apertures.lastIndex
            desiredTime = apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        } else if (desiredTime < shutters.last()) {
            apertureIndex = 0
            desiredTime = apertures[apertureIndex].pow(2.0) / 2.0.pow(exposureValueAtIso)
        }
        return ExposurePair(apertureIndex, nearestIndex(shutters, desiredTime))
    }

    fun pairForAperture(ev100: Double?, iso: Int, apertureIndex: Int): ExposurePair {
        if (ev100 == null) {
            return ExposurePair(apertureIndex, shutters.indexOfFirst { it <= 1.0 / 125.0 })
        }
        val evAtIso = ev100 + log2(iso / 100.0)
        val aperture = apertures[apertureIndex]
        val shutter = aperture * aperture / 2.0.pow(evAtIso)
        return ExposurePair(apertureIndex, nearestIndex(shutters, shutter))
    }

    fun formatAperture(value: Double): String =
        if (value >= 10.0 || abs(value - value.toInt()) < 0.01) {
            "f/${value.toInt()}"
        } else {
            "f/${"%.1f".format(value)}"
        }

    fun formatShutter(seconds: Double): String = when {
        seconds >= 1.0 -> if (abs(seconds - seconds.toInt()) < 0.02) {
            "${seconds.toInt()}″"
        } else {
            "${"%.1f".format(seconds)}″"
        }
        seconds >= 0.3 -> "${"%.1f".format(seconds)}″"
        else -> "1/${(1.0 / seconds).roundToInt()}"
    }

    fun log2(value: Double): Double = ln(value) / ln(2.0)

    fun apertureStop(value: Double): Double = 2.0 * log2(value)

    fun apertureFromStop(stop: Double): Double = 2.0.pow(stop / 2.0)

    fun apertureTicks(step: ExposureStep): List<ExposureScaleTick> = when (step) {
        ExposureStep.FULL -> fullStopApertureTicks
        ExposureStep.HALF -> halfStopApertureTicks
        ExposureStep.THIRD -> thirdStopApertureTicks
    }

    fun shutterTicks(step: ExposureStep): List<ExposureScaleTick> = when (step) {
        ExposureStep.FULL -> fullStopShutterTicks
        ExposureStep.HALF -> halfStopShutterTicks
        ExposureStep.THIRD -> thirdStopShutterTicks
    }

    fun apertureStops(step: ExposureStep): DoubleArray =
        apertureTicks(step).map(ExposureScaleTick::coordinate).toDoubleArray()

    fun shutterStops(step: ExposureStep): DoubleArray =
        shutterTicks(step).map(ExposureScaleTick::coordinate).toDoubleArray()

    fun apertureValueForCoordinate(coordinate: Double, step: ExposureStep): Double =
        nominalValueAtCoordinate(apertureTicks(step), coordinate)
            ?: apertureFromStop(coordinate)

    fun shutterValueForCoordinate(coordinate: Double, step: ExposureStep): Double =
        nominalValueAtCoordinate(shutterTicks(step), coordinate)
            ?: 2.0.pow(coordinate)

    fun nearestApertureStop(
        target: Double,
        step: ExposureStep = ExposureStep.THIRD,
    ): Double {
        val values = apertureStops(step)
        return values.minByOrNull { abs(it - target) } ?: values.first()
    }

    fun nearestShutterLogSeconds(
        target: Double,
        step: ExposureStep = ExposureStep.THIRD,
    ): Double {
        val values = shutterStops(step)
        val markedMaximum = values.maxOrNull() ?: return target
        if (target > markedMaximum) {
            return ((target.coerceAtMost(maxShutterLogSeconds) * step.denominator).roundToInt()
                .toDouble() / step.denominator).coerceAtMost(maxShutterLogSeconds)
        }
        return values.minByOrNull { abs(it - target) } ?: values.first()
    }

    private fun nearestIndex(values: DoubleArray, target: Double): Int =
        values.indices.minByOrNull { abs(log2(values[it] / target)) } ?: 0

    private fun buildApertureTicks(
        nominalValues: DoubleArray,
        denominator: Int,
    ): List<ExposureScaleTick> = nominalValues.mapIndexed { index, nominalValue ->
        ExposureScaleTick(
            coordinate = index.toDouble() / denominator,
            nominalValue = nominalValue,
        )
    }

    private fun buildShutterTicks(
        nominalValues: DoubleArray,
        denominator: Int,
    ): List<ExposureScaleTick> {
        val oneSecondIndex = nominalValues.indexOfFirst { abs(it - 1.0) < 0.0001 }
        require(oneSecondIndex >= 0) { "Shutter scale must contain one second" }
        return nominalValues.mapIndexed { index, nominalValue ->
            ExposureScaleTick(
                coordinate = (oneSecondIndex - index).toDouble() / denominator,
                nominalValue = nominalValue,
            )
        }
    }

    private fun nominalValueAtCoordinate(
        ticks: List<ExposureScaleTick>,
        coordinate: Double,
    ): Double? = ticks.firstOrNull { abs(it.coordinate - coordinate) < 0.001 }
        ?.nominalValue
}

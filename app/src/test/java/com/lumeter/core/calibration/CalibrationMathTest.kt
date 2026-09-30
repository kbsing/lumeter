package com.lumeter.core.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationMathTest {

    @Test
    fun `camera exposure reference converts to EV100`() {
        // Sunny 16: f/16, 1/125, ISO 100 → EV100 = log2(16²·100/(0.008·100)) = log2(32000) ≈ 14.97
        val ev = CalibrationMath.ev100FromCameraExposure(16.0, 1.0 / 125.0, 100.0)
        assertEquals(14.97, ev, 0.02)
    }

    @Test
    fun `gray card lux reference matches reflected metering`() {
        // 25600 lux on an 18% card: L = 0.18·25600/π ≈ 1467 cd/m², EV100 = log2(100L/12.5)
        val ev = CalibrationMath.ev100FromLuxOnGrayCard(25600.0)
        assertEquals(13.5, ev, 0.3)
    }

    @Test
    fun `correction accumulates and clamps`() {
        val first = CalibrationMath.updatedUserCorrection(0.0, 12.0, 11.5)
        assertEquals(0.5, first, 1e-9)
        val second = CalibrationMath.updatedUserCorrection(first, 12.0, 11.5)
        assertEquals(1.0, second, 1e-9)
        val huge = CalibrationMath.updatedUserCorrection(7.9, 20.0, 5.0)
        assertEquals(CalibrationMath.MAX_ABS_USER_CORRECTION_EV, huge, 1e-9)
    }

    @Test
    fun `shutter parser accepts common notations`() {
        assertEquals(1.0 / 125.0, CalibrationMath.parseShutterSeconds("1/125")!!, 1e-9)
        assertEquals(0.5, CalibrationMath.parseShutterSeconds("0.5s")!!, 1e-9)
        assertEquals(2.0, CalibrationMath.parseShutterSeconds("2\"")!!, 1e-9)
        assertEquals(1.0 / 60.0, CalibrationMath.parseShutterSeconds("÷60")!!, 1e-9)
        assertNull(CalibrationMath.parseShutterSeconds(""))
        assertNull(CalibrationMath.parseShutterSeconds("abc"))
    }
}

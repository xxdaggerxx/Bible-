package com.biblestudy.app

import android.view.MotionEvent
import com.biblestudy.app.ui.StylusState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StylusStateTest {
    private fun stylusDown(buttons: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 0.5f })
        return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, 0, 0)
    }

    @Test
    fun sideButtonIsReadFromRawMotionEvents() {
        StylusState.record(stylusDown(MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertTrue(StylusState.sideButtonHeld)
        StylusState.record(stylusDown(MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertTrue(StylusState.sideButtonHeld)
        StylusState.record(stylusDown(0))
        assertFalse(StylusState.sideButtonHeld)
    }
}

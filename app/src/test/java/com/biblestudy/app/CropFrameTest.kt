package com.biblestudy.app

import androidx.compose.ui.geometry.Rect
import com.biblestudy.app.ui.turnFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class CropFrameTest {
    @Test
    fun aQuarterTurnMovesTheLeftHalfToTheTop() {
        assertEquals(Rect(0f, 0f, 1f, 0.5f), turnFrame(Rect(0f, 0f, 0.5f, 1f), 1))
    }

    @Test
    fun fourQuarterTurnsGoAllTheWayRound() {
        val f = Rect(0.1f, 0.2f, 0.6f, 0.9f)
        for (t in 0..3) {
            val back = turnFrame(turnFrame(f, t), (4 - t) % 4)
            assertEquals(f.left, back.left, 1e-5f); assertEquals(f.top, back.top, 1e-5f)
            assertEquals(f.right, back.right, 1e-5f); assertEquals(f.bottom, back.bottom, 1e-5f)
        }
    }
}

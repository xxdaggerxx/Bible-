package com.biblestudy.app

import com.biblestudy.app.ui.Shape
import com.biblestudy.app.ui.ShapeSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeSnapTest {
    private fun pts(vararg xy: Pair<Float, Float>) = xy.flatMap { listOf(it.first, it.second, 0.5f) }.toFloatArray()
    private fun path(n: Int, f: (Float) -> Pair<Float, Float>) = pts(*Array(n + 1) { f(it / n.toFloat()) })

    @Test
    fun aWobblyStrokeBecomesALine() {
        val r = ShapeSnap.recognize(path(30) { t -> 100f * t to 2f * sin(t * 20f) })!!
        assertEquals(Shape.LINE, r.first)
        assertEquals(6, r.second.size)
    }

    @Test
    fun aFlickBackAtTheTipMakesAnArrow() {
        val main = path(20) { t -> 200f * t to 0f }
        val flick = pts(185f to -12f, 175f to -20f)
        assertEquals(Shape.ARROW, ShapeSnap.recognize(main + flick)!!.first)
    }

    @Test
    fun closedLoopsBecomeBoxesOrOvals() {
        val square = path(40) { t ->
            val p = t * 4f
            when {
                p < 1f -> 100f * p to 0f
                p < 2f -> 100f to 100f * (p - 1f)
                p < 3f -> 100f * (3f - p) to 100f
                else -> 0f to 100f * (4f - p)
            }
        }
        assertEquals(Shape.BOX, ShapeSnap.recognize(square)!!.first)
        val circle = path(48) { t -> 50f + 50f * cos(t * 6.28f) to 50f + 30f * sin(t * 6.28f) }
        assertEquals(Shape.OVAL, ShapeSnap.recognize(circle)!!.first)
    }

    @Test
    fun aScribbleStaysAsDrawn() {
        assertNull(ShapeSnap.recognize(path(40) { t -> 100f * t to 40f * sin(t * 12f) }))
        assertNull(ShapeSnap.recognize(pts(0f to 0f, 3f to 2f, 4f to 1f, 5f to 3f)))
    }
}

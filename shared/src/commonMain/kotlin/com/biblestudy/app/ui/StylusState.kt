package com.biblestudy.app.ui

/**
 * Whether the pen's side button is held, as the platform reports it before Compose sees the touch.
 *
 * Compose folds the S Pen side button into its "primary" button, so it can't tell "pen touching"
 * from "pen touching with the side button held". On Android the raw MotionEvent can (recordMotion).
 */
object StylusState {
    @kotlin.concurrent.Volatile
    var sideButtonHeld: Boolean = false
}

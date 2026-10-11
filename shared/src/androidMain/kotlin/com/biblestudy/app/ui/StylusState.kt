package com.biblestudy.app.ui

import android.view.MotionEvent

/**
 * The raw state of the most recent touch event, recorded by MainActivity before Compose sees it.
 *
 * Compose folds the S Pen side button into its "primary" button, so it can't tell "pen touching"
 * from "pen touching with the side button held". The raw MotionEvent can.
 */
object StylusState {
    @Volatile
    var buttonState: Int = 0
        private set

    fun record(ev: MotionEvent) {
        buttonState = ev.buttonState
    }

    /** True while the stylus side button (S Pen button, or the lower button on two-button pens) is held. */
    val sideButtonHeld: Boolean
        get() = buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
}

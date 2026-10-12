package com.biblestudy.app.ui

import android.view.MotionEvent

/** Records the raw touch event's buttons (MainActivity calls this for every touch and hover). */
fun StylusState.record(ev: MotionEvent) {
    // True while the stylus side button (S Pen button, or the lower button on two-button pens) is held.
    sideButtonHeld = ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
}

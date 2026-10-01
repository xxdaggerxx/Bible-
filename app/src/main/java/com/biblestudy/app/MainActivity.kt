package com.biblestudy.app

import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.biblestudy.app.ui.StudyApp
import com.biblestudy.app.ui.StudyViewModel
import com.biblestudy.app.ui.StylusState

class MainActivity : ComponentActivity() {
    private val vm: StudyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { StudyApp(vm) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        StylusState.record(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) vm.userActive()
        // Deliver pen movement as soon as it arrives instead of once per frame, so ink keeps up with
        // the tip (INK-4).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        ) {
            window.decorView.requestUnbufferedDispatch(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** A hovering pen arrives here, not as a touch: record its side button for the hover cursor (INK-13). */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        StylusState.record(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Reading time is counted every 15 seconds while the app is on screen (ANL-1). */
    private val ticks = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            vm.readingTick()
            ticks.postDelayed(this, 15_000)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.foreground = true
        vm.userActive()
        vm.startReadingClock() // time spent away isn't counted
        ticks.postDelayed(tick, 15_000)
    }

    override fun onStop() {
        super.onStop()
        vm.foreground = false
        ticks.removeCallbacks(tick)
        vm.savePrefs()
        vm.autoBackupIfDue() // DATA-6: a backup each day or week, when the app goes to the background
    }
}

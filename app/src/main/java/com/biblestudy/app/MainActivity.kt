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
        // Deliver pen movement as soon as it arrives instead of once per frame, so ink keeps up with
        // the tip (INK-4).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        ) {
            window.decorView.requestUnbufferedDispatch(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        vm.savePrefs()
    }
}

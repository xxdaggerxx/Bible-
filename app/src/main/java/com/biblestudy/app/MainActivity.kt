package com.biblestudy.app

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
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        vm.savePrefs()
    }
}

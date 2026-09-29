package com.biblestudy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.biblestudy.app.ui.StudyApp
import com.biblestudy.app.ui.StudyViewModel

class MainActivity : ComponentActivity() {
    private val vm: StudyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { StudyApp(vm) }
    }

    override fun onStop() {
        super.onStop()
        vm.savePrefs()
    }
}

package com.biblestudy.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.biblestudy.app.AppConfig
import com.biblestudy.app.platform.DesktopHost
import com.biblestudy.app.platform.FilePrefs
import com.biblestudy.app.ui.StudyApp
import com.biblestudy.app.ui.StudyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Ink & Word on Windows, Mac and Linux: the same app as on Android, in a window. */
fun main() {
    AppConfig.versionName = BuildInfo.VERSION_NAME
    AppConfig.versionCode = BuildInfo.VERSION_CODE
    AppConfig.youVersionKey = BuildInfo.YOUVERSION_KEY
    AppConfig.esvKey = BuildInfo.ESV_KEY
    AppConfig.nltKey = BuildInfo.NLT_KEY
    AppConfig.googleDesktopId = BuildInfo.GOOGLE_DESKTOP_ID
    AppConfig.googleDesktopSecret = BuildInfo.GOOGLE_DESKTOP_SECRET

    application {
        val vm = remember { StudyViewModel(DesktopHost()) }
        val scope = rememberCoroutineScope()
        val state = rememberWindowState(size = DpSize(1280.dp, 860.dp))
        val closing = remember { booleanArrayOf(false) }
        Window(
            onCloseRequest = {
                if (!closing[0]) {
                    closing[0] = true
                    state.isMinimized = true
                    // As when Android sends the app to the background: save, back up if due, sync.
                    vm.foreground = false
                    vm.savePrefs()
                    scope.launch {
                        withTimeoutOrNull(60_000) { listOfNotNull(vm.autoBackupIfDue(), vm.syncNow()).joinAll() }
                        FilePrefs.flush()
                        exitApplication()
                    }
                }
            },
            state = state,
            title = "Ink & Word",
            icon = painterResource("icon.png"),
            onKeyEvent = vm::shortcut, // keys not used by what has focus (PLAT-4)
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(720, 520)
                // Counting reading time only while the window is in use, as the phone does when it's on screen.
                window.addWindowListener(object : WindowAdapter() {
                    override fun windowIconified(e: WindowEvent) {
                        vm.foreground = false; vm.savePrefs(); vm.autoBackupIfDue(); vm.syncNow()
                    }
                    override fun windowDeiconified(e: WindowEvent) {
                        vm.foreground = true; vm.userActive(); vm.startReadingClock(); vm.syncNow()
                    }
                })
                // Any click, key or scroll counts as reading, as a touch does on the phone.
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                    { vm.userActive() },
                    java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK,
                )
                vm.foreground = true
                vm.userActive()
                vm.startReadingClock()
                vm.syncNow()
                while (true) {
                    delay(15_000)
                    if (vm.foreground) vm.readingTick() // ANL-1
                }
            }
            StudyApp(vm)
        }
    }
}

package com.biblestudy.app

import androidx.compose.ui.window.ComposeUIViewController
import com.biblestudy.app.platform.IosHost
import com.biblestudy.app.ui.StudyApp
import com.biblestudy.app.ui.StudyViewModel
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

/**
 * Ink & Word on iPad and iPhone: the same app as on Android, called from the Xcode project's
 * Swift code (iosApp/). Going to the background saves, backs up when due and syncs, as on Android.
 */
fun MainViewController(): UIViewController {
    AppConfig.versionName = IosBuildInfo.VERSION_NAME
    AppConfig.versionCode = IosBuildInfo.VERSION_CODE
    AppConfig.youVersionKey = IosBuildInfo.YOUVERSION_KEY
    AppConfig.esvKey = IosBuildInfo.ESV_KEY
    AppConfig.nltKey = IosBuildInfo.NLT_KEY
    AppConfig.googleIosId = IosBuildInfo.GOOGLE_IOS_ID

    val vm = StudyViewModel(IosHost())
    val center = NSNotificationCenter.defaultCenter
    val main = NSOperationQueue.mainQueue
    fun start() {
        vm.foreground = true
        vm.userActive()
        vm.startReadingClock() // time spent away isn't counted
        vm.syncNow()
    }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, main) { _ -> start() }
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ ->
        vm.foreground = false
        vm.savePrefs()
        vm.autoBackupIfDue() // DATA-6
        vm.syncNow() // SYNC-1
    }
    // Reading time is counted every 15 seconds while the app is on screen (ANL-1).
    NSTimer.scheduledTimerWithTimeInterval(15.0, repeats = true) { _ -> if (vm.foreground) vm.readingTick() }
    start()
    return ComposeUIViewController { StudyApp(vm) }
}

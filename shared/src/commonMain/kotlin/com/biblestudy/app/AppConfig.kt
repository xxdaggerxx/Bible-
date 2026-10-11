package com.biblestudy.app

/**
 * What each platform's app shell tells the shared app at start-up: the version, and the online
 * Bible keys built in when the app was built (BIB-12). Set before anything else runs.
 */
object AppConfig {
    var versionName: String = ""
    var versionCode: Int = 0
    var youVersionKey: String = ""
    var esvKey: String = ""
    var nltKey: String = ""
    /** Google sign-in for sync on computers (an OAuth "Desktop app" client); blank turns it off. */
    var googleDesktopId: String = ""
    var googleDesktopSecret: String = ""
}

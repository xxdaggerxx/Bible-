package com.biblestudy.app

import android.app.Application

/** Hands the build's version and keys to the shared app before any screen opens. */
class InkAndWordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.versionName = BuildConfig.VERSION_NAME
        AppConfig.versionCode = BuildConfig.VERSION_CODE
        AppConfig.youVersionKey = BuildConfig.YOUVERSION_KEY
        AppConfig.esvKey = BuildConfig.ESV_KEY
        AppConfig.nltKey = BuildConfig.NLT_KEY
    }
}

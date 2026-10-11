package com.biblestudy.app.platform

actual fun isMainThread(): Boolean = android.os.Looper.getMainLooper().isCurrentThread

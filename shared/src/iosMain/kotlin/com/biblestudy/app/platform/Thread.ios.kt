package com.biblestudy.app.platform

actual fun isMainThread(): Boolean = platform.Foundation.NSThread.isMainThread

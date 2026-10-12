package com.biblestudy.app.platform

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun uptimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

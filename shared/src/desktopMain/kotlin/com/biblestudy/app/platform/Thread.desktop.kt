package com.biblestudy.app.platform

actual fun isMainThread(): Boolean = javax.swing.SwingUtilities.isEventDispatchThread()

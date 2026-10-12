package com.biblestudy.app.platform

import okio.FileSystem

actual val fs: FileSystem get() = FileSystem.SYSTEM

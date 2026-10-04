package com.biblestudy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Launches the real app on a JVM-emulated tablet (Galaxy Tab S9 size in landscape)
 * and saves screenshots to app/build/screenshots/. This is a smoke test that the app
 * starts, loads the bundled KJV and draws its main screens without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class AppScreenshotTest {
    @get:Rule(order = 0)
    val flusher = SnapshotFlusher()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Applies state changes made outside composition, as Compose's GlobalSnapshotManager does on a
     * device. After other test classes in the same run that manager stops doing it, and Compose
     * never reports idle (the same fix as in FeatureTest).
     */
    @get:Rule
    val flush = object : org.junit.rules.TestWatcher() {
        var handle: androidx.compose.runtime.snapshots.ObserverHandle? = null
        override fun starting(d: org.junit.runner.Description) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val posted = java.util.concurrent.atomic.AtomicBoolean(false)
            handle = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
                if (posted.compareAndSet(false, true)) main.post {
                    posted.set(false)
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
            }
        }
        override fun finished(d: org.junit.runner.Description) { handle?.dispose() }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun launchesAndShowsMainScreens() {
        snap("01-reader")
        compose.onNodeWithContentDescription("Search").performClick()
        snap("02-search")
    }
}

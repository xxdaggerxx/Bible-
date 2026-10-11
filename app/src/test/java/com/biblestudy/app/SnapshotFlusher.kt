package com.biblestudy.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.rules.ExternalResource
import java.util.concurrent.atomic.AtomicLong

/**
 * Applies Compose state changes made outside composition, as Compose's GlobalSnapshotManager does
 * on a device: after such a change, a call to apply them is posted to the main thread. In a long
 * Robolectric run that manager can stop doing it, so a change (a chapter finishing loading, a
 * window's data arriving) isn't seen and the test waits out its timeout, or Compose never reports
 * idle. Applying them from another thread instead would race with the main thread's own changes.
 * A posted call that hasn't run within 50 ms is posted again, in case the first was dropped.
 */
class SnapshotFlusher : ExternalResource() {
    private var handle: ObserverHandle? = null

    override fun before() {
        val main = Handler(Looper.getMainLooper())
        val postedAt = AtomicLong(0L)
        handle = Snapshot.registerGlobalWriteObserver {
            val now = System.nanoTime()
            val at = postedAt.get()
            if ((at == 0L || now - at > 50_000_000L) && postedAt.compareAndSet(at, now)) main.post {
                postedAt.set(0L)
                Snapshot.sendApplyNotifications()
            }
        }
    }

    override fun after() {
        handle?.dispose()
    }
}

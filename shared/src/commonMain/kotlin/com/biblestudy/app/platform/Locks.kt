package com.biblestudy.app.platform

/** A lock for [locked], on every platform (Java's monitor on Android and desktops). Reentrant. */
typealias Lock = kotlinx.atomicfu.locks.SynchronizedObject

/** Runs [block] holding [lock], like Java's synchronized. */
inline fun <T> locked(lock: Lock, block: () -> T): T = kotlinx.atomicfu.locks.synchronized(lock, block)

/** Whether this is the app's main (UI) thread. */
expect fun isMainThread(): Boolean

/** A set safe to use from several threads at once (like Collections.synchronizedSet). */
class SyncSet<T> : MutableSet<T> {
    private val lock = Lock()
    private val set = HashSet<T>()
    override val size: Int get() = locked(lock) { set.size }
    override fun isEmpty() = locked(lock) { set.isEmpty() }
    override fun contains(element: T) = locked(lock) { element in set }
    override fun containsAll(elements: Collection<T>) = locked(lock) { set.containsAll(elements) }
    override fun add(element: T) = locked(lock) { set.add(element) }
    override fun addAll(elements: Collection<T>) = locked(lock) { set.addAll(elements) }
    override fun remove(element: T) = locked(lock) { set.remove(element) }
    override fun removeAll(elements: Collection<T>) = locked(lock) { set.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<T>) = locked(lock) { set.retainAll(elements.toSet()) }
    override fun clear() = locked(lock) { set.clear() }
    /** Iterates over a copy, so other threads can change the set meanwhile. */
    override fun iterator(): MutableIterator<T> = locked(lock) { set.toMutableList() }.iterator()
}

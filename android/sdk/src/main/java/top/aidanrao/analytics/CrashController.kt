package top.aidanrao.analytics

import android.app.Application
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class CrashController(application: Application, endpoint: String, appId: String,
                               private val engine: Engine, private val tracker: AutoTracker) : AutoTrackingListener {
    private val active = AtomicBoolean(true)
    private val pending = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "event-analytics-crash-replay").apply { isDaemon = true } }
    private val namespace = MessageDigest.getInstance("SHA-256").digest("$endpoint\n$appId".toByteArray()).joinToString("") { "%02x".format(it) }
    private val store = CrashStore(File(application.noBackupFilesDir, "event-analytics/crashes/$namespace"))
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val handler = CrashHandler({ thread, error ->
        if (active.get() && tracker.enabled) {
            val snapshot = engine.captureContext()
            CrashRecord.capture(snapshot.first, snapshot.second, tracker.options.eventPrefix + "crash", tracker.currentPage, thread, error)?.let(store::save)
        }
    }, previous)
    init { Thread.setDefaultUncaughtExceptionHandler(handler); replay() }
    override fun onForeground() = replay()
    private fun replay() {
        if (!active.get() || !pending.compareAndSet(false, true)) return
        try {
            io.execute {
                try {
                    store.read().forEach { record ->
                        if (active.get()) try {
                            engine.replay(record).thenAccept { accepted -> if (accepted) runCatching { store.acknowledge(record) } }
                        } catch (_: Exception) { /* leave persisted for next foreground/restart */ }
                    }
                    if (active.get()) engine.flush()
                } catch (_: Exception) { /* storage/closed engine must never break the host */ }
                finally { pending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { pending.set(false) }
    }
    override fun onStopped() = close()
    fun deactivate() { active.set(false) }
    fun close() {
        deactivate()
        // Do not clobber a handler subsequently installed by another SDK. In that chain our inactive
        // wrapper still delegates to the original handler without recording anything.
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(previous)
        io.shutdown()
    }
}

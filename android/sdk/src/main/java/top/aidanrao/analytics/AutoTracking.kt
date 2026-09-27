package top.aidanrao.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Stable business page name; do not include route arguments or user identifiers. */
data class PageInfo @JvmOverloads constructor(val id: String, val name: String? = null)
fun interface ActivityPageMapper { fun map(activity: Activity): PageInfo? }
interface AutoTrackingListener {
    fun onForeground() {}
    fun onBackground() {}
    fun onStopped() {}
}
/** Collection is opt-in via Builder.autoTracking(). Null from the mapper excludes an Activity. */
data class AutoTrackingOptions @JvmOverloads constructor(
    val lifecycleEvents: Boolean = true,
    val pageViews: Boolean = true,
    val crashes: Boolean = true,
    val eventPrefix: String = "",
    val activityPageMapper: ActivityPageMapper = ActivityPageMapper { PageInfo(it.javaClass.name) }
) {
    init { require(eventPrefix.length <= 200 && eventPrefix.none { it.isISOControl() }) }
}

internal class AutoTracker(private val application: Application, val options: AutoTrackingOptions,
                           private val emit: (String, JsonObject) -> Unit, private val flush: () -> Unit) :
    Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    companion object {
        private val owner = AtomicReference<AutoTracker?>()
        private val processStarted = AtomicBoolean(false)
        private val runId = UUID.randomUUID().toString()
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile var enabled = true; private set
    @Volatile var inForeground = false; private set
    @Volatile var currentPage: String? = null; private set
    private var installed = false
    private var configuration = false
    private var lastActivity = WeakReference<Activity>(null)
    private var visibleActivity = WeakReference<Activity>(null)
    private val suppressed = WeakHashMap<Activity, Int>()
    private val listeners = linkedSetOf<AutoTrackingListener>()
    private var foregroundAt = 0L
    fun start(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!enabled || !owner.compareAndSet(null, this)) { enabled = false; return false }
        installed = true
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        return true
    }
    fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
    private fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { } }
    private fun event(name: String, properties: JsonObject = emptyMap()) = safely {
        emit(options.eventPrefix + name, properties + ("process_session_id" to runId))
    }
    override fun onStart(owner: LifecycleOwner) = foreground()
    override fun onStop(owner: LifecycleOwner) = background()
    fun foreground() {
        if (!enabled || inForeground) return
        inForeground = true
        foregroundAt = android.os.SystemClock.elapsedRealtime()
        if (options.lifecycleEvents && processStarted.compareAndSet(false, true)) event("app_start")
        if (options.lifecycleEvents) event("foreground")
        listeners.toList().forEach { safely { it.onForeground() } }
        main.post { visibleActivity.get()?.let { showActivity(it, "foreground") } }
    }
    fun background() {
        if (!enabled || !inForeground) return
        inForeground = false
        lastActivity.clear()
        if (options.lifecycleEvents) event("background", mapOf("duration_ms" to (android.os.SystemClock.elapsedRealtime() - foregroundAt).coerceAtLeast(0)))
        listeners.toList().forEach { safely { it.onBackground() } }
        safely(flush)
    }
    fun configurationChanged() { configuration = true }
    fun page(id: String, name: String?, reason: String) {
        if (!enabled || !options.pageViews || !inForeground) return
        if (id.isBlank() || id.length > 256 || (name?.length ?: 0) > 256) return
        if (configuration && reason == "initial" && currentPage == id) { configuration = false; return }
        configuration = false
        val previous = currentPage
        currentPage = id
        event("page_view", mapOf("page_id" to id, "page_view_id" to UUID.randomUUID().toString(), "reason" to reason.take(64)) +
            (name?.let { mapOf("page_name" to it) } ?: emptyMap()) +
            (previous?.let { mapOf("previous_page_id" to it) } ?: emptyMap()))
    }
    fun addListener(listener: AutoTrackingListener): Closeable {
        val disposed = AtomicBoolean(false)
        dispatch { if (enabled && !disposed.get()) listeners.add(listener) }
        return Closeable { disposed.set(true); dispatch { listeners.remove(listener) } }
    }
    fun suppress(activity: Activity): Closeable {
        val disposed = AtomicBoolean(false)
        val reference = WeakReference(activity)
        dispatch { reference.get()?.let { if (!disposed.get()) suppressed[it] = (suppressed[it] ?: 0) + 1 } }
        return Closeable {
            if (disposed.compareAndSet(false, true)) dispatch {
                reference.get()?.let { val count = (suppressed[it] ?: 1) - 1; if (count <= 0) suppressed.remove(it) else suppressed[it] = count }
            }
        }
    }
    private fun showActivity(activity: Activity, reason: String) {
        if (!enabled || !inForeground || suppressed.containsKey(activity) || activity.isFinishing || lastActivity.get() === activity) return
        safely {
            val info = options.activityPageMapper.map(activity) ?: return@safely
            lastActivity = WeakReference(activity)
            page(info.id, info.name, if (configuration) "initial" else reason)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        visibleActivity = WeakReference(activity)
        main.post { if (visibleActivity.get() === activity) showActivity(activity, "initial") }
    }
    override fun onActivityPaused(activity: Activity) { if (visibleActivity.get() === activity) visibleActivity.clear() }
    override fun onActivityStopped(activity: Activity) { if (activity.isChangingConfigurations) configurationChanged() }
    override fun onActivityDestroyed(activity: Activity) { suppressed.remove(activity); if (activity.isChangingConfigurations) configurationChanged() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    fun stop() {
        enabled = false
        dispatch {
            if (installed) {
                application.unregisterActivityLifecycleCallbacks(this)
                ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
                owner.compareAndSet(this, null); installed = false
            }
            listeners.toList().forEach { safely { it.onStopped() } }; listeners.clear()
            suppressed.clear(); visibleActivity.clear(); lastActivity.clear()
        }
    }
}

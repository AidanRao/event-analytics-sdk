package top.aidanrao.analytics

import android.content.Context
import android.app.Application
import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture

/** Counts events pending when flush/close was invoked; concurrent flushes may count the same events. */
data class FlushResult(val accepted: Int, val failed: Int)
data class SdkError(val reason: String, val eventIds: List<String>, val status: Int? = null, val code: String? = null)
fun interface ErrorListener { fun onError(error: SdkError) }
fun interface FlushCallback { fun onComplete(result: FlushResult) }

/** Browser-compatible ingestion semantics. All HTTP work runs off the caller's thread. */
class Analytics private constructor(private val engine: Engine, private val application: Application,
                                    private val endpoint: String, private val appId: String, private val errorListener: ErrorListener?) {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var autoTracker: AutoTracker? = null
    @Volatile private var crashController: CrashController? = null
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() } }
    private fun report(reason: String) { runCatching { errorListener?.onError(SdkError(reason, emptyList())) } }
    /** Enable once on the main process. Duplicate calls on this instance are no-ops. */
    @JvmOverloads fun startAutoTracking(options: AutoTrackingOptions = AutoTrackingOptions()) {
        onMain {
            if (closed.get() || autoTracker?.enabled == true) return@onMain
            if (!runCatching { isMainProcess() }.getOrDefault(false)) { report("Automatic tracking requires the main process"); return@onMain }
            val tracker = AutoTracker(application, options, { name, properties -> engine.track(name, properties) }, { engine.flush() })
            autoTracker = tracker
            try {
                if (!tracker.start()) { autoTracker = null; report("Automatic tracking already owned by another Analytics instance"); return@onMain }
                if (options.crashes) {
                    crashController = CrashController(application, endpoint, appId, engine, tracker)
                    tracker.addListener(crashController!!)
                }
            } catch (_: Exception) { tracker.stop(); crashController?.close(); crashController = null; autoTracker = null; report("Automatic tracking unavailable") }
        }
    }
    fun stopAutoTracking() {
        val tracker = autoTracker
        val crashes = crashController
        tracker?.stop()
        crashes?.deactivate()
        onMain {
            crashes?.close()
            if (crashController === crashes) crashController = null
            if (autoTracker === tracker) autoTracker = null
        }
    }
    fun isAutoTrackingEnabled(): Boolean = autoTracker?.enabled == true && !closed.get()
    fun isInForeground(): Boolean = autoTracker?.inForeground == true && isAutoTrackingEnabled()
    /** Nonthrowing page event entry point for UI adapters; enabled only while collecting in foreground. */
    @JvmOverloads fun pageView(pageId: String, pageName: String? = null, reason: String = "navigation") {
        onMain { autoTracker?.page(pageId, pageName, reason) }
    }
    fun suppressActivityPages(activity: Activity): Closeable = autoTracker?.suppress(activity) ?: Closeable { }
    fun addAutoTrackingListener(listener: AutoTrackingListener): Closeable = autoTracker?.addListener(listener) ?: Closeable { }
    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
        val actual = name ?: (application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        return actual == (application.applicationInfo.processName ?: application.packageName)
    }
    @JvmOverloads fun track(name: String, properties: Map<String, Any?> = emptyMap()): String = engine.track(name, properties)
    fun setIdentity(identity: Map<String, Any?>) = engine.setIdentity(identity)
    /** Merge a context patch. app_id is immutable. */
    fun setContext(context: Map<String, Any?>) = engine.setContext(context)
    fun flush(): CompletableFuture<FlushResult> = engine.flush()
    fun flush(callback: FlushCallback) { flush().thenAccept { callback.onComplete(it) } }
    fun close(): CompletableFuture<FlushResult> { closed.set(true); stopAutoTracking(); return engine.close() }
    fun close(callback: FlushCallback) { close().thenAccept { callback.onComplete(it) } }

    class Builder(context: Context, private val endpoint: String, private val appId: String) {
        private val application = context.applicationContext as Application
        private var automatic: AutoTrackingOptions? = null
        @JvmOverloads fun autoTracking(options: AutoTrackingOptions = AutoTrackingOptions()) = apply { automatic = options }
        private var appVersion: String? = null
        private var context: Map<String, Any?> = emptyMap()
        private var identity: Map<String, Any?> = emptyMap()
        private var config = Config(endpoint)
        fun appVersion(value: String) = apply { appVersion = value }
        fun context(value: Map<String, Any?>) = apply { context = Protocol.obj(value) }
        fun identity(value: Map<String, Any?>) = apply { identity = Protocol.obj(value) }
        fun batchSize(value: Int) = apply { config = config.copy(batchSize = value) }
        fun flushIntervalMs(value: Long) = apply { config = config.copy(flushIntervalMs = value) }
        fun maxQueueSize(value: Int) = apply { config = config.copy(maxQueueSize = value) }
        fun timeoutMs(value: Long) = apply { config = config.copy(timeoutMs = value) }
        fun maxRetries(value: Int) = apply { config = config.copy(maxRetries = value) }
        fun retryBaseMs(value: Long) = apply { config = config.copy(retryBaseMs = value) }
        fun closeTimeoutMs(value: Long) = apply { config = config.copy(closeTimeoutMs = value) }
        fun onError(value: ErrorListener) = apply { config = config.copy(onError = value) }
        @Suppress("DEPRECATION")
        fun build(): Analytics {
            require(!context.containsKey("app_id") || context["app_id"] == appId) { "app_id is fixed" }
            val version = appVersion ?: application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"
            val base = SystemInfo.resolve(Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
                Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.MODEL.orEmpty(), emptyMap())
            val supplied = context + mapOf("app_id" to appId, "app_version" to version)
            val hostOsOverride = context.containsKey("os_name")
            fun defaults(values: JsonObject): JsonObject = if (hostOsOverride)
                (values - "os_version") + mapOf("os_detection_source" to "host_override") else values
            val engine = Engine(config, defaults(base) + supplied, identity,
                contextInitializer = { defaults(SystemInfo.collect()) }, initialOverrideKeys = supplied.keys)
            return Analytics(engine, application, endpoint, appId, config.onError).also { sdk -> automatic?.let(sdk::startAutoTracking) }
        }
    }
}

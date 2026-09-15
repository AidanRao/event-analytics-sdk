package top.aidanrao.analytics

import android.content.Context
import java.util.concurrent.CompletableFuture

/** Counts events pending when flush/close was invoked; concurrent flushes may count the same events. */
data class FlushResult(val accepted: Int, val failed: Int)
data class SdkError(val reason: String, val eventIds: List<String>, val status: Int? = null, val code: String? = null)
fun interface ErrorListener { fun onError(error: SdkError) }
fun interface FlushCallback { fun onComplete(result: FlushResult) }

/** Browser-compatible ingestion semantics. All HTTP work runs off the caller's thread. */
class Analytics private constructor(private val engine: Engine) {
    @JvmOverloads fun track(name: String, properties: Map<String, Any?> = emptyMap()): String = engine.track(name, properties)
    fun setIdentity(identity: Map<String, Any?>) = engine.setIdentity(identity)
    /** Merge a context patch. app_id is immutable. */
    fun setContext(context: Map<String, Any?>) = engine.setContext(context)
    fun flush(): CompletableFuture<FlushResult> = engine.flush()
    fun flush(callback: FlushCallback) { flush().thenAccept { callback.onComplete(it) } }
    fun close(): CompletableFuture<FlushResult> = engine.close()
    fun close(callback: FlushCallback) { close().thenAccept { callback.onComplete(it) } }

    class Builder(context: Context, private val endpoint: String, private val appId: String) {
        private val application = context.applicationContext
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
            return Analytics(Engine(config, mapOf("platform" to "android", "os_name" to "Android") + context +
                mapOf("app_id" to appId, "app_version" to version), identity))
        }
    }
}

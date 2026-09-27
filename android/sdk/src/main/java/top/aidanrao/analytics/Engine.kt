package top.aidanrao.analytics

import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal data class Config(
    val endpoint: String, val batchSize: Int = 20, val flushIntervalMs: Long = 10_000,
    val maxQueueSize: Int = 1000, val timeoutMs: Long = 10_000, val maxRetries: Int = 3,
    val retryBaseMs: Long = 1000, val closeTimeoutMs: Long = 15_000, val onError: ErrorListener? = null
) {
    fun validate() {
        val url = endpoint.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "URL must not contain credentials or fragment" }
        require(batchSize in 1..500 && flushIntervalMs in 1..2147483647L && maxQueueSize in 1..100_000)
        require(timeoutMs in 1..300_000 && maxRetries in 0..10 && retryBaseMs in 1..60_000 && closeTimeoutMs in 1..300_000)
    }
}
internal class Engine(private val config: Config, initialContext: JsonObject, initialIdentity: JsonObject = emptyMap(),
    private val contextInitializer: (() -> JsonObject)? = null, initialOverrideKeys: Set<String> = initialContext.keys) {
    private class Entry(val event: JsonObject, var context: JsonObject, val identity: JsonObject, val overrides: Set<String>? = null) {
        val done = CompletableFuture<Boolean>()
        val key get() = Protocol.json(listOf(context, identity))
    }
    private val lock = Any()
    private var context = Protocol.context(initialContext)
    private val overrideKeys = initialOverrideKeys.toMutableSet()
    @Volatile private var capture = Pair(context, Protocol.obj(initialIdentity))
    fun captureContext(): Pair<JsonObject, JsonObject> = capture
    private var identity = Protocol.obj(initialIdentity)
    private val queue = mutableListOf<Entry>()
    private var running = false
    private var closing = false
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    private var closeFuture: CompletableFuture<FlushResult>? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "event-analytics-delivery").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "event-analytics-timer").apply { isDaemon = true } }
    private val client: OkHttpClient
    private val timer: java.util.concurrent.ScheduledFuture<*>
    init {
        config.validate()
        client = OkHttpClient.Builder().callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        worker.execute {
            try {
                contextInitializer?.invoke()?.let { defaults -> synchronized(lock) {
                    fun enriched(value: JsonObject, keys: Set<String>) = Protocol.context(value + defaults.filterKeys { it !in keys })
                    context = enriched(context, overrideKeys)
                    queue.forEach { entry -> entry.overrides?.let { entry.context = enriched(entry.context, it) } }
                    capture = Pair(context, identity)
                } }
            } catch (_: Exception) { report(SdkError("System information unavailable", emptyList())) }
        }
        timer = scheduler.scheduleWithFixedDelay({ start() }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
    }
    fun setIdentity(value: JsonObject) = synchronized(lock) { checkOpen(); identity = Protocol.obj(value); capture = Pair(context, identity) }
    fun setContext(value: JsonObject) = synchronized(lock) {
        checkOpen(); val update = Protocol.obj(value)
        require(!update.containsKey("app_id") || update["app_id"] == context["app_id"]) { "app_id is fixed" }
        val overridingOs = update.containsKey("os_name")
        val base = if (overridingOs && !update.containsKey("os_version")) context - "os_version" else context
        context = Protocol.context(base + update + if (overridingOs) mapOf("os_detection_source" to "host_override") else emptyMap())
        overrideKeys.addAll(update.keys)
        if (overridingOs) overrideKeys.addAll(setOf("os_version", "os_detection_source"))
        capture = Pair(context, identity)
    }
    fun track(name: String, properties: JsonObject): String {
        val id = UUID.randomUUID().toString()
        try {
            synchronized(lock) {
                checkOpen(); require(queue.size < config.maxQueueSize) { "Queue is full" }
                val event = mapOf("event_id" to id, "event_name" to name, "local_time_ms" to System.currentTimeMillis(), "properties" to Protocol.obj(properties))
                val entry = Entry(event, Protocol.context(context), Protocol.obj(identity), overrideKeys.toSet())
                Protocol.validate(request(listOf(entry), System.currentTimeMillis() / 1000))
                require(Protocol.fitsEvent(entry.context, entry.identity, event)) { "Normalized event exceeds 16000 bytes" }
                queue.add(entry)
                if (queue.size >= config.batchSize) start()
            }
            return id
        } catch (e: IllegalArgumentException) { report(SdkError(e.message ?: "Invalid event", listOf(id))); throw e }
    }
    fun replay(record: CrashRecord): CompletableFuture<Boolean> = synchronized(lock) {
        checkOpen()
        require(record.context["app_id"] == context["app_id"])
        queue.firstOrNull { it.event["event_id"] == record.event["event_id"] }?.let { return@synchronized it.done }
        require(queue.size < config.maxQueueSize) { "Queue is full" }
        val entry = Entry(Protocol.obj(record.event), Protocol.context(record.context), Protocol.obj(record.identity))
        Protocol.validate(request(listOf(entry), System.currentTimeMillis() / 1000))
        require(Protocol.fitsEvent(entry.context, entry.identity, entry.event))
        queue.add(entry)
        if (queue.size >= config.batchSize) start()
        entry.done
    }
    fun flush(): CompletableFuture<FlushResult> {
        val pending = synchronized(lock) { queue.map { it.done } }
        start()
        return CompletableFuture.allOf(*pending.toTypedArray()).thenApply {
            val accepted = pending.count { it.join() }; FlushResult(accepted, pending.size - accepted)
        }
    }
    fun close(): CompletableFuture<FlushResult> = synchronized(lock) {
        closeFuture?.let { return@synchronized it }
        closing = true; timer.cancel(false)
        val result = CompletableFuture<FlushResult>()
        closeFuture = result
        val deadline = scheduler.schedule({
            stopped = true; call?.cancel(); worker.shutdownNow()
            val pending = synchronized(lock) { queue.toList() }
            finish(pending, false, SdkError("Close timed out", pending.map { it.event["event_id"] as String }))
        }, config.closeTimeoutMs, TimeUnit.MILLISECONDS)
        flush().whenComplete { summary, _ ->
            stopped = true; deadline.cancel(false); scheduler.shutdownNow(); worker.shutdown()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            result.complete(summary)
        }
        result
    }
    private fun checkOpen() { check(!closing && !stopped) { "Analytics is closed" } }
    private fun report(error: SdkError) { try { config.onError?.onError(error) } catch (_: Exception) { /* isolate callbacks */ } }
    private fun start(): Unit = synchronized(lock) {
        if (!running && !stopped && queue.isNotEmpty()) {
            running = true
            worker.execute {
                try { drain() } finally { synchronized(lock) { running = false; start() } }
            }
        }
    }
    private fun request(batch: List<Entry>, time: Long): JsonObject = mapOf("context" to batch.first().context,
        "identity" to batch.first().identity, "local_time" to time, "events" to batch.map { it.event })
    private fun finish(batch: List<Entry>, accepted: Boolean, error: SdkError? = null) {
        val active = synchronized(lock) { batch.filter { queue.contains(it) }.also { queue.removeAll(it.toSet()) } }
        active.forEach { it.done.complete(accepted) }
        if (active.isNotEmpty() && error != null) report(error)
    }
    private fun drain() {
        while (!stopped) {
            val batch = synchronized(lock) {
                val first = queue.firstOrNull() ?: return
                val selected = mutableListOf<Entry>()
                for (entry in queue) {
                    if (entry.key != first.key || selected.size >= config.batchSize) break
                    if (Protocol.bytes(request(selected + entry, Protocol.MAX_TIME)) > 1_000_000) break
                    selected.add(entry)
                }
                selected
            }
            if (batch.any { !Protocol.fitsEvent(it.context, it.identity, it.event) }) {
                val oversized = batch.filter { !Protocol.fitsEvent(it.context, it.identity, it.event) }
                finish(oversized, false, SdkError("Normalized event exceeds 16000 bytes after context enrichment", oversized.map { it.event["event_id"] as String }))
                continue
            }
            if (batch.isEmpty()) return // A single validated normalized event always fits the HTTP budget.
            val ids = batch.map { it.event["event_id"] as String }
            var accepted = false
            var failure = SdkError("Delivery failed", ids)
            for (attempt in 0..config.maxRetries) {
                if (stopped) break
                var retry = true
                try {
                    val request = Request.Builder().url(config.endpoint).post(Protocol.json(request(batch, System.currentTimeMillis() / 1000))
                        .toRequestBody("application/json".toMediaType())).build()
                    val activeCall = client.newCall(request); call = activeCall
                    if (stopped) { activeCall.cancel(); break }
                    activeCall.execute().use { response ->
                        if (response.code == 202) accepted = true
                        else {
                            val code = try {
                                val node = JsonParser.parseString(response.peekBody(65_536).string()).asJsonObject.getAsJsonObject("error")?.get("code")
                                if (node?.isJsonPrimitive == true && node.asJsonPrimitive.isString) node.asString else null
                            } catch (_: Exception) { null }
                            failure = SdkError("HTTP request rejected", ids, response.code, code)
                            retry = response.code == 429 || response.code >= 500
                        }
                    }
                } catch (_: Exception) { failure = SdkError("Network request failed or timed out", ids) }
                finally { call = null }
                if (accepted || !retry || attempt == config.maxRetries || stopped) break
                try { Thread.sleep((minOf(60_000L, config.retryBaseMs * (1L shl attempt)) * (0.5 + Random.nextDouble() * 0.5)).toLong()) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            finish(batch, accepted, if (accepted) null else failure)
        }
    }
}

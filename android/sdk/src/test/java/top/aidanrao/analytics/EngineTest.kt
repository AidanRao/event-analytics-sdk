package top.aidanrao.analytics

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class EngineTest {
    private val server = MockWebServer().apply { start() }
    private val clients = mutableListOf<Engine>()
    private val context = mapOf("app_id" to "demo", "app_version" to "1", "platform" to "android", "os_name" to "Android")
    private fun client(config: Config = Config(server.url("/v1/events").newBuilder().host("127.0.0.1").build().toString(), retryBaseMs = 1)) = Engine(config, context).also { clients.add(it) }
    private fun flush(engine: Engine) = engine.flush().get(5, TimeUnit.SECONDS)
    private fun fixtures(name: String): List<Map<String, Any?>> = Gson().fromJson(File(System.getProperty("fixtures"), name).readText(), object : TypeToken<List<Map<String, Any?>>>() {}.type)
    private fun body(): Map<String, Any?> = Gson().fromJson(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8(), object : TypeToken<Map<String, Any?>>() {}.type)
    @After fun cleanup() { clients.forEach { it.close().get(20, TimeUnit.SECONDS) }; server.shutdown() }
    @Test fun sharedProtocolFixtures() {
        fixtures("requests.json").forEach { f ->
            val valid = try { Protocol.validate(f["request"]); true } catch (_: IllegalArgumentException) { false }
            assertEquals(f["name"].toString(), f["valid"], valid)
        }
        fixtures("sdk-cases.json").forEach { f ->
            val event = mapOf("event_id" to "e1", "event_name" to "click", "local_time_ms" to 1720000000123L, "properties" to f["properties"])
            assertEquals(f["name"].toString(), f["valid"], Protocol.fitsEvent(context, emptyMap(), event))
        }
    }
    @Test fun freezesSnapshotsAndAcceptsEmpty202() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202)) }
        val sdk = client(); val properties = mutableMapOf<String, Any?>("value" to 1)
        sdk.setIdentity(mapOf("user" to "A")); sdk.track("first", properties)
        properties["value"] = 2; sdk.setIdentity(mapOf("user" to "B")); sdk.setContext(mapOf("app_version" to "2")); sdk.track("second", emptyMap())
        assertEquals(FlushResult(2, 0), flush(sdk))
        val first = body(); val second = body()
        assertEquals("A", (first["identity"] as Map<*, *>)["user"])
        assertEquals("1", (first["context"] as Map<*, *>)["app_version"])
        assertEquals(1.0, (((first["events"] as List<*>)[0] as Map<*, *>)["properties"] as Map<*, *>)["value"])
        assertEquals("B", (second["identity"] as Map<*, *>)["user"])
        assertThrows(IllegalArgumentException::class.java) { sdk.setContext(mapOf("app_id" to "changed")) }
    }
    @Test fun partialAcceptanceRetriesSameEvents() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"B02-001","details":{"confirmed_events":1,"possibly_partial":true}}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        val sdk = client(); sdk.track("one", emptyMap()); sdk.track("two", emptyMap())
        assertEquals(FlushResult(2, 0), flush(sdk)); assertEquals(body()["events"], body()["events"])
    }
    @Test fun permanentFailureReportsCodeWithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"A01-002"}}"""))
        val errors = java.util.concurrent.CopyOnWriteArrayList<SdkError>()
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), onError = ErrorListener { errors.add(it) }))
        val id = sdk.track("one", emptyMap()); assertEquals(FlushResult(0, 1), flush(sdk))
        // Error callbacks finish on the delivery thread after futures complete.
        sdk.close().get(2, TimeUnit.SECONDS)
        assertEquals(1, server.requestCount)
        for (i in 0..100) { if (errors.isNotEmpty()) break; Thread.sleep(1) }
        assertEquals(listOf(id), errors.single().eventIds); assertEquals("A01-002", errors.single().code)
    }
    @Test fun retriesAreBounded() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setBody("gateway")) }
        val sdk = client(); sdk.track("one", emptyMap()); assertEquals(FlushResult(0, 1), flush(sdk)); assertEquals(4, server.requestCount)
    }
    @Test fun includesInflightInCapacityAndSharesFlush() {
        server.enqueue(MockResponse().setResponseCode(202).setHeadersDelay(100, TimeUnit.MILLISECONDS))
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), maxQueueSize = 1))
        sdk.track("one", emptyMap()); val a = sdk.flush(); val b = sdk.flush()
        assertThrows(IllegalArgumentException::class.java) { sdk.track("two", emptyMap()) }
        assertEquals(FlushResult(1, 0), a.get(3, TimeUnit.SECONDS)); assertEquals(a.get(), b.get())
    }
    @Test fun rejectsNonJsonAndOversize() {
        val sdk = client(); val cycle = mutableMapOf<String, Any?>(); cycle["self"] = cycle
        listOf(mapOf("value" to Double.NaN), mapOf("value" to Any()), mapOf("value" to "😀".repeat(4000)), cycle).forEach {
            assertThrows(IllegalArgumentException::class.java) { sdk.track("bad", it) }
        }
    }
    @Test fun splitsHttpByteBudget() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202)) }
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), batchSize = 500))
        repeat(100) { sdk.track("large", mapOf("text" to "x".repeat(14000))) }
        assertEquals(FlushResult(100, 0), flush(sdk)); assertEquals(2, server.requestCount)
        repeat(2) { assertTrue(server.takeRequest().bodySize <= 1_000_000) }
    }
    @Test fun autoFlushesThresholdAndTimer() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202)) }
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), batchSize = 2, flushIntervalMs = 100))
        sdk.track("one", emptyMap()); sdk.track("two", emptyMap())
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); flush(sdk)
        sdk.track("three", emptyMap()); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); flush(sdk)
    }
    @Test fun closeDeadlineCancelsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), closeTimeoutMs = 100))
        sdk.track("one", emptyMap()); val close = sdk.close()
        assertSame(close, sdk.close()); assertThrows(IllegalStateException::class.java) { sdk.track("two", emptyMap()) }
        assertEquals(FlushResult(0, 1), close.get(2, TimeUnit.SECONDS))
    }
    @Test fun preservesIsolatedSurrogatesAndRejectsUtf16Overflow() {
        val value = mapOf("text" to "\uD800")
        assertEquals("{\"text\":\"\\ud800\"}", Protocol.json(value))
        assertEquals(17, Protocol.bytes(value))
        val sdk = client()
        assertThrows(IllegalArgumentException::class.java) { sdk.track("😀".repeat(129), emptyMap()) }
    }
    @Test fun callbackExceptionsDoNotStopDelivery() {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(202))
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(),
            onError = ErrorListener { throw RuntimeException("Application callback") }))
        sdk.track("one", emptyMap()); sdk.setIdentity(mapOf("user" to "next")); sdk.track("two", emptyMap())
        assertEquals(FlushResult(1, 1), flush(sdk))
    }
    @Test fun timeoutIsRetried() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(202).setHeadersDelay(2, TimeUnit.SECONDS)) }
        val sdk = client(Config(server.url("/").newBuilder().host("127.0.0.1").build().toString(), timeoutMs = 500, maxRetries = 1, retryBaseMs = 1))
        sdk.track("one", emptyMap()); assertEquals(FlushResult(0, 1), flush(sdk)); assertEquals(2, server.requestCount)
    }
}

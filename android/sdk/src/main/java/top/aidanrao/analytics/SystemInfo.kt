package top.aidanrao.analytics

import android.os.Build
import java.util.concurrent.TimeUnit

/** Best-effort ROM identification. Vendor properties are conventions, not Android API contracts.
 * Unknown or conflicting evidence deliberately falls back to Android. Never infer a ROM from brand
 * alone: regional firmware and custom ROMs can run on the same hardware.
 */
internal object SystemInfo {
    private val keys = listOf(
        "ro.mi.os.version.name", "ro.miui.ui.version.name",
        "ro.build.version.realmeui", "ro.oxygen.version", "ro.build.version.opporom",
        "ro.vivo.os.name", "ro.vivo.os.version", "ro.build.version.oneui",
        "ro.build.version.emui", "ro.build.version.magic", "ro.build.display.id"
    )

    // Once per process; caller should initialize on a background thread. No hidden API reflection,
    // root access, arbitrary property dump, or shell interpretation is used.
    private val cached by lazy {
        resolve(Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
            Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.MODEL.orEmpty(), probe())
    }
    fun collect(): Map<String, Any?> = cached.toMap()

    internal fun resolve(androidVersion: String, apiLevel: Int, manufacturer: String, brand: String,
                         model: String, properties: Map<String, String>): Map<String, Any?> {
        fun prop(key: String): String? = properties[key]?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 256 && it.none { char -> char.isISOControl() }
        }
        val candidates = mutableListOf<Pair<String, String?>>()
        val hyper = prop("ro.mi.os.version.name")
        val miui = prop("ro.miui.ui.version.name")
        if (hyper != null) candidates += "HyperOS" to hyper
        else if (miui != null) candidates += "MIUI" to miui

        val realme = prop("ro.build.version.realmeui")
        val oxygen = prop("ro.oxygen.version")
        val oppo = prop("ro.build.version.opporom")
        if (realme != null) candidates += "realme UI" to realme
        if (oxygen != null) candidates += "OxygenOS" to oxygen
        // Oplus compatibility properties are also present on realme and OnePlus firmware. Their
        // presence alone is insufficient to name that firmware ColorOS.
        if (oppo != null && realme == null && oxygen == null &&
            brand.equals("OPPO", true) && manufacturer.equals("OPPO", true)) {
            candidates += "ColorOS" to oppo
        }
        when (prop("ro.vivo.os.name")?.lowercase(java.util.Locale.ROOT)) {
            "originos" -> candidates += "OriginOS" to prop("ro.vivo.os.version")
            "funtouch", "funtouch os", "funtouchos" -> candidates += "Funtouch OS" to prop("ro.vivo.os.version")
        }
        prop("ro.build.version.oneui")?.let { value ->
            // Samsung convention: 60100 -> 6.1. Treat unknown encodings as version unavailable.
            val number = value.toIntOrNull()?.takeIf { it in 10000..999999 && it % 100 == 0 }
            candidates += "One UI" to number?.let { "${it / 10000}.${it / 100 % 100}" }
        }
        val magic = prop("ro.build.version.magic")
        if (magic != null) candidates += (if (magic.startsWith("MagicUI", true)) "Magic UI" else "MagicOS") to magic
        else prop("ro.build.version.emui")?.let { candidates += "EMUI" to it }
        // Only explicit Flyme naming is meaningful; generic Build.DISPLAY is not an OS version.
        prop("ro.build.display.id")?.takeIf { it.startsWith("Flyme ", true) }?.let {
            candidates += "Flyme" to it.substringAfter(' ').trim().takeIf(String::isNotEmpty)
        }
        val rom = candidates.singleOrNull()
        return linkedMapOf<String, Any?>(
            "platform" to "android", "os_name" to (rom?.first ?: "Android"),
            "android_version" to androidVersion, "android_api_level" to apiLevel,
            "device_manufacturer" to manufacturer, "device_brand" to brand, "device_model" to model,
            "os_detection_source" to if (rom == null) "fallback" else "vendor_property"
        ).apply {
            val version = if (rom == null) androidVersion.takeIf(String::isNotBlank) else rom.second
            if (version != null) put("os_version", version)
        }
    }

    private fun probe(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        for (key in keys) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || Thread.currentThread().isInterrupted) break
            var process: Process? = null
            try {
                process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(60)), TimeUnit.NANOSECONDS) &&
                    process.exitValue() == 0) {
                    // Read at most 257 bytes after process exit. Reject oversized values rather than
                    // truncating them into apparently valid evidence. A hung/full-pipe child times out.
                    val bytes = ByteArray(257)
                    var count = 0
                    while (count < bytes.size) {
                        val read = process.inputStream.read(bytes, count, bytes.size - count)
                        if (read <= 0) break
                        count += read
                    }
                    if (count in 1..256) result[key] = String(bytes, 0, count, Charsets.UTF_8).trim()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: Exception) {
                // getprop can be unavailable or denied by the device. Metadata must never break init.
            } finally {
                process?.let {
                    try { it.destroyForcibly() } catch (_: Exception) { }
                    try { it.inputStream.close() } catch (_: Exception) { }
                    try { it.outputStream.close() } catch (_: Exception) { }
                    try { it.errorStream.close() } catch (_: Exception) { }
                }
            }
        }
        return result
    }
}

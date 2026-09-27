package top.aidanrao.analytics

import org.junit.Assert.*
import org.junit.Test

class SystemInfoTest {
    private fun resolve(properties: Map<String, String> = emptyMap(), brand: String = "OPPO", manufacturer: String = "OPPO") =
        SystemInfo.resolve("14", 34, manufacturer, brand, "CPH-test", properties)

    @Test fun unknownRomDoesNotGuessFromBrand() {
        val info = resolve()
        assertEquals("Android", info["os_name"])
        assertEquals("14", info["os_version"])
        assertEquals("fallback", info["os_detection_source"])
        assertEquals(34, info["android_api_level"])
        assertEquals("14", info["android_version"])
        assertEquals("CPH-test", info["device_model"])
    }
    @Test fun explicitColorOsKeepsAndroidVersionSeparate() {
        val info = resolve(mapOf("ro.build.version.opporom" to "V14.0"))
        assertEquals("ColorOS", info["os_name"])
        assertEquals("V14.0", info["os_version"])
        assertEquals("14", info["android_version"])
        assertEquals("vendor_property", info["os_detection_source"])
    }
    @Test fun sharedOplusPropertiesDoNotMislabelOtherBrands() {
        assertEquals("Android", resolve(mapOf("ro.build.version.opporom" to "V14.0"), "OnePlus", "OnePlus")["os_name"])
        assertEquals("Android", resolve(mapOf("ro.build.version.opporom" to "V14.0"), "realme", "realme")["os_name"])
        assertEquals("realme UI", resolve(mapOf("ro.build.version.realmeui" to "5.0"), "realme", "realme")["os_name"])
    }
    @Test fun hyperOsWinsOverCompatibilityMiuiProperty() {
        val info = resolve(mapOf("ro.mi.os.version.name" to "OS1.0", "ro.miui.ui.version.name" to "V816"))
        assertEquals("HyperOS", info["os_name"])
        assertEquals("OS1.0", info["os_version"])
    }
    @Test fun conflictingRomFamiliesFallBackInsteadOfGuessing() {
        assertEquals("Android", resolve(mapOf("ro.miui.ui.version.name" to "V14", "ro.build.version.emui" to "EmotionUI_13"))["os_name"])
    }
    @Test fun blankAndOversizedPropertiesAreIgnored() {
        assertEquals("Android", resolve(mapOf("ro.build.version.opporom" to " "))["os_name"])
        assertEquals("Android", resolve(mapOf("ro.build.version.opporom" to "x".repeat(257)))["os_name"])
    }
    @Test fun oneUiVersionIsDecodedFromDocumentedEncodingConvention() {
        assertEquals("6.1", resolve(mapOf("ro.build.version.oneui" to "60100"))["os_version"])
        assertFalse(resolve(mapOf("ro.build.version.oneui" to "unknown")).containsKey("os_version"))
    }
}

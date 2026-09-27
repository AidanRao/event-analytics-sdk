import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}
android {
    namespace = "top.aidanrao.analytics.fragment"
    compileSdk = 34
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") } }
}
dependencies {
    api(project(":sdk"))
    api("androidx.fragment:fragment-ktx:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.10.1")

}
mavenPublishing {
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))
    coordinates("top.aidanrao", "event-analytics-fragment", providers.gradleProperty("VERSION_NAME").get())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name.set("Event Analytics Android Fragment Adapter")
        description.set("Optional Fragment page tracking for Event Analytics Android SDK")
        url.set("https://github.com/AidanRao/event-analytics-sdk")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
        developers { developer { id.set("AidanRao"); name.set("AidanRao"); url.set("https://aidanrao.top") } }
        scm { url.set("https://github.com/AidanRao/event-analytics-sdk"); connection.set("scm:git:https://github.com/AidanRao/event-analytics-sdk.git"); developerConnection.set("scm:git:ssh://git@github.com/AidanRao/event-analytics-sdk.git") }
    }
}

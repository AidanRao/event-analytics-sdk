plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "top.aidanrao.analytics.example"
    compileSdk = 34
    defaultConfig {
        applicationId = "top.aidanrao.analytics.example"
        minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("top.aidanrao:event-analytics-navigation:${providers.gradleProperty("sdkVersion").getOrElse("0.2.0")}")
    implementation("top.aidanrao:event-analytics-fragment:${providers.gradleProperty("sdkVersion").getOrElse("0.2.0")}")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    implementation("top.aidanrao:event-analytics:${providers.gradleProperty("sdkVersion").getOrElse("0.2.0")}") }

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
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    implementation("top.aidanrao:event-analytics:${providers.gradleProperty("sdkVersion").getOrElse("0.1.0")}") }

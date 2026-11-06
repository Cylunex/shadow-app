plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.shadow.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shadow.app"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.shadow.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.15.0"
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation(project(":bot-core")) { exclude(group = "org.json", module = "json") }
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    inputs.property("botScreenshots", providers.gradleProperty("botScreenshots").orElse(""))
    providers.gradleProperty("botScreenshots").orNull?.let { systemProperty("botScreenshots", it) }
}

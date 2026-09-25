import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi") version "2.0.0-alpha02"
}

val marketDataUrl = providers.gradleProperty("marketDataUrl").orNull.orEmpty()
// Only public Privy identifiers belong in the Android application. Never add an app secret here.
val localAppProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun publicAppSetting(property: String, environment: String): String =
    providers.gradleProperty(property).orNull
        ?: providers.environmentVariable(environment).orNull
        ?: localAppProperties.getProperty(property).orEmpty()
fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val privyAppId = publicAppSetting("privyAppId", "PRIVY_APP_ID").trim()
val privyClientId = publicAppSetting("privyClientId", "PRIVY_CLIENT_ID").trim()
val privyUrlScheme = publicAppSetting("privyUrlScheme", "PRIVY_URL_SCHEME").trim()
    .ifEmpty { "com.elevencapital.app.privy" }
require(privyUrlScheme.matches(Regex("[a-z][a-z0-9+.-]*"))) { "Privy URL scheme must be a lowercase URI scheme without ://." }
if (marketDataUrl.isNotEmpty()) {
    val endpoint = URI(marketDataUrl)
    require(endpoint.scheme == "https" || (endpoint.scheme == "http" && endpoint.host in listOf("127.0.0.1", "localhost"))) {
        "Market data must use HTTPS, or loopback HTTP for a local debug build."
    }
    require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
}

android {
    namespace = "com.elevencapital.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elevencapital.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 42
        versionName = "0.18.21"
        buildConfigField("boolean", "LIVE_MARKET_DATA", marketDataUrl.isNotEmpty().toString())
        // The server independently validates executable routes, including the pinned
        // Ethereum/Arbitrum-to-Anthropic Relay routes; other unverified routes remain preview-only.
        buildConfigField("boolean", "PURCHASE_EXECUTION_ENABLED", "true")
        buildConfigField("String", "MARKET_DATA_URL", "\"" + marketDataUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        buildConfigField("String", "PRIVY_APP_ID", buildConfigString(privyAppId))
        buildConfigField("String", "PRIVY_CLIENT_ID", buildConfigString(privyClientId))
        buildConfigField("String", "PRIVY_URL_SCHEME", buildConfigString(privyUrlScheme))
        manifestPlaceholders["privyUrlScheme"] = privyUrlScheme
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // Privy's crypto dependency and jspecify both ship JVM-only OSGi metadata.
        // Android does not load this manifest; preserve their code and license files.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

kotlin {
    sourceSets.getByName("main").kotlin.apply {
        setSrcDirs(listOf(rootProject.projectDir))
        include("*.kt", "auth/*.kt", "data/*.kt", "purchase/*.kt", "screens/*.kt", "ui/*.kt", "wallet/*.kt")
    }
}

// Fixture data is for local layout tests, never an installable wallet application.
val verifyLiveAppConfiguration by tasks.registering {
    doLast {
        check(marketDataUrl.isNotBlank()) {
            "APK builds require -PmarketDataUrl=<backend URL>. " +
                "For local device testing use -PmarketDataUrl=http://127.0.0.1:8787 and adb reverse tcp:8787 tcp:8787."
        }
    }
}
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    dependsOn(verifyLiveAppConfiguration)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(enforcedPlatform("androidx.compose:compose-bom:2025.05.01"))
    implementation(project(":core"))
    implementation("io.privy:privy-core:0.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}

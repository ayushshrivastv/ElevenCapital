import java.net.URI
import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi") version "2.0.0-alpha02"
}

val marketDataUrl = providers.gradleProperty("marketDataUrl").orNull.orEmpty()
// Keep the Mainnet execution implementation available for explicit code review/tests,
// while the installable UI build does not compile or link its unused coordinators.
val includeRealTokenExecution = providers.gradleProperty("includeRealTokenExecution").orNull == "true"
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
        versionCode = 80
        versionName = "0.21.33"
        buildConfigField("boolean", "LIVE_MARKET_DATA", marketDataUrl.isNotEmpty().toString())
        // The server independently validates each executable same-chain Solana route;
        // unverified routes remain preview-only.
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
        include(
            "*.kt", "auth/*.kt", "communitybuy/*.kt", "data/*.kt", "purchase/*.kt", "screens/*.kt",
            "tokenlaunch/*.kt", "ui/*.kt", "wallet/*.kt",
        )
        if (!includeRealTokenExecution) exclude(
            "CommunityBuyAppController.kt",
            "auth/PrivyRealExecutionSigner.kt",
            "screens/CommunityBuyFlow.kt",
            "communitybuy/CommunityBuyClient.kt",
            "communitybuy/CommunityBuyCoordinator.kt",
            "communitybuy/CommunityBuyPendingStore.kt",
            "communitybuy/CommunityMainnetVerifier.kt",
            "communitybuy/CommunityBuyProtocol.kt",
            "communitybuy/SolanaBuyAddresses.kt",
            "tokenlaunch/TokenLaunchClient.kt",
            "tokenlaunch/TokenLaunchCoordinator.kt",
            "tokenlaunch/TokenLaunchMetadata.kt",
            "tokenlaunch/TokenLaunchProtocol.kt",
            "tokenlaunch/TokenLaunchRealUiController.kt",
        )
        if (includeRealTokenExecution) exclude("tokenlaunch/TokenLaunchUiController.kt")
    }
    if (!includeRealTokenExecution) sourceSets.getByName("test").kotlin.exclude(
        "com/elevencapital/app/communitybuy/CommunityBuyCoordinatorTest.kt",
        "com/elevencapital/app/communitybuy/CommunityMainnetVerifierTest.kt",
        "com/elevencapital/app/communitybuy/CommunityBuyProtocolTest.kt",
        "com/elevencapital/app/tokenlaunch/TokenLaunchClientTest.kt",
        "com/elevencapital/app/tokenlaunch/TokenLaunchCoordinatorTest.kt",
        "com/elevencapital/app/tokenlaunch/TokenLaunchMetadataTest.kt",
        "com/elevencapital/app/tokenlaunch/TokenLaunchProtocolTest.kt",
        "com/elevencapital/app/tokenlaunch/TokenLaunchRealUiControllerTest.kt",
    )
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
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(rootProject.tasks.named("pruneBuildArtifacts"))
}

// Save every successful APK build outside Gradle's disposable build directory.
// The shared helper deduplicates unchanged builds and keeps numbering after cleanup.
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    doLast {
        val buildType = if (name == "assembleDebug") "debug" else "release"
        val outputDirectory = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
        val metadata = JsonSlurper().parse(outputDirectory.resolve("output-metadata.json")) as Map<*, *>
        val elements = metadata["elements"] as List<*>
        check(elements.isNotEmpty()) { "No APK outputs were produced for $buildType." }
        elements.forEach { element ->
            val outputFile = (element as Map<*, *>)["outputFile"] as String
            val savedApk = providers.exec {
                commandLine(
                    "python3", rootProject.file("scripts/archive_apk.py"),
                    outputDirectory.resolve(outputFile),
                    "--archive-dir", rootProject.file("Apk build")
                )
            }.standardOutput.asText.get().trim()
            logger.lifecycle("APK saved to: $savedApk")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(enforcedPlatform("androidx.compose:compose-bom:2025.05.01"))
    implementation(project(":core"))
    implementation(project(":market-data"))
    implementation("io.privy:privy-core:0.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}

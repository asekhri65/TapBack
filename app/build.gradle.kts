import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Where the phone asks for its next step, and the token it presents.
 *
 * Both come from local.properties, which is gitignored, and both are empty
 * unless a developer has put them there. The provider key is not among them
 * and never reaches the app: the phone holds a token for Dot's own service,
 * and that service holds the key.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProperty(name: String): String = localProperties.getProperty(name).orEmpty()

/**
 * Where a release build asks for guidance: a hosted TapBack service, HTTPS
 * only. From local.properties (tapback.release.backend.url) or the
 * TAPBACK_BACKEND_URL environment variable; empty when neither is set, and a
 * build with a non-HTTPS address fails rather than ship cleartext.
 */
fun releaseBackendUrl(): String {
    val url = localProperty("tapback.release.backend.url").ifBlank { System.getenv("TAPBACK_BACKEND_URL").orEmpty() }.trim().trimEnd('/')
    require(url.isEmpty() || url.startsWith("https://")) {
        "tapback.release.backend.url must be an https:// address; a release build never speaks cleartext."
    }
    return url
}

/** A RevenueCat public SDK key from local.properties, only if it is the kind this build may carry. */
fun revenueCatKey(name: String, prefix: String): String =
    localProperty(name).trim().takeIf { it.startsWith(prefix) && it.all { c -> c.isLetterOrDigit() || c == '_' } }.orEmpty()

android {
    namespace = "com.aaravsekhri.dot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aaravsekhri.dot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // A developer's own machine, over the local network.
            buildConfigField("String", "DOT_BACKEND_URL", "\"${localProperty("dot.backend.url")}\"")
            buildConfigField("String", "DOT_BACKEND_TOKEN", "\"${localProperty("dot.backend.token")}\"")
            // RevenueCat's Test Store: its public SDK key starts "test_" and
            // is only ever put in a debug build. Anything else is ignored.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey("revenuecat.test.key", "test_")}\"")
        }
        release {
            // A hosted service over HTTPS, when one is configured, and never
            // a shared credential: each install proves itself with its own
            // identity (the service runs with DOT_INSTALL_AUTH=1). Unset, the
            // release build has no guidance service and says so.
            buildConfigField("String", "DOT_BACKEND_URL", "\"${releaseBackendUrl()}\"")
            buildConfigField("String", "DOT_BACKEND_TOKEN", "\"\"")
            // Google Play's public SDK key ("goog_"): never a Test Store key,
            // which RevenueCat refuses in a release build.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey("revenuecat.google.key", "goog_")}\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.revenuecat.purchases)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
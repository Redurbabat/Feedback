plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing must stay reproducible across updates: a changed signing key forces an uninstall, and
// an uninstall destroys the Android Keystore key - which means device identity and pairing are
// lost. The keystore location and its passwords therefore come from Gradle properties or
// environment variables only. Nothing is ever read from repository content, and there is no
// fallback password. When anything is missing, no fixed signing config is created at all and the
// build falls back to the default debug signing, which keeps CI working without secrets.
//
// What is PUBLISHED is the release build, not the debug one. A debuggable APK lets anyone with
// ADB access read the app's private data directory through `run-as`, without root and without
// knowing the app lock - which would hand over the deviceToken and the sealed app-lock state.
// The Keystore key itself stays in hardware either way, but a token is enough to speak as the
// device. Shipping a debuggable build of an app whose whole premise is that secret would
// undo a good part of it (THREAT_MODEL 4.8).
val signingValue: (String) -> String? = { name ->
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
}

val feedbackKeystoreFile = signingValue("FEEDBACK_KEYSTORE_FILE")?.let { rootProject.file(it) }
val feedbackStorePassword = signingValue("FEEDBACK_STORE_PASSWORD")
val feedbackKeyPassword = signingValue("FEEDBACK_KEY_PASSWORD")
val feedbackKeyAlias = signingValue("FEEDBACK_KEY_ALIAS")

// No smart cast is relied on here: script level declarations are properties, not locals.
val stableSigningAvailable = feedbackKeystoreFile?.isFile == true &&
    feedbackStorePassword != null &&
    feedbackKeyPassword != null &&
    feedbackKeyAlias != null

val stableSigningConfigName = "feedbackStable"

android {
    namespace = "com.redurbabat.feedback"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.redurbabat.feedback"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (stableSigningAvailable) {
            create(stableSigningConfigName).apply {
                storeFile = feedbackKeystoreFile
                storePassword = feedbackStorePassword
                keyAlias = feedbackKeyAlias
                keyPassword = feedbackKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (stableSigningAvailable) {
                signingConfig = signingConfigs.getByName(stableSigningConfigName)
            }
        }
        release {
            // Always signed with something, so the published APK installs. With the secret it is
            // the stable key, which is what lets an update keep the device identity; without it
            // the debug key, which changes between machines - the release notes say so.
            signingConfig = if (stableSigningAvailable) {
                signingConfigs.getByName(stableSigningConfigName)
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.okhttp)
    // Pure-Java QR encoder; no android.* and no camera code is pulled in.
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // org.json is provided by the Android framework at runtime. On the JVM unit test classpath
    // the stubbed android.jar would throw "not mocked", so a real implementation is added here.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

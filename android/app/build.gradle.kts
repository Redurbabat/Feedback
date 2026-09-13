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
// The config is applied to debug as well, because the published artifact is the debug APK.
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
            if (stableSigningAvailable) {
                signingConfig = signingConfigs.getByName(stableSigningConfigName)
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // org.json is provided by the Android framework at runtime. On the JVM unit test classpath
    // the stubbed android.jar would throw "not mocked", so a real implementation is added here.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

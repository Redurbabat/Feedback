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
//
// The same reader now also brings in FEEDBACK_SERVER_URL below. It is not signing material and not
// a secret, but it has the same property: it belongs to the owner's deployment, not to the
// repository.
val configValue: (String) -> String? = { name ->
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
}

val feedbackKeystoreFile = configValue("FEEDBACK_KEYSTORE_FILE")?.let { rootProject.file(it) }
val feedbackStorePassword = configValue("FEEDBACK_STORE_PASSWORD")
val feedbackKeyPassword = configValue("FEEDBACK_KEY_PASSWORD")
val feedbackKeyAlias = configValue("FEEDBACK_KEY_ALIAS")

// No smart cast is relied on here: script level declarations are properties, not locals.
val stableSigningAvailable = feedbackKeystoreFile?.isFile == true &&
    feedbackStorePassword != null &&
    feedbackKeyPassword != null &&
    feedbackKeyAlias != null

val stableSigningConfigName = "feedbackStable"

/**
 * Deliberately a copy of the host rules in
 * `app/src/main/java/com/redurbabat/feedback/network/ServerEndpoint.kt`, because the two must
 * agree: DEFAULT_SERVER_URL is the trust anchor a setup link is compared against, and that
 * comparison runs the build value through ServerEndpoint. A value this accepted but ServerEndpoint
 * rejected would ship an app that cannot use its own default and cannot accept any link either.
 *
 * Failing the build is the point. A mistyped anchor is not something to discover on a phone.
 */
fun normalizedServerOrigin(raw: String): String {
    // Longest host name DNS carries, and longest single label - the limits ServerEndpoint.kt uses.
    val maxHostLength = 253
    val maxLabelLength = 63

    fun reject(reason: String): Nothing = throw GradleException(
        "FEEDBACK_SERVER_URL=\"$raw\" is not usable: $reason. Expected exactly " +
            "https://<host> or https://<host>:<port> - no path, query, fragment or credentials, " +
            "and a registrable host name (at least two labels, letters/digits/hyphen only, " +
            "no address literal, no trailing dot).",
    )

    val uri = try {
        java.net.URI(raw)
    } catch (_: java.net.URISyntaxException) {
        reject("it is not a valid URI")
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) {
        reject("the scheme must be https")
    }
    if (uri.rawUserInfo != null) {
        reject("it carries credentials")
    }
    if (uri.rawQuery != null || uri.rawFragment != null) {
        reject("it carries a query or a fragment")
    }
    if (!uri.rawPath.isNullOrEmpty()) {
        reject("it carries a path - a trailing \"/\" counts as one")
    }
    val host = (uri.host ?: reject("the host is missing or is not a plain host name")).lowercase()
    if (uri.port != -1 && uri.port !in 1..65_535) {
        reject("the port is out of range")
    }
    if (host.length > maxHostLength || host.endsWith('.')) {
        reject("the host name is too long or ends in a dot")
    }
    val labels = host.split('.')
    if (labels.size < 2) {
        reject("the host name needs at least two labels")
    }
    // An IPv4 literal falls out of this rather than needing a rule of its own: it ends in an
    // all-digit label, and no top level domain does. IPv6 literals never reach here, "[" is not an
    // allowed character.
    if (labels.last().all(Char::isDigit)) {
        reject("the last label is numeric, so this is an address and not a name")
    }
    for (label in labels) {
        val usable = label.isNotEmpty() &&
            label.length <= maxLabelLength &&
            label.first() != '-' &&
            label.last() != '-' &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        if (!usable) {
            reject("\"$label\" is not a usable host label")
        }
    }
    return "https://$host" + if (uri.port == -1) "" else ":${uri.port}"
}

// The owner's own domain. Not a secret - it is public in the manifest and in assetlinks.json - but
// not repository content either, so it is supplied per deployment. Unset is a valid configuration:
// the app then has no built-in anchor, the server address is typed as before, and every setup link
// is refused because there is nothing for it to be equal to.
val feedbackServerUrl = configValue("FEEDBACK_SERVER_URL")
    ?.let { normalizedServerOrigin(it) }
    .orEmpty()

/**
 * RFC 2606 reserves `.invalid`, so this host can never belong to anyone and the intent filter it
 * builds can never match a real link.
 */
val unclaimableAppLinkHost = "feedback.invalid"

// Digital Asset Links verification always fetches https://<host>/.well-known/assetlinks.json on
// port 443. With a port in the server URL that file is unreachable, so the App Link can never be
// verified - and an unverified autoVerify filter is worse than no filter: the app would claim a
// link it is never granted, and Android would fall back to the disambiguation dialog. Saying so in
// the build log beats claiming it quietly.
val feedbackAppLinkHost = when {
    feedbackServerUrl.isEmpty() -> unclaimableAppLinkHost
    feedbackServerUrl.removePrefix("https://").contains(':') -> {
        logger.warn(
            "FEEDBACK_SERVER_URL carries a port ($feedbackServerUrl). Android verifies App Links " +
                "only over port 443, so no App Link host is claimed and setup links will not " +
                "open this app. The server address still has to be entered by hand.",
        )
        unclaimableAppLinkHost
    }
    else -> feedbackServerUrl.removePrefix("https://")
}

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

        // No quoting worry: normalizedServerOrigin() already limited this to
        // [a-z0-9.-] plus an optional ":<port>", so it cannot close the literal or escape out of
        // it. An empty string is the honest representation of "this build has no anchor".
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$feedbackServerUrl\"")

        // The bare host, no port: an intent filter that names a port only matches that port, and
        // the verification this filter asks for cannot succeed with one anyway.
        manifestPlaceholders["feedbackAppLinkHost"] = feedbackAppLinkHost
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
            // These two fetch https://<host>/.well-known/assetlinks.json from the build machine and
            // report whatever they get. That is a question about a deployment, not about this
            // source tree: the verification that matters happens on the device, at install time,
            // against the host the build was actually configured with. In CI the host is a
            // repository variable that may not be set at all, so the check would fail every build
            // for a reason no code change can fix.
            "AppLinksAutoVerifyError",
            "AppLinksAutoVerifyWarning",
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

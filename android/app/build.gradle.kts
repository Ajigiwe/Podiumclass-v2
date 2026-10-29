import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

//
// Release signing.
//
// The certificate is the app's identity: Android refuses to update an installed
// app with an APK signed by a different key, so this keystore has to outlive
// every build machine and CI runner. It therefore lives outside version control
// (android/keystore.properties is git-ignored) and is read here only if present:
// a fresh clone, or a machine that never had it, still builds debug APKs, while
// releasing there fails to produce a *signed* artifact rather than silently
// shipping one signed with a throwaway debug key.
//
// Generate it once with:
//   keytool -genkeypair -v -keystore android/keystore/podium-release.jks \
//     -alias podium -keyalg RSA -keysize 2048 -validity 10000
//
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile").isNullOrBlank().not()

android {
    namespace = "online.podiumclass.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.podiumclass.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // The one server this build talks to. There is no in-app server field:
        // the address is fixed at build time so a phone can never be left
        // pointing at a dead host by a typo or a stale setting. This is the
        // same API the web client uses (see VITE_API_URL in
        // deploy/publish_frontend.py).
        //
        // Local development overrides it on the command line, which is the only
        // supported way to change it:
        //   ./gradlew assembleDebug -PpodiumApiUrl=http://192.168.1.10:8000
        val podiumApiUrl = (project.findProperty("podiumApiUrl") as String?)
            ?: "https://podium-api-r6sz.onrender.com"
        buildConfigField("String", "DEFAULT_API_URL", "\"$podiumApiUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Paths in keystore.properties are relative to android/.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No R8: shrinking is off, so the APK is larger but nothing needs
            // keep rules. Sign only when the keystore is available; otherwise
            // Gradle emits an unsigned APK, which cannot be installed -- a
            // visible failure instead of a debug-signed "release".
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // DEFAULT_API_URL above is a generated constant.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // LiveKit WebRTC SDK
    implementation(libs.livekit.android)

    // Networking & Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines & DataStore & Credentials
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.credentials.core)
    implementation(libs.credentials.play)
    implementation(libs.google.id)
}

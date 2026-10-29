import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: ~/.local/android/release-keys/jot.properties (or the file named by
// JOT_ANDROID_SIGNING_PROPERTIES), or the same keys as environment variables.
// Without them, release builds use the debug key.
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = file(
    System.getenv("JOT_ANDROID_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/android/release-keys/jot.properties"
)
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "io.github.carnager.jot"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.carnager.jot"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
    }

    signingConfigs {
        create("release") {
            signingValue("JOT_ANDROID_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("JOT_ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("JOT_ANDROID_KEY_ALIAS")
            keyPassword = signingValue("JOT_ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Jot Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Jot")
            signingConfig = if (signingValue("JOT_ANDROID_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Images: Coil with its OkHttp fetcher, so requests carry the bearer token.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // Compiles Compose's bundled baseline profiles on install, so the first launch is already fast.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    // The real org.json; android.jar only has stubs for unit tests.
    testImplementation("org.json:json:20231013")
}

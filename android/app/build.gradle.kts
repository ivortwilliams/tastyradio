import java.util.Properties

// AGP 9 has built-in Kotlin support: applying org.jetbrains.kotlin.android alongside it is an error.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Signing details for the sideloadable release APK, kept out of git (see keystore.properties.example).
// Without them the release build is simply unsigned — a debug build still works for development.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.tastyradio"
    // 37 because the AndroidX versions in libs.versions.toml demand it. targetSdk stays a
    // separate, more conservative decision — compiling against newer APIs is not opting into
    // newer runtime behaviour.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.tastyradio"
        // 29, not 24: AudioPlaybackCapture (the recording feature) does not exist below API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.5"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The same key has to sign every build, or an update won't install over the last one.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)

    // Station artwork comes from the directory (radio-browser's favicon), not from the stream.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Android's own SQLite has no FTS5 (verified: 3.44.3 on API 36 exposes fts3/fts4 only), and
    // both the search index and its bm25 ranking need FTS5. This ships our own SQLite.
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}

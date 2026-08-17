// AGP 9 has built-in Kotlin support: applying org.jetbrains.kotlin.android alongside it is an error.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}

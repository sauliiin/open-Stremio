plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.mdblisthub.tv.player"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    buildFeatures { compose = true }
}

dependencies {
    api(projects.core.model)
    // The only module that sees ExoPlayer. Everything upstream talks to the
    // engine through `PlaybackController`, so swapping the backend never
    // reaches the screens — which is what made this migration off mpv (and
    // off libVLC before it) a change to this module alone.
    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mdblisthub.tv.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(projects.core.model)
    api(projects.core.network)
    api(projects.core.database)
    api(libs.work.runtime)
    api(libs.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    // `SubtitleFileParser` is pure JVM on purpose, so the files that actually
    // break it can be checked in and run without a device.
    testImplementation(libs.junit)
}

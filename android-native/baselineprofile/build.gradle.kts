plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

/**
 * The profile generator.
 *
 * Nothing here ships. This module builds an instrumentation APK that launches
 * the *release* app on a real device, walks it through a cold start, and
 * records which classes and methods that actually touched. The plugin bakes
 * the result into `:app` as `baseline-prof.txt`, and ART uses it to
 * ahead-of-time compile exactly that code at install time instead of leaving
 * the first run to the interpreter.
 */
android {
    namespace = "com.mdblisthub.tv.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Macrobenchmark's own floor, and unrelated to the app's minSdk of 24:
        // this APK only ever runs on the machine generating the profile. The
        // profile it produces is still applied all the way down to 24 through
        // `profileinstaller`.
        minSdk = 28
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

    targetProjectPath = ":app"
}

baselineProfile {
    // Drives whatever is attached to adb rather than a Gradle-managed device,
    // which would download and boot a second emulator for every run.
    //
    // ⚠ THIS MEANS *EVERY* ATTACHED DEVICE. This project is developed with a
    // physical TV box paired over network adb alongside the emulator, and with
    // both attached this task installs the release app plus this
    // instrumentation APK onto the TV as well and drives its UI — which is not
    // something a profile generation run should ever do to a device someone is
    // watching. Always pin the target:
    //
    //     ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile
    //
    // `adb devices` lists the serials.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.google.services)
}

val localSecrets = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun secret(name: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localSecrets.getProperty(name).orEmpty()

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.mdblisthub.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Matches the Android client registered in safevault-fcbdc.
        applicationId = "mdblist_hub.apk.S84"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 46
        versionName = "1.1.17"

        // Credentials live outside version control. They are still public to
        // anyone reverse-engineering a client APK, so quota-bearing services
        // should ultimately sit behind a backend; this at least prevents keys
        // from being copied forever through the repository history.
        buildConfigField("String", "TMDB_API_KEY", quoted(secret("TMDB_API_KEY")))
        buildConfigField("String", "OMDB_API_KEY", quoted(secret("OMDB_API_KEY")))
        buildConfigField("String", "FANART_TV_API_KEY", quoted(secret("FANART_TV_API_KEY")))
        buildConfigField("String", "TRAKT_CLIENT_ID", quoted(secret("TRAKT_CLIENT_ID")))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", quoted(secret("TRAKT_CLIENT_SECRET")))
        buildConfigField("String", "SIMKL_CLIENT_ID", quoted(secret("SIMKL_CLIENT_ID")))
        buildConfigField("String", "OPENSUBTITLES_API_KEY", quoted(secret("OPENSUBTITLES_API_KEY")))
        buildConfigField("String", "WYZIE_API_KEY", quoted(secret("WYZIE_API_KEY")))

        // x86 (32-bit) is back on the list deliberately: the emulator this
        // app is tested on reports exactly that ABI, and without a native
        // slice it silently ran the armeabi-v7a one through binary
        // translation — molasses that looked like app slowness. Post-mpv the
        // native payload is a few hundred KB per ABI, so carrying it is free.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    // Far less load-bearing since the player became Media3, which decodes
    // through Android's own MediaCodec rather than shipping its own ffmpeg —
    // the per-ABI split now saves kilobytes where it used to save tens of
    // megabytes. Kept because the universal APK is still the sideload path
    // onto an unknown box.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The debug keystore, on purpose: this app is sideloaded, not
            // published, and an unsigned release APK cannot be installed at
            // all. R8 + shrinking is where the "lighter and faster" build
            // lives — debug builds carry Compose's runtime checks and none
            // of the dead-code stripping. Swap for a real keystore if this
            // ever heads to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/*.version",
        )
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.player)
    implementation(files("../player/libs/media3-decoder-ffmpeg-1.11.0.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // Reads the profile baked in below and hands it to ART at install time.
    // Without this the profile ships inside the APK and is never applied — on
    // a set-top box that is the difference between the first launch running
    // compiled and running interpreted.
    implementation(libs.profileinstaller)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)

    // Where the profile comes from. `:baselineprofile` builds nothing that
    // ships; this wiring is what makes its output land in the release APK.
    baselineProfile(projects.baselineprofile)
}

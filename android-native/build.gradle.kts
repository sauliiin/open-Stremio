plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
}

/**
 * Applied here rather than in each of `app`, `core:ui` and `player`, because
 * a stability declaration only holds if *every* module compiling composables
 * agrees on it: one module left out would go on treating the same
 * `MediaItem` as unstable and re-emit unskippable code for it. See
 * `compose_compiler_config.conf` for what is declared and why.
 */
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            // Plural, not the singular `stabilityConfigurationFile`: that one
            // is a hard error as of Kotlin 2.4 and disappears in 2.5.
            stabilityConfigurationFiles.add(
                rootProject.layout.projectDirectory.file("compose_compiler_config.conf"),
            )
        }
    }
}

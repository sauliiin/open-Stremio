package com.mdblisthub.tv

import android.app.Activity
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The Activity, published separately from [androidx.compose.ui.platform.LocalContext].
 *
 * Normally the two are the same object and nobody needs this. Here they are
 * not: `MainActivity` overrides `LocalContext` with a locale-wrapped context so
 * that `stringResource` follows the in-app language setting, and what
 * `createConfigurationContext` returns is a bare `ContextImpl` — no `Activity`
 * in it, and not a `ContextWrapper` either, so the usual trick of unwrapping
 * `baseContext` until an Activity appears finds nothing.
 *
 * Anything that needs to open a window — the credential picker most of all —
 * must read the Activity from here rather than from the context.
 */
val LocalHostActivity = staticCompositionLocalOf<Activity?> { null }

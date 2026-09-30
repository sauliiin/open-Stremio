package com.mdblisthub.tv.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R

/**
 * A runtime in minutes, written as hours and minutes.
 *
 * pt: 125 → "2 hs 5 min", 120 → "2 hs", 45 → "45 min"   en/es/fr: "2 h 5 min"
 *
 * Shared by the home hero and the detail screen so the two never disagree
 * about how long the same title is.
 */
@Composable
internal fun runtimeLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.home_minutes, rest)
        rest == 0 -> stringResource(R.string.home_hours, hours)
        else -> stringResource(R.string.home_hours_minutes, hours, rest)
    }
}

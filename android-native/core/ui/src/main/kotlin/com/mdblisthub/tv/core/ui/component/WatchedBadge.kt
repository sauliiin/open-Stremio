package com.mdblisthub.tv.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mdblisthub.tv.core.ui.R
import com.mdblisthub.tv.core.ui.theme.HubColors

@Composable
fun WatchedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_watched),
            contentDescription = "Watched",
            colorFilter = ColorFilter.tint(HubColors.Accent),
            modifier = Modifier.fillMaxSize()
        )
    }
}

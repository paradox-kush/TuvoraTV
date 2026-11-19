@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A live channel as a Home card: the channel logo on a card surface, the channel name under it and, when the guide is
 * already known, what is on now. Built on [LightweightCard], so it takes the same focus ring as every other Home card
 * and the same width and artwork height as a Continue Watching card, which keeps the row heights even.
 */
@Composable
fun LiveChannelCard(
    name: String,
    nowTitle: String?,
    logo: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    cornerRadius: Dp = NuvioTheme.radii.md
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val clipShape = remember(cornerRadius) { RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius) }
    LightweightCard(
        onClick = onClick,
        modifier = modifier.width(cardWidth),
        shape = shape,
        containerColor = Color.Transparent,
        focusedBorder = Border(
            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
            shape = shape
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .height(imageHeight)
                    .clip(clipShape)
                    .background(NuvioTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                if (logo.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(top = NuvioTheme.spacing.xs)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!nowTitle.isNullOrBlank()) {
                    Text(
                        text = nowTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

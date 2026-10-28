package online.podiumclass.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.podiumclass.app.R

/**
 * The Podium mark: the web app's graduation cap, white on brand indigo.
 *
 * One drawable ([R.drawable.ic_podium_cap]) feeds this mark, the launcher icon
 * and the notification small icon, so the splash screen, the sign-in header and
 * the icon in the launcher are the same brand -- and the same brand as the web
 * header, which draws the identical glyph in the identical blue.
 */
@Composable
fun PodiumMark(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    markColor: Color = Color.White,
    containerColor: Color? = null,
    cornerRadius: Dp = size / 4
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (containerColor != null) {
                    Modifier
                        .clip(shape)
                        .background(
                            Brush.linearGradient(
                                listOf(containerColor, containerColor.copy(alpha = 0.82f))
                            )
                        )
                } else {
                    Modifier
                }
            )
            // The cap is a wide, flat glyph; a quarter of the box as padding is
            // what gives it the same weight as the web header's 14px icon in a
            // 32px tile.
            .padding(size * 0.24f),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_podium_cap),
            contentDescription = null,
            colorFilter = ColorFilter.tint(markColor),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Mark plus the Podium wordmark, for headers and sign-in. */
@Composable
fun PodiumLogo(
    markSize: Dp = 36.dp,
    textSize: Int = 24,
    markColor: Color = Color.White,
    containerColor: Color = Color(0xFF1845D4),
    labelColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        PodiumMark(size = markSize, markColor = markColor, containerColor = containerColor)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Podium",
            color = labelColor,
            fontSize = textSize.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp
        )
    }
}

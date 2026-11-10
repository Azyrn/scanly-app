package com.skeler.scanely.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Scanly's copy icon: two equal rounded squares, the back one hidden where the
 * front one covers it. Tinted by [androidx.compose.material3.Icon] like any
 * Material icon.
 */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "CopyIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Back square: only the part outside the front square's outer edge (x/y = 6.75).
        addPath(
            pathData = PathParser().parsePathString(
                "M6.75 16.5H5.4A2.6 2.6 0 0 1 2.8 13.9V5.4A2.6 2.6 0 0 1 5.4 2.8" +
                    "H13.9A2.6 2.6 0 0 1 16.5 5.4V6.75"
            ).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Round
        )
        // Front square.
        addPath(
            pathData = PathParser().parsePathString(
                "M10.1 7.5H18.6A2.6 2.6 0 0 1 21.2 10.1V18.6A2.6 2.6 0 0 1 18.6 21.2" +
                    "H10.1A2.6 2.6 0 0 1 7.5 18.6V10.1A2.6 2.6 0 0 1 10.1 7.5Z"
            ).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()
}

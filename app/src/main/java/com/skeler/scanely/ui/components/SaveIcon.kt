package com.skeler.scanely.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Scanly's save icon: a round-capped arrow dropping into an open tray.
 * Tinted by [androidx.compose.material3.Icon] like any Material icon.
 */
val SaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SaveIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        listOf(
            // Shaft.
            "M12 4.1V16",
            // Arrow head.
            "M7.6 11.6L12 16L16.4 11.6",
            // Tray.
            "M4.05 16.6V18.8A1.25 1.25 0 0 0 5.3 20.05H18.7A1.25 1.25 0 0 0 19.95 18.8V16.6"
        ).forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
}

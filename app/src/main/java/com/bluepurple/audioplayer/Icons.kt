package com.bluepurple.audioplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun buildIcon(name: String, stroke: List<String> = emptyList(), fill: List<String> = emptyList()): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    fill.forEach { data ->
        builder.addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
    }
    stroke.forEach { data ->
        builder.addPath(
            PathParser().parsePathString(data).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }
    return builder.build()
}

private fun roundedSquare(x: Int, y: Int) =
    "M${x + 1} ${y}h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H${x + 1}a1 1 0 0 1-1-1V${y + 1}a1 1 0 0 1 1-1z"

internal val IcPlay = buildIcon("play", fill = listOf("M7 4.5v15l13-7.5z"), stroke = listOf("M7 4.5v15l13-7.5z"))
internal val IcPause = buildIcon("pause", fill = listOf("M6 4h4v16H6z", "M14 4h4v16h-4z"))
internal val IcPrev = buildIcon("prev", fill = listOf("M19 5v14L8 12z"), stroke = listOf("M19 5v14L8 12z", "M5 5v14"))
internal val IcNext = buildIcon("next", fill = listOf("M5 5v14l11-7z"), stroke = listOf("M5 5v14l11-7z", "M19 5v14"))
internal val IcShuffle = buildIcon("shuffle", stroke = listOf("M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5"))
internal val IcRepeat = buildIcon(
    "repeat",
    stroke = listOf("M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
)
internal val IcNote = buildIcon(
    "note",
    stroke = listOf("M9 18V5l12-2v13", "M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0z", "M21 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z")
)
internal val IcFolder = buildIcon("folder", stroke = listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"))
internal val IcGrid = buildIcon(
    "grid",
    stroke = listOf(roundedSquare(3, 3), roundedSquare(14, 3), roundedSquare(3, 14), roundedSquare(14, 14))
)
internal val IcDownload = buildIcon("download", stroke = listOf("M12 3v12M7 10l5 5 5-5M5 21h14"))
internal val IcList = buildIcon("list", stroke = listOf("M8 6h13M8 12h13M8 18h13M3 6h0.01M3 12h0.01M3 18h0.01"))
internal val IcSearch = buildIcon("search", stroke = listOf("M18 11a7 7 0 1 1-14 0a7 7 0 0 1 14 0z", "M21 21l-4.3-4.3"))
internal val IcRefresh = buildIcon("refresh", stroke = listOf("M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5"))
internal val IcTheme = buildIcon(
    "theme",
    stroke = listOf("M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0z", "M8 10.5h0.01M12 7.5h0.01M16 10.5h0.01")
)
internal val IcCheck = buildIcon("check", stroke = listOf("M5 12l5 5 9-10"))
internal val IcPlus = buildIcon("plus", stroke = listOf("M12 5v14M5 12h14"))
internal val IcClose = buildIcon("close", stroke = listOf("M18 6L6 18M6 6l12 12"))
internal val IcLink = buildIcon(
    "link",
    stroke = listOf(
        "M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.7 1.7",
        "M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.7-1.7"
    )
)
internal val IcCloud = buildIcon("cloud", stroke = listOf("M7 18h10a4 4 0 0 0 0.6-7.95A6 6 0 0 0 6 11a3.5 3.5 0 0 0 1 7z"))
internal val IcChevron = buildIcon("chevron", stroke = listOf("M9 6l6 6-6 6"))

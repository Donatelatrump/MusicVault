package com.musicvault.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handful of Material icons the app needs that are NOT part of
 * material-icons-core. Authoring them locally lets the build depend on the
 * tiny icons-core artifact instead of the multi-MB icons-extended dex, which
 * the dex verifier no longer has to walk at cold start.
 *
 * Path data is the official 24dp Material icon geometry, parsed with
 * PathParser so the strings stay readable and verifiable.
 */
object VaultIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black)
            )
        }.build()

    val Album = icon("Album", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,16.5c-2.49,0 -4.5,-2.01 -4.5,-4.5S9.51,7.5 12,7.5s4.5,2.01 4.5,4.5 -2.01,4.5 -4.5,4.5zM12,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1 1,-0.45 1,-1 -0.45,-1 -1,-1z")

    val DeleteOutline = icon("DeleteOutline", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12zM8,9h8v10L8,19L8,9zM15.5,4l-1,-1h-5l-1,1L5,4v2h14L19,4z")

    val Download = icon("Download", "M19,9h-4L15,3L9,3v6L5,9l7,7 7,-7zM5,18v2h14v-2L5,18z")

    val DragHandle = icon("DragHandle", "M20,9L4,9v2h16L20,9zM4,15h16v-2L4,13v2z")

    val FolderOpen = icon("FolderOpen", "M20,6h-8l-2,-2L4,4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,8c0,-1.1 -0.9,-2 -2,-2zM20,18L4,18L4,8h16v10z")

    val LibraryMusic = icon("LibraryMusic", "M20,2L8,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51L14,5h4v2zM4,6L2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6z")

    val LibraryMusicOutline = icon("LibraryMusicOutline", "M20,4L8,4v12h12L20,4zM22,2v14c0,1.1 -0.9,2 -2,2L8,18c-1.1,0 -2,-0.9 -2,-2L6,2c0,-1.1 0.9,-2 2,-2h12c1.1,0 2,0.9 2,2zM2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6L2,6zM18,6h-4v5.5c0,1.38 -1.12,2.5 -2.5,2.5S9,12.88 9,11.5 10.12,9 11.5,9c0.57,0 1.08,0.19 1.5,0.51L13,4h5v2z")

    val Pause = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")

    val QueueMusic = icon("QueueMusic", "M15,6L3,6v2h12L15,6zM15,10L3,10v2h12v-2zM3,16h8v-2L3,14v2zM17,6v8.18c-0.31,-0.11 -0.65,-0.18 -1,-0.18 -1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3L19,8h3L22,6h-5z")

    val QueueMusicOutline = icon("QueueMusicOutline", "M15,6L3,6v2h12L15,6zM21,6h-2v8.18c-0.31,-0.11 -0.65,-0.18 -1,-0.18 -1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3L21,8h3L24,6h-3zM15,10L3,10v2h12v-2zM3,16h8v-2L3,14v2z")

    val Repeat = icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")

    val RepeatOne = icon("RepeatOne", "M7,7h10v3l4,-4 -4,-4v3L5,5v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4zM11.75,14.5h1.5v-5h-1.5v1h-1.5v1.5h1.5L11.75,14.5z")

    val Shuffle = icon("Shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.41,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5L20,4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20L20,20v-5.5l-2.04,2.04 -3.13,-3.13z")

    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6zM16,6v12h2L18,6z")

    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z")
}

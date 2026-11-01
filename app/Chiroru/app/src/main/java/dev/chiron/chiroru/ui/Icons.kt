package dev.chiron.chiroru.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small local vector set; no bitmap or extended icon dependency. */
internal object Glyph {
    val Console = icon("Console", "M3,4 L21,4 L21,20 L3,20 Z M5,6 L5,18 L19,18 L19,6 Z M7,8 L11,11 L7,14 L6,12.5 L8,11 L6,9.5 Z M12,13 L17,13 L17,15 L12,15 Z")
    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()

    val Home = icon("Home", "M12,3 L2,12 L5,12 L5,21 L10,21 L10,15 L14,15 L14,21 L19,21 L19,12 L22,12 Z")
    val Journal = icon("Journal", "M5,3 L19,3 L19,21 L5,21 Z M7,5 L7,19 L17,19 L17,5 Z M9,8 L15,8 L15,10 L9,10 Z M9,12 L15,12 L15,14 L9,14 Z M9,16 L13,16 L13,17 L9,17 Z")
    val Settings = icon("Settings", "M4,6 L20,6 L20,8 L4,8 Z M4,16 L20,16 L20,18 L4,18 Z M8,3 L10,3 L10,11 L8,11 Z M14,13 L16,13 L16,21 L14,21 Z")
    val Play = icon("Play", "M8,5 L20,12 L8,19 Z")
    val Stop = icon("Stop", "M6,6 L18,6 L18,18 L6,18 Z")
    val Sun = icon("Sun", "M12,7 A5,5 0,1 1,12,17 A5,5 0,1 1,12,7 Z M11,1 L13,1 L13,4 L11,4 Z M11,20 L13,20 L13,23 L11,23 Z M1,11 L4,11 L4,13 L1,13 Z M20,11 L23,11 L23,13 L20,13 Z M3,4.4 L4.4,3 L6.5,5.1 L5.1,6.5 Z M17.5,18.9 L18.9,17.5 L21,19.6 L19.6,21 Z M17.5,5.1 L19.6,3 L21,4.4 L18.9,6.5 Z M3,19.6 L5.1,17.5 L6.5,18.9 L4.4,21 Z")
    val Moon = icon("Moon", "M20.9,13.3 A8,8 0,0 1,10.7,3.1 A9,9 0,1 0,20.9,13.3 Z")
    val Arrow = icon("Arrow", "M5,11 L15.2,11 L11,6.8 L12.4,5.4 L19,12 L12.4,18.6 L11,17.2 L15.2,13 L5,13 Z")
    val Check = icon("Check", "M9,16.2 L4.8,12 L3.4,13.4 L9,19 L21,7 L19.6,5.6 Z")
    val Copy = icon("Copy", "M8,2 L21,2 L21,18 L8,18 Z M10,4 L10,16 L19,16 L19,4 Z M3,6 L5,6 L5,20 L16,20 L16,22 L3,22 Z")
    val Share = icon("Share", "M18,2 A3,3 0,1 1,15.5,6.6 L8.8,10.4 A3,3 0,0 1,8.8,13.6 L15.5,17.4 A3,3 0,1 1,14.5,19.1 L7.8,15.3 A3,3 0,1 1,7.8,8.7 L14.5,4.9 A3,3 0,0 1,18,2 Z")
    val Shield = icon("Shield", "M12,2 L21,6 L21,12 C21,17 17,21 12,23 C7,21 3,17 3,12 L3,6 Z M12,4.2 L5,7.3 L5,12 C5,16 8,19.1 12,20.8 C16,19.1 19,16 19,12 L19,7.3 Z")
    val Device = icon("Device", "M7,2 L17,2 Q19,2 19,4 L19,20 Q19,22 17,22 L7,22 Q5,22 5,20 L5,4 Q5,2 7,2 Z M7,4 L7,18 L17,18 L17,4 Z M10,19 L10,20 L14,20 L14,19 Z")
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.chiron.chiroru.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chiron.chiroru.RootMethod

internal val RootMethod.icon: ImageVector get() = when (this) {
    RootMethod.PROBE -> Glyph.Check
    RootMethod.IN_APP -> Glyph.Console
    RootMethod.ADB -> Glyph.Device
    RootMethod.KERNEL_SU -> Glyph.Shield
}

/** Material's expressive shape vocabulary, used for focal points rather than every container. */
@Composable
internal fun ExpressiveEmblem(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val shape = MaterialShapes.Cookie6Sided.toShape()
    Surface(modifier.size(size), shape = shape, color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * .46f)) }
    }
}

/** Expressive size tokens and press-to-morph shapes, including the platform motion scheme. */
@Composable
internal fun ExpressiveAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: ImageVector = Glyph.Play) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height),
        shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
        Icon(icon, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(label, style = ButtonDefaults.textStyleFor(height))
    }
}

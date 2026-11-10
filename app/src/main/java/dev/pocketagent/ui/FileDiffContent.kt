package dev.pocketagent.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic diff colors remain red/green even when the app's primary accent changes. */
internal data class FileDiffColors(val added: Color, val removed: Color)

@Composable
internal fun fileDiffColors(): FileDiffColors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    FileDiffColors(Color(0xFF8BE9A8), Color(0xFFFFB4AB))
else FileDiffColors(Color(0xFF146C2E), Color(0xFFB3261E))

@Composable
internal fun FileDiffContent(diff: String, scrollState: ScrollState) {
    val colors = fileDiffColors()
    val lines = remember(diff) { diff.lines() }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scrollState)) {
            Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = 240.dp)) {
                lines.forEach { line ->
                    val color = when (line.firstOrNull()) {
                        '+' -> colors.added
                        '-' -> colors.removed
                        '@' -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val changed = line.startsWith('+') || line.startsWith('-')
                    Text(line.ifEmpty { " " },
                        Modifier.fillMaxWidth().background(if (changed) color.copy(alpha = 0.10f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        color = color, softWrap = false,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp))
                }
            }
        }
    }
}

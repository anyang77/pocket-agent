package dev.pocketagent.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.sin

internal fun studioPreviewRatio(size: String): Float = size.split('x').let {
    val width = it.getOrNull(0)?.toFloatOrNull()
    val height = it.getOrNull(1)?.toFloatOrNull()
    if (width != null && height != null && width.isFinite() && height.isFinite() && width > 0 && height > 0) width / height else 1f
}

/** Abstract waiting surface only. It never claims to depict a not-yet-returned image. */
@Composable
internal fun StudioLoadingPreview(size: String) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && android.animation.ValueAnimator.areAnimatorsEnabled()
    val phase = if (foreground) {
        val transition = rememberInfiniteTransition(label = "等待成图")
        val value by transition.animateFloat(0f, 6.2831855f,
            infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "模糊画布")
        value
    } else 0f
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(studioPreviewRatio(size))
            .clip(RoundedCornerShape(12.dp)).background(surface)
            .semantics { contentDescription = "正在等待图片，模糊画布为占位，不是实时生成预览" },
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Large soft falloffs read as an unfocused canvas, without expensive offscreen blur.
            val radius = this.size.maxDimension * .85f
            val centers = listOf(
                Offset(this.size.width * (.25f + .12f * sin(phase)), this.size.height * .3f),
                Offset(this.size.width * .8f, this.size.height * (.7f + .12f * sin(phase + 2f))),
            )
            centers.forEachIndexed { index, center ->
                val color = if (index == 0) tint.copy(alpha = .14f) else tint.copy(alpha = .06f)
                drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(16.dp),
            shape = MaterialTheme.shapes.small, color = surface.copy(alpha = .94f)) {
            Text("等待成图 · 非实时预览", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tiny, bilinearly sampled copy of the actual result dissolves into the sharp preview. */
@Composable
internal fun StudioImageReveal(bitmap: Bitmap, soft: Bitmap, description: String, animate: Boolean, onRevealed: () -> Unit) {
    val progress = remember(bitmap) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(bitmap) {
        if (animate) progress.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
        onRevealed()
    }
    Box(Modifier.fillMaxSize()) {
        Image(bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (progress.value < 1f) Image(soft.asImageBitmap(), null,
            Modifier.fillMaxSize().blur((18f * (1f - progress.value)).dp)
                .graphicsLayer { alpha = 1f - progress.value },
            contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
    }
}

package dev.pocketagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.pocketagent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Import to private immutable storage: picker grants can expire; history keeps its old avatar. */
internal suspend fun importAgentAvatar(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = 256f / maxOf(info.size.width, info.size.height).coerceAtLeast(256)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片，请选择其他图片" }
        val opts = BitmapFactory.Options().apply {
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 512) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("无法读取图片，请选择其他图片")
    }
    val directory = File(context.filesDir, "agent-avatars").apply { mkdirs() }
    val file = File(directory, "${UUID.randomUUID()}.png")
    try {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        file.absolutePath
    } catch (error: Exception) {
        file.delete()
        throw error
    } finally {
        bitmap.recycle()
    }
}

@Composable
internal fun AgentAvatar(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (path.isBlank()) null else runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) Image(painterResource(R.drawable.ic_launcher), null, modifier.clip(CircleShape))
    else Image(image, null, modifier.clip(CircleShape), contentScale = ContentScale.Crop)
}

package dev.pocketagent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.NonCancellable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pocketagent.data.StudioArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class StudioPreview(val bitmap: Bitmap, val soft: Bitmap, val width: Int, val height: Int, val bytes: Long)

/** Image-specific presentation; originals are exported unchanged, previews are sampled off main. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioArtworkView(artwork: StudioArtwork, enabled: Boolean, feedback: SnackbarHostState,
    reveal: Boolean = false, onReuse: (StudioArtwork) -> Unit) {
    var revealed by rememberSaveable(artwork.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = feedback
    var fullscreen by rememberSaveable(artwork.id) { mutableStateOf(false) }
    var details by rememberSaveable(artwork.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var loadError by remember(artwork.path) { mutableStateOf<String?>(null) }
    val preview by produceState<StudioPreview?>(null, artwork.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(artwork.path)
                require(file.isFile) { "原图文件已移走或删除" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
                val options = BitmapFactory.Options()
                options.inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("图片解码失败")
                val scale = 12f / maxOf(bitmap.width, bitmap.height)
                val soft = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                StudioPreview(bitmap, soft, bounds.outWidth, bounds.outHeight, file.length())
            }.onFailure { loadError = it.message ?: "图片不可用" }.getOrNull()
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(artwork.attachment().mimeType)) { uri ->
        exporting = false
        if (uri != null) {
            saving = true
            scope.launch(NonCancellable) {
                // A user-confirmed export must finish even if this lazy-list item leaves composition.
                val message = try {
                    withContext(Dispatchers.IO) {
                        val source = File(artwork.path)
                        require(source.isFile && source.length() > 0) { "原图已丢失，请检查作品目录" }
                        source.inputStream().use { input ->
                            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                            output.use { input.copyTo(it) }
                        }
                    }
                    "原图已保存到所选位置"
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    "保存失败：${error.message ?: "请选择其他位置"}"
                } finally { saving = false }
                kotlinx.coroutines.withTimeoutOrNull(10_000) { snackbar.showSnackbar(message) }
            }
        }
    }
    val save: () -> Unit = {
        exporting = true
        try { export.launch(File(artwork.path).name) } catch (error: Exception) {
            exporting = false
            scope.launch { snackbar.showSnackbar("无法打开保存位置：${error.message}") }
        }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreen = false }) { Icon(Icons.Outlined.Close, "关闭大图") }
                        Column(Modifier.weight(1f)) {
                            Text("原图预览", style = MaterialTheme.typography.titleMedium)
                            Text(preview?.let { "${it.width} × ${it.height} · 双指缩放" } ?: "读取图片…", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = save, enabled = preview != null && !saving && !exporting) { Text(if (saving) "保存中…" else "保存原图") }
                    }
                    var scale by remember(artwork.id) { mutableFloatStateOf(1f) }
                    var offset by remember(artwork.id) { mutableStateOf(Offset.Zero) }
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).pointerInput(artwork.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) Offset.Zero else {
                                val boundX = size.width * (scale - 1f) / 2f
                                val boundY = size.height * (scale - 1f) / 2f
                                Offset((offset.x + pan.x).coerceIn(-boundX, boundX), (offset.y + pan.y).coerceIn(-boundY, boundY))
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                        preview?.let { image ->
                            Image(image.bitmap.asImageBitmap(), artwork.prompt, Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                            }, contentScale = ContentScale.Fit)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重置缩放") }
                    }
                    SnackbarHost(snackbar)
                }
            }
        }
    }
    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("生成详情", style = MaterialTheme.typography.titleLarge)
                StudioDetailRow("模型", artwork.model)
                StudioDetailRow("请求尺寸", if (artwork.size == "auto") "自动" else artwork.size.replace("x", " × "))
                preview?.let { StudioDetailRow("原图", "${it.width} × ${it.height} · ${String.format(java.util.Locale.ROOT, "%.2f", it.bytes / 1048576.0)} MB") }
                StudioDetailRow("总耗时", "${artwork.elapsedMs / 1000} 秒")
                StudioDetailRow("创建时间", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(artwork.createdAt)))
                HorizontalDivider()
                Text("传输记录", style = MaterialTheme.typography.titleSmall)
                Text(artwork.diagnostics.ifBlank { "此作品暂无传输记录" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(artwork.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = preview?.let { it.width.toFloat() / it.height } ?: studioPreviewRatio(artwork.size)
            val width = minOf(maxWidth, 560.dp, 560.dp * ratio)
            Surface(onClick = { fullscreen = true }, enabled = preview != null, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.width(width).aspectRatio(ratio), contentAlignment = Alignment.Center) {
                    val image = preview
                    if (image != null) StudioImageReveal(image.bitmap, image.soft, artwork.prompt,
                        animate = reveal && !revealed, onRevealed = { revealed = true })
                    else if (loadError != null) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.BrokenImage, null)
                            Text(loadError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = save, enabled = preview != null && !saving && !exporting) {
                Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (saving) "保存中…" else "保存原图")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onReuse(artwork) }, enabled = enabled) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("复用创作")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "作品操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("查看大图") }, enabled = preview != null, onClick = { menu = false; fullscreen = true })
                    DropdownMenuItem(text = { Text("复制提示词") }, onClick = {
                        menu = false
                        clipboard.setText(AnnotatedString(artwork.prompt))
                        scope.launch { snackbar.showSnackbar("提示词已复制") }
                    })
                    DropdownMenuItem(text = { Text("生成详情") }, onClick = { menu = false; details = true })
                }
            }
        }
        Text(preview?.let { "${it.width} × ${it.height} · ${artwork.model} · ${artwork.elapsedMs / 1000} 秒" } ?: artwork.model,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StudioDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

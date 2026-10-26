package dev.pocketagent.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONArray

data class EditorDiagnostic(
    val line: Int,
    val column: Int,
    val message: String,
    val replacement: String? = null,
)

object EditorDiagnosticParser {
    fun parse(output: String, lintPath: String): List<EditorDiagnostic> {
        val clean = output.replace(lintPath, "当前文件").trim()
        if (clean.isBlank()) return emptyList()
        val lines = clean.lines()
        val diagnostics = mutableListOf<EditorDiagnostic>()

        val compilerPattern = Regex(
            """^当前文件:(\d+):(\d+):\s*(?:error|warning|note):\s*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        compilerPattern.findAll(clean).forEach { match ->
            diagnostics += EditorDiagnostic(
                line = match.groupValues[1].toIntOrNull() ?: 1,
                column = match.groupValues[2].toIntOrNull() ?: 1,
                message = match.groupValues[3].trim().take(500),
            )
        }

        val pythonStart = Regex(
            """^File ["']当前文件["'], line (\d+)(?:, column (\d+))?""",
            RegexOption.IGNORE_CASE,
        )
        lines.forEachIndexed { index, raw ->
            val match = pythonStart.find(raw.trim()) ?: return@forEachIndexed
            diagnostics += EditorDiagnostic(
                line = match.groupValues[1].toIntOrNull() ?: 1,
                column = match.groupValues[2].toIntOrNull() ?: 1,
                message = relevantMessageAfter(lines, index),
            )
        }

        val bareLocation = Regex("""^当前文件:(\d+):(\d+)\s*$""")
        lines.forEachIndexed { index, raw ->
            val match = bareLocation.find(raw.trim()) ?: return@forEachIndexed
            diagnostics += EditorDiagnostic(
                line = match.groupValues[1].toIntOrNull() ?: 1,
                column = match.groupValues[2].toIntOrNull() ?: 1,
                message = relevantMessageAfter(lines, index),
            )
        }

        val shellMessage = Regex(
            """^当前文件:\s*(\d+):\s*(?!(?:\d+:|\d+\s*$))(.+)$""",
            RegexOption.MULTILINE,
        )
        shellMessage.findAll(clean).forEach { match ->
            val message = match.groupValues[2]
                .trim()
                .replaceFirst(
                    Regex("""^(?:error|warning|note):\s*""", RegexOption.IGNORE_CASE),
                    "",
                )
            diagnostics += EditorDiagnostic(
                line = match.groupValues[1].toIntOrNull() ?: 1,
                column = 1,
                message = message.take(500),
            )
        }

        val distinct = diagnostics
            .filter { it.message.isNotBlank() }
            .distinctBy { it.line to it.column to it.message }
            .take(50)
        if (distinct.isNotEmpty()) return distinct

        val line = Regex("""line\s+(\d+)""")
            .find(clean)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Regex(""":\s*(\d+)(?::\s*(\d+))?""")
                .find(clean)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            ?: 1
        val column = Regex("""column\s+(\d+)""")
            .find(clean)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Regex(""":\s*\d+:\s*(\d+)""")
                .find(clean)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            ?: 1
        val rawMessage = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull {
                it.contains("SyntaxError", ignoreCase = true) ||
                    it.contains("IndentationError", ignoreCase = true) ||
                    it.contains("TabError", ignoreCase = true) ||
                    it.contains("syntax error", ignoreCase = true) ||
                    it.contains("Unexpected token", ignoreCase = true) ||
                    it.contains("expected", ignoreCase = true)
            }
            ?: lines.lastOrNull { it.isNotBlank() }.orEmpty()
        val message = rawMessage.replace(
            Regex("""^当前文件:\s*\d+(?::\s*\d+)?:\s*"""),
            "",
        )
        return listOf(
            EditorDiagnostic(
                line = line,
                column = column,
                message = message.take(500).ifBlank { "语法检查失败" },
            ),
        )
    }

    private fun relevantMessageAfter(lines: List<String>, startIndex: Int): String {
        val following = lines.drop(startIndex + 1).map { it.trim() }.filter { it.isNotEmpty() }
        val syntaxError = following.firstOrNull {
            it.contains("SyntaxError", ignoreCase = true) ||
                it.contains("IndentationError", ignoreCase = true) ||
                it.contains("TabError", ignoreCase = true)
        }
        val preferred = syntaxError ?: following.firstOrNull {
            it.contains("error", ignoreCase = true) ||
                it.contains("expected", ignoreCase = true) ||
                it.contains("unexpected", ignoreCase = true)
        }
        return (preferred ?: following.firstOrNull { it != "^" })
            ?.take(500)
            .orEmpty()
            .ifBlank { "语法检查失败" }
    }
}

class CodeEditorController internal constructor(
    private val webView: WebView,
) {
    fun focus() {
        webView.evaluateJavascript("PocketEditor.focus();", null)
    }

    fun showCompletions() {
        webView.evaluateJavascript("PocketEditor.showCompletions();", null)
    }

    fun openSearch() {
        webView.evaluateJavascript("PocketEditor.openSearch();", null)
    }

    fun undo() {
        webView.evaluateJavascript("PocketEditor.undo();", null)
    }

    fun redo() {
        webView.evaluateJavascript("PocketEditor.redo();", null)
    }

    fun goTo(line: Int, column: Int) {
        webView.evaluateJavascript(
            "PocketEditor.goTo(${line.coerceAtLeast(1)}, ${column.coerceAtLeast(1)});",
            null,
        )
    }

    fun applyFix(line: Int, column: Int, replacement: String) {
        webView.evaluateJavascript(
            "PocketEditor.applyFix(" +
                "${line.coerceAtLeast(1)}, " +
                "${column.coerceAtLeast(1)}, " +
                "${JSONObject.quote(replacement)}" +
                ");",
            null,
        )
    }

    fun getContent(onResult: (String) -> Unit) {
        webView.evaluateJavascript(
            "PocketEditor.flush(); PocketEditor.getValue();",
        ) { encoded ->
            val value = runCatching {
                JSONTokener(encoded).nextValue() as String
            }.getOrDefault("")
            onResult(value)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodeEditorWebView(
    path: String,
    initialContent: String,
    language: SyntaxLanguage,
    diagnostics: List<EditorDiagnostic>,
    onContentChanged: (String) -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
    onCursorChanged: (Int, Int) -> Unit,
    onHistoryStateChanged: (Boolean, Boolean) -> Unit,
    onControllerReady: (CodeEditorController) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentContentChanged by rememberUpdatedState(onContentChanged)
    val currentDirtyChanged by rememberUpdatedState(onDirtyChanged)
    val currentCursorChanged by rememberUpdatedState(onCursorChanged)
    val currentHistoryStateChanged by rememberUpdatedState(onHistoryStateChanged)
    val currentControllerReady by rememberUpdatedState(onControllerReady)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val context = LocalContext.current
    val webView = remember(path) {
        WebView(context).apply {
            setBackgroundColor(Color.rgb(9, 13, 18))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
    }
    LaunchedEffect(diagnostics) {
        val payload = JSONArray().apply {
            diagnostics.forEach { diagnostic ->
                put(
                    JSONObject().apply {
                        put("line", diagnostic.line.coerceAtLeast(1) - 1)
                        put("ch", diagnostic.column.coerceAtLeast(1) - 1)
                        put("message", diagnostic.message)
                        diagnostic.replacement?.let { put("replacement", it) }
                        put("severity", "error")
                    },
                )
            }
        }
        webView.evaluateJavascript(
            "if (window.PocketEditor) PocketEditor.setDiagnostics(${payload});",
            null,
        )
    }

    val controller = remember(webView) { CodeEditorController(webView) }
    val bridge = remember(webView) {
        object {
            @JavascriptInterface
            fun onReady() {
                mainHandler.post {
                    webView.evaluateJavascript(
                        "PocketEditor.setMode(${JSONObject.quote(language.name.lowercase())});",
                        null,
                    )
                    webView.evaluateJavascript(
                        "PocketEditor.setValue(${JSONObject.quote(initialContent)});",
                        null,
                    )
                    currentControllerReady(controller)
                }
            }

            @JavascriptInterface
            fun onContentChanged(text: String) {
                mainHandler.post { currentContentChanged(text) }
            }

            @JavascriptInterface
            fun onDirtyChanged(dirty: Boolean) {
                mainHandler.post { currentDirtyChanged(dirty) }
            }

            @JavascriptInterface
            fun onCursorChanged(line: Int, column: Int) {
                mainHandler.post { currentCursorChanged(line, column) }
            }

            @JavascriptInterface
            fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
                mainHandler.post {
                    currentHistoryStateChanged(canUndo, canRedo)
                }
            }
        }
    }

    AndroidView(
        factory = {
            webView.apply {
                addJavascriptInterface(bridge, "PocketEditorBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        val mode = JSONObject.quote(language.name.lowercase())
                        val text = JSONObject.quote(initialContent)
                        view.evaluateJavascript(
                            "if (window.PocketEditor) {" +
                                "PocketEditor.setMode($mode); " +
                                "PocketEditor.setValue($text);" +
                                "}",
                            null,
                        )
                        currentControllerReady(controller)
                        view.postDelayed({
                            view.evaluateJavascript(
                                """
                                JSON.stringify({
                                  ready: typeof PocketEditor,
                                  valueLength: window.PocketEditor ? PocketEditor.getValue().length : -1,
                                  lineCount: document.querySelector('.CodeMirror') ? 'present' : 'missing',
                                  codeLength: document.querySelector('.CodeMirror-code')?.innerText?.length ?? -1,
                                  rect: document.querySelector('.CodeMirror')?.getBoundingClientRect()?.toJSON?.() ?? null,
                                  color: document.querySelector('.CodeMirror') ?
                                    getComputedStyle(document.querySelector('.CodeMirror')).color : null,
                                  display: document.querySelector('.CodeMirror') ?
                                    getComputedStyle(document.querySelector('.CodeMirror')).display : null
                                })
                                """.trimIndent(),
                            ) { result -> Log.d("PocketEditor", "DOM $result") }
                        }, 800)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(
                            "PocketEditor",
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                loadUrl("file:///android_asset/editor/editor.html")
            }
        },
        modifier = modifier.onSizeChanged { size ->
            if (size.width > 0 && size.height > 0) {
                webView.post {
                    webView.evaluateJavascript(
                        "if (window.PocketEditor) PocketEditor.refresh();",
                        null,
                    )
                }
            }
        },
        update = {
            it.setBackgroundColor(Color.rgb(9, 13, 18))
        },
    )

    DisposableEffect(webView) {
        onDispose {
            webView.removeJavascriptInterface("PocketEditorBridge")
            webView.stopLoading()
            webView.destroy()
        }
    }
}

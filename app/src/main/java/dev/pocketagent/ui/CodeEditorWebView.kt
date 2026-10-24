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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

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
    onContentChanged: (String) -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
    onCursorChanged: (Int, Int) -> Unit,
    onControllerReady: (CodeEditorController) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentContentChanged by rememberUpdatedState(onContentChanged)
    val currentDirtyChanged by rememberUpdatedState(onDirtyChanged)
    val currentCursorChanged by rememberUpdatedState(onCursorChanged)
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
                            "PocketEditor.setMode($mode); PocketEditor.setValue($text);",
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
        modifier = modifier,
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

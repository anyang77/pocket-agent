package dev.pocketagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.pocketagent.data.AuditLog
import dev.pocketagent.runtime.RootDetector
import dev.pocketagent.runtime.RuntimeKind
import dev.pocketagent.runtime.TermuxRuntime
import java.io.File

class TerminalSessionManager(
    context: Context,
    private val runtime: TermuxRuntime,
    private val workspace: () -> File,
    private val auditLog: AuditLog,
    private val rootDetector: RootDetector = RootDetector(),
) : TerminalSessionClient, TerminalViewClient {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("terminal_ui", Context.MODE_PRIVATE)
    private var fontSizeSp = run {
        val oldDefault = preferences.getInt("font_size_sp", 14)
        if (oldDefault == 12 && !preferences.getBoolean("font_size_migrated_v2", false)) {
            preferences.edit()
                .putInt("font_size_sp", 14)
                .putBoolean("font_size_migrated_v2", true)
                .apply()
            14
        } else {
            oldDefault.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        }
    }
    private var view: TerminalView? = null
    private var session: TerminalSession? = null
    private val terminalInput = StringBuilder()
    private var runtimeKind = RuntimeKind.TERMUX

    init {
        configureTerminalColors()
    }

    @Synchronized
    fun currentSession(): TerminalSession {
        val existing = session
        if (existing != null && existing.isRunning) return existing
        val useRoot = runtimeKind == RuntimeKind.ROOT_TERMUX
        val useSystemShell = runtimeKind == RuntimeKind.SYSTEM_SHELL
        val su = if (useRoot) rootDetector.findSuBinary() else null
        val shellPath: String
        val arguments: Array<String>
        if (su != null) {
            shellPath = su.absolutePath
            arguments = arrayOf("su", "-c", buildRootShellCommand(workspace()))
        } else if (useSystemShell) {
            shellPath = "/system/bin/sh"
            arguments = arrayOf("sh", "-i")
        } else {
            val terminalShell = runtime.terminalShell()
            shellPath = terminalShell.first
            arguments = terminalShell.second
        }
        val sessionDirectory = if (su != null) runtime.home else workspace()
        val created = TerminalSession(
            shellPath,
            sessionDirectory.absolutePath,
            arguments,
            runtime.environmentArray(),
            5_000,
            this,
        )
        session = created
        auditLog.append(
            "terminal",
            "启动 Shell",
            "cwd=${workspace().absolutePath} runtime=$runtimeKind",
        )
        view?.attachSession(created)
        return created
    }

    @Synchronized
    fun attach(newView: TerminalView) {
        view = newView
        newView.setTerminalViewClient(this)
        applyFontSize(newView)
        newView.setTypeface(Typeface.MONOSPACE)
        newView.setPadding(10, 10, 10, 10)
        newView.isFocusable = true
        newView.isFocusableInTouchMode = true
        newView.attachSession(currentSession())
    }

    @Synchronized
    fun detach(oldView: TerminalView) {
        if (view === oldView) view = null
    }

    fun restart() {
        flushTerminalCommand()
        session?.finishIfRunning()
        session = null
        auditLog.append("terminal", "重启 Shell", "用户请求重启")
        view?.let { attach(it) }
    }

    @Synchronized
    fun setRuntimeKind(kind: RuntimeKind) {
        if (runtimeKind == kind) return
        runtimeKind = kind
        auditLog.append("terminal", "切换执行环境", "runtime=$kind")
        if (session != null) {
            session?.finishIfRunning()
            session = null
            view?.let { attach(it) }
        }
    }

    @Synchronized
    fun setUseRoot(enabled: Boolean) {
        setRuntimeKind(if (enabled) RuntimeKind.ROOT_TERMUX else RuntimeKind.TERMUX)
    }

    fun increaseFont() {
        fontSizeSp = (fontSizeSp + 1).coerceAtMost(MAX_FONT_SP)
        preferences.edit().putInt("font_size_sp", fontSizeSp).apply()
        view?.let(::applyFontSize)
    }

    fun decreaseFont() {
        fontSizeSp = (fontSizeSp - 1).coerceAtLeast(MIN_FONT_SP)
        preferences.edit().putInt("font_size_sp", fontSizeSp).apply()
        view?.let(::applyFontSize)
    }

    private fun applyFontSize(target: TerminalView) {
        target.setTextSize(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp.toFloat(),
                target.resources.displayMetrics,
            ).toInt(),
        )
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.post { view?.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        flushTerminalCommand()
        auditLog.append(
            "terminal",
            "Shell 结束",
            "exit=${finishedSession.getExitStatus()}",
        )
        view?.post { view?.onScreenUpdated() }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
        if (!text.isNullOrEmpty()) {
            auditLog.append("terminal", "粘贴", "chars=${text.length}")
            view?.mEmulator?.paste(text)
        }
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.setTerminalCursorBlinkerState(state, false)
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "error", e)
    }

    override fun onScale(scale: Float): Float {
        when {
            scale >= 1.1f -> increaseFont()
            scale <= 0.9f -> decreaseFont()
            else -> return scale
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        view?.let {
            it.requestFocus()
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            flushTerminalCommand()
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        when {
            codePoint == '\r'.code || codePoint == '\n'.code -> flushTerminalCommand()
            codePoint == 8 || codePoint == 127 -> {
                if (terminalInput.isNotEmpty()) terminalInput.deleteCharAt(terminalInput.lastIndex)
            }
            codePoint >= 32 && !ctrlDown -> terminalInput.appendCodePoint(codePoint)
            ctrlDown && codePoint in 1..26 -> terminalInput.append("^").append(('A'.code + codePoint - 1).toChar())
        }
        return false
    }

    override fun onEmulatorSet() {
        view?.setTerminalCursorBlinkerState(true, true)
    }

    private fun flushTerminalCommand() {
        val command = terminalInput.toString().trim()
        terminalInput.clear()
        if (command.isNotEmpty()) {
            auditLog.append("terminal", "手动命令", command)
        }
    }

    private fun buildRootShellCommand(workspace: File): String {
        val environment = runtime.environmentArray().joinToString(" ") { shellQuote(it) }
        return "cd -- ${shellQuote(workspace.absolutePath)} && " +
            "exec ${runtime.prefix.absolutePath}/bin/env -i $environment " +
            runtime.shellInvocation("${runtime.bash.absolutePath} --norc --noprofile -i")
    }

    private fun configureTerminalColors() {
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        val base = intArrayOf(
            0xFF0B1016.toInt(),
            0xFFFF7B72.toInt(),
            0xFF7EE787.toInt(),
            0xFFE3B341.toInt(),
            0xFF79C0FF.toInt(),
            0xFFD2A8FF.toInt(),
            0xFF56D4DD.toInt(),
            0xFFDCE6F0.toInt(),
            0xFF8B949E.toInt(),
            0xFFFFA198.toInt(),
            0xFFA5E8B8.toInt(),
            0xFFF0D58A.toInt(),
            0xFFA5D6FF.toInt(),
            0xFFE2C5FF.toInt(),
            0xFF9BEAF0.toInt(),
            0xFFFFFFFF.toInt(),
        )
        base.copyInto(colors, 0, 0, base.size.coerceAtMost(16))
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFE6EDF3.toInt()
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF090D12.toInt()
        colors[TextStyle.COLOR_INDEX_CURSOR] = 0xFF8BE9C1.toInt()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val MIN_FONT_SP = 10
        private const val MAX_FONT_SP = 22
    }
}

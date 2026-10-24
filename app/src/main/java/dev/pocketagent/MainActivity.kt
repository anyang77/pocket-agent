package dev.pocketagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.PocketAgentApp
import dev.pocketagent.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            PocketAgentTheme {
                val viewModel: AppViewModel = viewModel()
                val debugAction = intent.getStringExtra("pocket_agent_action")
                LaunchedEffect(debugAction) {
                    when (debugAction) {
                        "install_runtime" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.SETTINGS)
                            viewModel.installInternalRuntime()
                        }
                        "probe_root" -> viewModel.probeRoot()
                        "run_pwd" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.TERMINAL)
                            viewModel.runTerminal("pwd")
                        }
                        "root_diag" -> viewModel.runRootDiagnostic()
                        "cache_probe" -> viewModel.runCacheProbe()
                        "reasoning_tool_probe" -> viewModel.runReasoningToolProbe()
                        "toolchain_diag" -> viewModel.runToolchainDiagnostic()
                        "run_python" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.TERMINAL)
                            viewModel.runTerminal("python3 hello.py")
                        }
                        "run_python_test" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.TERMINAL)
                            viewModel.runTerminal("python3 -m py_compile hello.py")
                        }
                        "run_node" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.TERMINAL)
                            viewModel.runTerminal("node -e 'console.log(process.version)'")
                        }
                        "run_clang" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.TERMINAL)
                            viewModel.runTerminal("clang hello.c -o hello && ./hello")
                        }
                        "device_info" -> viewModel.runDeviceInfoPreview()
                        "agent_device_info" -> {
                            viewModel.selectTab(dev.pocketagent.ui.AppTab.AGENT)
                            viewModel.sendChat("读取我的手机信息，并总结型号、Android 版本、CPU、内存和存储。")
                        }
                        "mock_agent" -> viewModel.runMockAgent()
                    }
                }
                PocketAgentApp(viewModel)
            }
        }
    }
}

package dev.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    val listState = rememberLazyListState()
    var secondsRemaining by remember { mutableIntStateOf(READING_SECONDS) }
    var reachedBottom by remember { mutableStateOf(false) }
    val accepted = secondsRemaining == 0 && reachedBottom

    LaunchedEffect(listState) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(Unit) {
        repeat(READING_SECONDS) {
            delay(1_000)
            secondsRemaining--
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1 &&
                listState.canScrollForward.not()
        }.distinctUntilChanged().collect { isAtBottom ->
            reachedBottom = reachedBottom || isAtBottom
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 28.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item { WelcomeTitle() }
                item {
                    WelcomeSection(
                        icon = Icons.Outlined.Code,
                        title = "应用用途",
                        lines = listOf(
                            "Pocket Agent 用于浏览和编辑 Android 项目文件、运行终端命令、构建测试及管理 Git。",
                            "你也可以连接兼容 OpenAI 接口的模型服务，进行代码分析、问题排查和任务执行。",
                        ),
                    )
                }
                item {
                    WelcomeSection(
                        icon = Icons.Outlined.WarningAmber,
                        title = "使用前须知",
                        lines = listOf(
                            "文件访问用于读写项目，通知用于显示 Agent、构建和后台任务的进度。权限只会在你点击对应授权按钮后申请。",
                            "设备存在并已授权 Root 时会自动启用。普通开发命令仍运行在独立的 Termux 用户环境中，不会因为 Root 可用而自动提权。",
                            "AI 生成的命令和代码可能出错。执行删除、写入或系统操作前请核对内容；重要项目应先提交 Git 或做好备份。",
                        ),
                    )
                }
                item {
                    WelcomeSection(
                        icon = Icons.Outlined.Lock,
                        title = "数据与隐私",
                        lines = listOf(
                            "对话、项目文件和操作记录保存在设备上，API Key 使用 Android Keystore 加密保存。",
                            "调用 AI 时，你输入的内容、相关代码和工具输出会发送到你所配置的模型服务。请仅处理你有权使用的数据，并确认服务可信。",
                        ),
                    )
                }
                item { WelcomeAgreementNotice() }
            }

            WelcomeActionBar(
                enabled = accepted,
                secondsRemaining = secondsRemaining,
                reachedBottom = reachedBottom,
                onContinue = onContinue,
            )
        }
    }
}

@Composable
private fun WelcomeTitle() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.size(23.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "Pocket Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "版本 0.1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.26f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "开始使用 Pocket Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "首次启动需要确认应用用途、相关权限以及数据处理方式，然后完成环境和模型设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 23.sp,
            )
        }
    }
}

@Composable
private fun WelcomeSection(
    icon: ImageVector,
    title: String,
    lines: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(21.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun WelcomeAgreementNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            "勾选并点击“继续”，即表示你已阅读并同意以上应用用途、使用须知和数据与隐私说明。",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun WelcomeActionBar(
    enabled: Boolean,
    secondsRemaining: Int,
    reachedBottom: Boolean,
    onContinue: () -> Unit,
) {
    val status = when {
        secondsRemaining > 0 -> "请阅读 $secondsRemaining 秒"
        !reachedBottom -> "请滑动阅读至底部"
        else -> "已阅读，可以继续"
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    status,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .height(42.dp)
                        .semantics { contentDescription = status },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    ),
                    contentPadding = PaddingValues(horizontal = 15.dp),
                ) {
                    Text(
                        if (secondsRemaining > 0) "${secondsRemaining} 秒" else "继续",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (enabled) {
                        Spacer(Modifier.padding(start = 5.dp))
                        Icon(
                            Icons.Outlined.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val READING_SECONDS = 5

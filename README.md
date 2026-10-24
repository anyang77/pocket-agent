# Pocket Agent

Android 上的移动 AI 编码工作区原型。目标不是复刻桌面 IDE，而是把下面这条闭环做稳：

```text
读取项目 -> 修改文件 -> 运行构建/测试 -> 读取错误 -> 继续修复
```

当前版本是第一个可安装里程碑：

- Jetpack Compose 手机界面
- `/sdcard` 工作区、目录搜索、文件/目录增删改和安全确认删除
- CodeMirror 代码编辑器：多色语法高亮、行号、括号匹配、补全、搜索、替换和全部替换
- Shell、Python 和 Node 脚本可在编辑器内直接运行并查看输出与退出码
- Termux `TerminalView` + PTY 交互式终端，字号可调
- 自动探测 `su`；拿到 Root 后终端、Shell 命令和系统只读访问会自动使用 Root
- OpenAI-compatible 模型接入
- 默认接入 DeepSeek：`https://api.deepseek.com/v1` + `deepseek-chat`
- Agent 工具调用：列文件、读写工作区、读取设备信息、只读访问系统路径、运行完整 Shell
- Agent 支持多步任务计划、全文搜索和精确局部编辑，减少整文件重写
- Agent 权限模式：只读 / 逐项确认 / 自动执行，权限持久化
- 逐项确认模式下的写文件、系统读取和命令授权弹窗
- 完整 Android shell：管道、重定向、命令串联、系统包管理和 root 工具均可执行
- 完整本地审计日志：模型请求、工具调用、授权决定、文件哈希、命令退出码、终端命令
- 首次启动三步引导：必需权限、运行环境、模型接入
- 多会话本地历史：自动标题、切换、删除、重启恢复和上下文恢复
- 类 dsh 的聊天布局：Markdown/代码块、折叠执行过程、自动跟随最新消息
- 持续 Agent 循环：不设固定轮数，用户可随时停止；接近模型上下文上限时自动生成检查点并继续
- 顶栏显示当前请求上下文进度；Prompt 缓存按最新请求、本次任务和当前会话分别统计并持久化
- 模型菜单自动读取/推断思考等级并发送 `reasoning_effort`，支持自动、极低、低、中、高、极高和最高
- 默认工作区为 `/sdcard`，在文件页直接用目录选择器切换；文件面板、编辑器、Agent 和终端同步切换
- 首次引导和设置页可配置 128K、200K、256K、512K 或 1M 上下文窗口
- 可视化 Git：初始化/克隆、分支、暂存、提交、diff、历史和拉取/推送
- 本地仓库列表、多 GitHub HTTPS 账号、SSH Key 账号和多仓库快速切换
- API Key 使用 Android Keystore + AES/GCM 保存
- App 自带精简 Termux bionic 基础用户态，不依赖外部 Termux、不需要 Ubuntu/PRoot
- 首次初始化通过 Termux apt 仓库按需下载 Python、pip、Node、npm、clang/LLVM 和 make
- App 使用 `targetSdk=30` 和 `MANAGE_EXTERNAL_STORAGE`，首次引导直接进入系统全盘文件访问授权
- 内置 Termux 通过 system-linker 和 `termux-exec` 解决 Android 10+ 的 app-data 执行限制

## 当前限制

- 检测到 Root 并获 root 管理器授权后，Agent 和终端会自动使用 Root；权限审批与审计仍然生效。
- 该构建面向侧载、F-Droid 或官网分发；使用全盘文件和动态工具链，不适合直接提交 Google Play。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0：

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew :app:assembleDebug
```

私有 GitHub 仓库优先使用 App 内生成的 SSH Key：复制 Public Key 到对应 GitHub 账号一次，
之后该账号的所有仓库共用。HTTPS Token 仅在 Clone 对话框中按需展开。

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 下一阶段

1. 增加任务级回滚和测试结果解析。
2. 继续完善编辑器的大文件索引与语言服务。
3. 完善 root 设备上的系统目录工具和操作审计。

## 许可证

内置用户态来自 `termux-packages` bootstrap，并按上游各组件的许可证分发。
当前产品方向按 GPL 兼容方式开发。

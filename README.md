# Pocket Agent

Android 上的移动 AI 编码工作区原型。目标不是复刻桌面 IDE，而是把下面这条闭环做稳：

```text
读取项目 -> 修改文件 -> 运行构建/测试 -> 读取错误 -> 继续修复
```

当前版本是第一个可安装里程碑：

- Jetpack Compose 手机界面
- `/sdcard` 工作区、目录搜索、文件/目录增删改和安全确认删除
- CodeMirror 代码编辑器：VS Code Dark+ 风格高亮、行号、括号匹配、输入自动补全、搜索/替换和撤销/重做
- 上下文补全支持关键字、函数、类型、文档符号、Shell 变量、对象属性和代码片段，并提供模糊匹配与说明
- 编辑器问题面板支持多错误定位、悬停提示、行列跳转和全角标点一键修复
- Shell 使用 ShellCheck + 解释器解析检查；Python、JavaScript、Java、JSON、C/C++ 使用对应真实工具检查
- Shell、Python、Node 和 Java 可在编辑器内直接运行并查看输出与退出码
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
- 上下文压缩会显示独立进度卡、已等待时间和停止按钮；压缩模型不可用时自动生成本地兜底检查点
- 模型网络错误和 HTTP 429/5xx 自动退避重试，并实时显示重试次数与服务端状态
- 顶栏显示当前请求上下文进度；Prompt 缓存按最新请求、本次任务和当前会话分别统计并持久化
- 模型菜单自动读取/推断思考等级并发送 `reasoning_effort`，支持自动、极低、低、中、高、极高和最高
- 默认工作区为 `/sdcard`，在文件页直接用目录选择器切换；文件面板、编辑器、Agent 和终端同步切换
- 首次引导和设置页可配置 128K、200K、256K、512K 或 1M 上下文窗口
- 可视化 Git：初始化/克隆、分支、暂存、提交、diff、历史和拉取/推送
- Git 页面区分本地与远程：本地负责改动/暂存/提交，远程负责单账号登录、仓库浏览和同步
- GitHub 浏览器设备授权登录：无需 SSH Key 或手动粘贴 Token，登录一次后 clone/pull/push 自动复用
- API Key 使用 Android Keystore + AES/GCM 保存
- App 自带精简 Termux bionic 基础用户态，不依赖外部 Termux、不需要 Ubuntu/PRoot
- 首次初始化通过内置 apt 用户态一次性下载 Python、Node、Java/JDK、ShellCheck、clang/LLVM、Git 和 SSH
- Java 使用 `javac` 解析阶段检查和 `java --source 17` 单文件运行，不依赖外部 Termux 或系统 JDK
- 初始化会修复应用私有工具链的所有权与 SELinux 标签，工具链就绪后 Git 不再重复安装
- apt 初始化优先使用清华 TUNA，失败时自动回退 BFSU、USTC、NJU 和 Termux 官方源
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

私有 GitHub 仓库通过 App 内浏览器登录授权。登录后直接列出账号可访问的仓库，
选择仓库即可克隆；克隆、拉取和推送自动复用当前账号。

### GitHub OAuth Device Flow

发布构建需要创建一个 GitHub OAuth App，并在 OAuth App 设置中启用 Device Flow。
Client ID 是公开配置，Client Secret 不进入 APK。构建时传入：

```bash
POCKET_GITHUB_CLIENT_ID=你的ClientID ./gradlew :app:assembleDebug
```

用户点击 Git 页的“使用 GitHub 登录”后，App 会打开系统浏览器，显示一次性验证码，
授权成功后使用 Android Keystore 保存 Token。
如果调试包没有内置 Client ID，可在 Git > 管理 的 GitHub 登录卡片中填写一次。

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 下一阶段

1. 增加任务级变更快照与测试结果解析。
2. 继续完善跨文件语言服务、定义跳转和引用查找。
3. 完善 root 设备上的系统目录工具和操作审计。

## 许可证

内置用户态来自 `termux-packages` bootstrap，并按上游各组件的许可证分发。
当前产品方向按 GPL 兼容方式开发。

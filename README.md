# CodeMate

Android 上的移动 AI 编码工作区原型。目标不是复刻桌面 IDE，而是把下面这条闭环做稳：

```text
读取项目 -> 修改文件 -> 运行构建/测试 -> 读取错误 -> 继续修复
```

当前版本是第一个可安装里程碑：

- Jetpack Compose 手机界面
- `/sdcard` 工作区、目录搜索、文件/目录增删改和安全确认删除
- CodeMirror 代码编辑器：支持 Rust / Cargo TOML、Windows 批处理（.bat / .cmd）等语言，VS Code Dark+ 风格高亮、行号、括号匹配、输入自动补全、搜索/替换和撤销/重做
- 上下文补全支持关键字、函数、类型、文档符号、Shell 变量、对象属性和代码片段，并提供模糊匹配与说明
- 编辑器问题面板支持多错误定位、悬停提示、行列跳转和全角标点一键修复
- Shell 使用 ShellCheck + 解释器解析检查；Python、JavaScript、Java、JSON、C/C++ 使用对应真实工具检查
- Rust 支持关键字、常用类型和代码片段补全；初始化自动安装 `rustfmt`，按 Cargo edition 检查当前缓冲区语法，不解析依赖、不修改文件
- Shell、Python、Node 和 Java 可在编辑器内直接运行并查看输出与退出码
- Windows 批处理支持命令、注释、标签、变量高亮及代码片段补全；Android 内置环境不提供 cmd.exe，暂不支持批处理运行或语法纠错
- Termux `TerminalView` + PTY 交互式终端，字号可调
- 每次启动后自动检测 `su`；检测到可用 Root 时启用设备管理能力，普通开发命令仍使用独立 Termux 用户环境
- OpenAI-compatible 模型接入
- 默认接入 DeepSeek：`https://api.deepseek.com/v1` + `deepseek-chat`
- Agent 工具调用：列文件、读写工作区、读取设备信息、只读访问系统路径、运行完整 Shell
- Agent 支持多步任务计划、全文搜索和精确局部编辑，减少整文件重写
- Agent 权限模式：只读 / 逐项确认 / 自动执行，权限持久化
- 逐项确认模式下的写文件、系统读取和命令授权弹窗
- 完整 Android shell：管道、重定向、命令串联、系统包管理和 root 工具均可执行
- 完整本地审计日志：模型请求、工具调用、授权决定、文件哈希、命令退出码、终端命令
- 首次启动三步引导：必需权限、运行环境、模型接入
- 欢迎页说明软件用途、权限、AI 数据去向及备份注意事项；阅读满 5 秒并滑至底部后可继续，进入首次设置
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
- 首次初始化通过内置 apt 用户态一次性下载 Python、Node、Java/JDK、Rust（rustc / cargo / rustfmt）、ShellCheck、clang/LLVM、Git 和 SSH
- 已初始化的旧环境会在下次启动时自动补齐缺失的 Rust 工具，无需重装基础环境
- Java 使用 `javac` 解析阶段检查和 `java --source 17` 单文件运行，不依赖外部 Termux 或系统 JDK
- 初始化会修复应用私有工具链的所有权与 SELinux 标签，工具链就绪后 Git 不再重复安装
- apt 初始化优先使用清华 TUNA，失败时自动回退 BFSU、USTC、NJU 和 Termux 官方源
- App 使用 `targetSdk=35`；点击“去授权”后，通知直接弹系统授权框，共享项目目录打开系统“所有文件访问”页面，授权返回后再初始化工作区
- 内置 Termux 通过 system-linker 和 `termux-exec` 解决 Android 10+ 的 app-data 执行限制

## 当前限制

- 终端提供 Termux 和手机 Shell 两种持久会话，可自行输入 su；Agent 按操作需要选择环境，权限审批与审计仍然生效。
- 该构建面向侧载、F-Droid 或官网分发；使用全盘文件和动态工具链，不适合直接提交 Google Play。

## 输入框命令

点击输入框的「＋」打开更多功能；也可直接发送下列命令（不附带附件）：

- `/compact` 压缩上下文：生成检查点，保留任务信息并释放上下文空间。
- `/review` 审查改动：程序只读采集暂存区、工作区和未跟踪文件的差异，Agent 仅能浏览、搜索与分析，不能写文件或运行命令。大差异会明确标注截断，审查不会假装运行过测试。
- `/plan` 规划模式：切换为只读分析，下一条任务用于生成方案。完成后可继续讨论，或点击「按计划执行」并确认，才恢复当前执行权限。退出规划不会自动执行计划。
- `/init` 项目说明：检查项目并生成或更新根目录 `AGENTS.md`，展示完整变更后确认写入。已有内容先备份到应用私有目录；确认期间文件有变化则拒绝覆盖。后续任务自动读取项目根目录的说明（最多 24000 字符）。

规划和审查在工具层限制能力，不只是添加提示词；受限任务不启动 MCP 或子代理。规划队列保留各自的模式，受限任务结束后队列暂停，避免隐式转为执行。模型、权限、历史等已有入口不在这里重复提供。

## Agent 扩展

设置 > Agent 扩展提供技能、MCP 服务与子代理管理。

- 技能：内置 Android 构建、Termux 环境选择和代码复核流程；支持在应用内创建 Markdown 技能，也自动发现工作区 `.agents/skills/<名称>/SKILL.md` 与 `.codemate/skills/<名称>/SKILL.md`。模型按需读取技能正文，启停可单独设置。
- MCP：支持 Streamable HTTP 与本机 stdio，可手动添加或导入 `mcpServers` JSON 配置。请求头与环境变量使用 Android Keystore 加密保存。测试连接会进行 initialize、工具发现并显示工具清单；任务中工具调用沿用 Agent 权限与审计。
- 子代理：使用当前模型、独立上下文并行读取工作区，默认最多 3 个，可调整或关闭。主 Agent 统一修改文件并收集分析结果。对话可查看每个子任务的进度、结果和 tokens，并单独停止；停止主任务会同时停止子任务。
- 应用显示名称为 CodeMate；沿用原有应用 ID，覆盖安装保留配置、工具链与历史。

MCP 配置示例：

```json
{
  "mcpServers": {
    "remote": {"url": "https://example.com/mcp", "headers": {"Authorization": "Bearer YOUR_TOKEN"}},
    "local": {"command": "npx", "args": ["-y", "YOUR_MCP_PACKAGE"]}
  }
}
```

本机 stdio 使用应用内 Termux，需要先安装对应服务。远程服务使用 HTTPS；本地回环地址支持 HTTP。本版不支持旧版 HTTP+SSE 传输、OAuth 自动登录和 MCP prompts/resources 浏览。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0：

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew :app:assembleDebug
```

私有 GitHub 仓库通过 App 内浏览器登录授权。登录后直接列出账号可访问的仓库，
选择仓库即可克隆；克隆、拉取和推送自动复用当前账号。
Agent 的命令也会复用当前 Git 账号和 SSH 配置：HTTPS 凭据按站点匹配，
GitHub CLI 可直接使用登录态，API 脚本可使用命令环境中的 `GITHUB_TOKEN`。
切换或退出账号后下一条命令立即生效，命令输出中的凭据会被隐藏。

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

### 在线扩展仓库

设置 → Agent 扩展 → 技能 / MCP 服务 → 浏览在线仓库。

- 技能默认使用正文为中文的 [中文技能精选](https://github.com/laolaoshiren/claude-code-skills-zh) 和 [Superpowers 中文版](https://github.com/jnMetaCode/superpowers-zh)。列表中的名称、用途和详情来自仓库原文，仓库 README 可直接在应用内阅读；优先读取 README.zh-CN.md 等中文版本。Anthropic、Vercel 英文源仍可切换，也支持添加其他公开 GitHub 仓库。
- MCP 默认使用 [Awesome-MCP-ZH 中文资源库](https://github.com/yzfly/Awesome-MCP-ZH)，直接读取其中文介绍、分类与备注。选择项目后按 GitHub 来源匹配官方 Registry 配置；查不到兼容配置时提供项目文档，用户可在 MCP 设置中手动添加或导入 JSON。官方 Registry 原始目录仍可切换，支持名称搜索与分页。
- 技能通过 GitHub codeload 读取仓库快照，锁定提交版本并缓存，避免 GitHub 未登录 REST API 的频率限制。安装保留完整技能目录、脚本、参考资料和许可文件；刷新后可更新，管理页可完整卸载。安装不执行附带脚本，Agent 可用 read_skill_resource 读取资料。不调用模型翻译仓库文档。
- MCP 自动配置支持 Streamable HTTP、npm/npx、PyPI/uvx；参数和密钥在添加前填写，密钥由本机 SecretStore 加密保存。本地包需对应运行环境，兼容性和费用以服务提供方为准。暂不自动 OAuth 登录，也不转换 Docker、MCPB、旧 SSE、自定义包注册表。
- 技能遵守各自许可条款；Anthropic 仓库中部分文档技能为 source-available，并非全部采用开源许可。大型仓库、私有仓库暂不支持。

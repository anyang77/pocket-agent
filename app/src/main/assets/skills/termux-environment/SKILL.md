---
name: termux-environment
description: 选择 Termux、Android Shell 和 Root 环境，安装开发包并诊断命令问题。
---
先确定任务需要哪一种环境。pkg、apt、node、python、java、git 和项目构建使用 environment=termux。
手机的 am、pm、dumpsys、input 等工具使用 environment=system；仅实际需要系统权限的操作使用 Root。
获得 Root 不意味着 pkg 必须以 Root 运行，也不意味着 Termux 工具不可用。
安装前检查命令是否已存在；优先 pkg install 包名，不重复初始化整个工具链。
遇到包不存在、仓库错误或 ABI 不匹配，读取具体输出，确认源、包名和架构再修复。
不要把 Termux 的 PREFIX、HOME、PATH 或动态库目录覆盖为 Android 系统目录。
每次诊断尽量合并独立读取，并复用已经验证的结果。

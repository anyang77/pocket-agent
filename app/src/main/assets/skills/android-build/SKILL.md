---
name: android-build
description: 构建和验证 Android / Gradle 项目，定位编译、资源与安装失败。
---
先读取项目的 README、Gradle 配置和现有构建脚本，复用仓库已经可用的构建命令。
在 Termux 环境执行项目工具，不要因为已获得 Root 就把整个构建切到手机 Shell 或 Root。
确认 Java、SDK 和 aapt2 的实际路径；Android ARM 设备可能需要本机 aapt2 覆盖参数。
首次失败优先读取完整的第一条编译错误及相关源码，避免重复整套安装或无关构建。
修改后运行与变更相关的构建或测试，报告真实命令和结果。安装后的交互测试应收集崩溃日志，结束后返回 CodeMate。

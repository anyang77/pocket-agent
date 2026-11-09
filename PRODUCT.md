# CodeMate

<!-- impeccable:product-schema 1 -->

## Platform

android

## Product Purpose

Android 上的编码 Agent 与移动工作区。本次经用户确认扩展独立生图模式，方便在手机上调整提示词与生成参数、查看作品并保存原图。

## Operating Context

现有 Kotlin / Jetpack Compose / Material 3 应用；接入用户已有的 OpenAI-compatible 服务与密钥，保留编码会话、工具链和设置。

## Capabilities and Constraints

- 用户已选择先交付生图模式，视频接口尚未确认，不提供可用视频入口。
- 创作界面与编码界面切换，创作选择不修改 Agent 模型及会话。
- 图片通过专用生成接口提交，不携带编码工具或聊天历史。
- 使用接口支持的参数；参考图、质量及其他高级参数未经确认不可伪装为可用。
- 展示生成等待和耗时、作品历史、原图保存；失败不自动重试，避免潜在重复计费。

## Brand Commitments

保留 CodeMate 名称和已有 Material 3 主题，不改造编码界面的视觉体系。

## Evidence on Hand

`README.md`、现有 Compose 界面、`ImageGenerationClient.kt` 及其网络测试；当前服务有一次成功的专用图片接口调用记录。

## Product Principles

- 创作请求与编码上下文隔离。
- 只展示真实能力，不虚构视频或高级参数支持。
- 本地作品可查看和导出，不自动重复付费请求。

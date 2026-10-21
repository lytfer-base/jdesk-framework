# JDesk Framework

[English](README.md)

JDesk Framework 是基于 Java 17 与 Swing 的桌面应用框架。它借鉴了 IntelliJ Platform 的架构思路（服务、扩展点、插件、消息总线、Action、Disposer 资源树），但不以构建 IDE 为目标。编辑器、预览这类复杂内容放在内嵌的 Chromium WebView（JCEF）里，通过消息桥与 Java 通信。

> **状态**：1.0 之前。框架与一款每天在用的桌面产品一起开发，API 仍可能调整。

## 能力

- **应用内核**：生命周期、服务容器、扩展点、插件加载、消息总线、配置持久化、运行期参数、启动追踪，以及管理资源归属的 `Disposer` 树。
- **桌面外壳**：Swing 主窗口、Action、菜单和工具栏、设置对话框、通知、对话框、浅色与深色主题、SVG 图标、HiDPI 缩放和 EDT 调度。
- **Web 内容**：带 JCEF 后端的 `WebViewService`、JavaScript 与 Java 之间的桥（`WebBridge`），以及一个本地回环 HTTP 服务，负责向 WebView 提供内置页面和沙箱内的文件。
- **存储与文件**：SQLite（含 schema 迁移和一层轻量实体 / DAO）、托管虚拟文件系统（文件身份、目录快照、文件监听）以及本机文件历史。
- **Agent 协议**：ACP 客户端，以及把应用工具开放给本机 AI agent 的内嵌 MCP 服务。

## 模块

| 模块 | 职责 |
| --- | --- |
| `jdesk-framework-core` | 应用内核。除 `com.jdesk.concurrency` 里的 EDT 辅助类外，不依赖 Swing / AWT。 |
| `jdesk-jcef-api` | 只在构建期使用：从 JetBrains Runtime 里抽出 `org.cef` 的类，供 `frame` 编译 JCEF 后端，不进任何发行包。 |
| `jdesk-framework-frame` | Swing 应用层：启动、窗口、Action、设置、主题、WebView、存储、VFS 与历史的实现、ACP 和 MCP。 |
| `jdesk-framework-demo` | 验证框架能力的 Demo 应用；`demo-greeter` 是示例插件。 |

## 环境要求

- **带 JCEF 的 JetBrains Runtime 17**（`jbrsdk_jcef` 17 的构建版本），设为 `JAVA_HOME`。构建和运行 WebView 都需要它：`jdesk-jcef-api` 构建时要读 `jmods/jcef.jmod`。换成普通 JDK，构建会在 `jdesk-jcef-api` 停下，并给出说明。
- **Maven 3.9** 或更新版本。
- **Windows x64**：主要的开发与测试平台，部分桌面集成通过 JNA 调用 Win32 API。其他平台未经测试。

## 构建

```bash
mvn -B verify
```

## 运行 Demo

```powershell
mvn -B package -DskipTests
& "$env:JAVA_HOME\bin\java" -jar jdesk-framework-demo/target/jdesk-framework-demo.jar
```

运行时要用带 JCEF 的 JetBrains Runtime。Demo 覆盖哪些能力见 [jdesk-framework-demo/README.md](jdesk-framework-demo/README.md)。

## WebView 后端

`WebViewService` 按 `jdesk.webview.backend` 设置选择后端，默认 `auto`。本仓库提供 JCEF 后端，它随 JetBrains Runtime 一起分发。取值 `jxbrowser` 对应维护者自己发行版使用的商业后端，不在本仓库里。

## 文档

代码注释用英文。各模块的 README 和 [`docs/`](docs/) 下的设计说明目前用中文书写。Issue 和 Pull Request 用中文或英文都可以。

## 参与贡献

欢迎贡献。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)；第一个 Pull Request 合并之前，需要签署[贡献者许可协议](CLA.md)。

## 安全

发现漏洞请私下报告，见 [SECURITY.md](SECURITY.md)。

## 许可证

本项目以 Apache License 2.0 授权，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

IntelliJ 是 JetBrains s.r.o. 的商标。本项目与 JetBrains 没有关联，也未获其认可。

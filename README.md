# JDesk Framework

[中文说明](README.zh-CN.md)

JDesk Framework is a desktop application framework for Java 17 and Swing. It borrows architectural ideas from the IntelliJ Platform — services, extension points, plugins, a message bus, actions and a disposer tree — without aiming to be an IDE platform. Rich content such as editors and previews runs in an embedded Chromium WebView (JCEF) that talks to Java through a message bridge.

> **Status:** pre-1.0. The framework is developed together with a desktop product that uses it every day, and its APIs may still change.

## Features

- **Application core**: lifecycle, service container, extension points, plugin loading, message bus, persistent configuration, runtime parameters, startup tracing, and a `Disposer` tree for resource ownership.
- **Desktop shell**: Swing main window, actions, menus and toolbars, a settings dialog, notifications, dialogs, light and dark themes, SVG icons, HiDPI scaling and EDT scheduling.
- **Web content**: a `WebViewService` with a JCEF backend, a JavaScript–Java bridge (`WebBridge`), and a loopback HTTP server that serves bundled pages and sandboxed files to the WebView.
- **Storage and files**: SQLite with schema migrations and a small entity/DAO layer, a managed virtual file system with file identity, directory snapshots and file watching, and local file history.
- **Agent protocols**: an ACP client, and an embedded MCP server that exposes application tools to local AI agents.

## Modules

| Module | Purpose |
| --- | --- |
| `jdesk-framework-core` | Application kernel. Apart from the EDT helpers in `com.jdesk.concurrency`, it does not depend on Swing or AWT. |
| `jdesk-jcef-api` | Build-time only. Extracts the `org.cef` classes from the JetBrains Runtime so that `frame` can compile against JCEF. It is never shipped. |
| `jdesk-framework-frame` | Swing application layer: bootstrap, windows, actions, settings, themes, WebView, storage, VFS and history implementations, ACP and MCP. |
| `jdesk-framework-demo` | Demo application that exercises the framework. `demo-greeter` is an example plugin. |

## Requirements

- **JetBrains Runtime 17 with JCEF** (a `jbrsdk_jcef` 17 build) as `JAVA_HOME`. It is needed both to build, because `jdesk-jcef-api` reads `jmods/jcef.jmod`, and to run the WebView. With a plain JDK the build stops at `jdesk-jcef-api` with an explanatory error.
- **Maven 3.9** or newer.
- **Windows x64.** It is the primary development and test platform, and parts of the desktop integration call Win32 APIs through JNA. Other platforms are untested.

## Building

```bash
mvn -B verify
```

## Running the demo

```powershell
mvn -B package -DskipTests
& "$env:JAVA_HOME\bin\java" -jar jdesk-framework-demo/target/jdesk-framework-demo.jar
```

Use the JetBrains Runtime with JCEF to run it. See [jdesk-framework-demo/README.md](jdesk-framework-demo/README.md) for what the demo covers.

## WebView backends

`WebViewService` picks its backend from the `jdesk.webview.backend` setting, `auto` by default. This repository ships the JCEF backend, which comes with the JetBrains Runtime. The `jxbrowser` value refers to a commercial backend used by the maintainer's own distribution; it is not part of this repository.

## Documentation

Code comments are in English. Module READMEs and the design notes under [`docs/`](docs/) are currently written in Chinese. Issues and pull requests in either English or Chinese are welcome.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first; you will need to sign the [Contributor License Agreement](CLA.md) before your first pull request is merged.

## Security

Please report vulnerabilities privately. See [SECURITY.md](SECURITY.md).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

IntelliJ is a trademark of JetBrains s.r.o. This project is not affiliated with or endorsed by JetBrains.

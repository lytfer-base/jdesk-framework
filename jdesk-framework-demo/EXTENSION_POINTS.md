# Demo 扩展点文档

Demo 应用声明了两个扩展点，供内置实现和外部插件实现。所有声明集中在 `src/main/resources/META-INF/app-plugins.xml`，由 frame 层在 `JdeskApplication.init()` 阶段解析注册。

---

## 扩展点一览

| 扩展点名称 | 接口 | 类型 | 说明 |
|---|---|---|---|
| `deskframe.demo.greeting` | `GreetingProvider` | `@FunctionalInterface` | 提供问候语字符串，显示在左侧 Greetings 面板和对话框 |
| `deskframe.demo.toolWindow` | `ToolWindowProvider` | `@FunctionalInterface` | 提供 Swing 组件，嵌入主窗口的工具面板区域 |

---

## 扩展点 1：`deskframe.demo.greeting`

### 接口定义

```java
// src/main/java/com/jdesk/demo/GreetingProvider.java
@FunctionalInterface
public interface GreetingProvider {
    String getGreeting();
}
```

### 使用位置

- **左侧工具面板**：`DemoApp.buildToolWindows()` 收集所有实现，填充 `JList`
- **Tools → Show Greetings 对话框**：`DemoApp.showGreetings()` 列出所有问候语

### 内置实现

| 实现类 | 来源 | 注册方式 | 输出示例 |
|---|---|---|---|
| `builtin.SystemInfoGreetingProvider` | Demo 内置 | `app-plugins.xml` `<extensions>` | `JDesk Framework running on windows 10.0` |
| `builtin.JavaInfoGreetingProvider` | Demo 内置 | `app-plugins.xml` `<extensions>` | `Java 17.0.1 | 8 CPUs` |

### 插件实现

| 实现类 | 插件 | 注册方式 | 输出示例 |
|---|---|---|---|
| `plugin.DemoGreeterExtension` | `com.jdesk.demo.greeter` | `plugin.xml` `<extensions>` | `[Plugin] Hello from the Demo Greeter Plugin v1.0.0! Loaded at 14:32:05` |

### 插件注册方式

在插件的 `META-INF/plugin.xml` 中：

```xml
<extensions>
    <extension point="deskframe.demo.greeting"
               implementation="com.jdesk.demo.plugin.DemoGreeterExtension"/>
</extensions>
```

---

## 扩展点 2：`deskframe.demo.toolWindow`

### 接口定义

```java
// src/main/java/com/jdesk/demo/ToolWindowProvider.java
@FunctionalInterface
public interface ToolWindowProvider {
    JComponent createComponent();
}
```

### 使用位置

- **右侧工具面板**：`DemoApp.buildToolWindows()` 收集实现，通过 `addRightToolWindow("Modules", ...)` 嵌入主窗口

### 内置实现

| 实现类 | 来源 | 注册方式 | 说明 |
|---|---|---|---|
| 匿名 lambda | Demo 内置（`DemoApp.buildToolWindows()`） | 代码中 `registerExtension()` | 输出框架模块清单的 `JTextArea` |

> **注意**：此内置实现目前仍以匿名 lambda 形式在 Java 代码中注册。可参照 `deskframe.demo.greeting` 的模式，提取为独立的 `ModuleStatusToolWindowProvider` 类并迁移到 `app-plugins.xml` 的 `<extensions>` 中。

---

## XML 声明文件

`app-plugins.xml`（位于 `src/main/resources/META-INF/`）同时包含扩展点声明和内置实现：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<app-plugins>
    <extensionPoints>
        <extensionPoint name="deskframe.demo.greeting"
                        interface="com.jdesk.demo.GreetingProvider"/>
        <extensionPoint name="deskframe.demo.toolWindow"
                        interface="com.jdesk.demo.ToolWindowProvider"/>
    </extensionPoints>

    <extensions>
        <extension point="deskframe.demo.greeting"
                   implementation="com.jdesk.demo.builtin.SystemInfoGreetingProvider"/>
        <extension point="deskframe.demo.greeting"
                   implementation="com.jdesk.demo.builtin.JavaInfoGreetingProvider"/>
    </extensions>
</app-plugins>
```

### 加载流程

```
DemoApp.run()
  └─ JdeskApplication.init()
  │    └─ ApplicationCore                       ← 注册 core 服务和 VFS
  │    └─ FrameConfigLoader                     ← 加载 frame-plugins.xml
  │    └─ ApplicationConfigLoader               ← 加载 app-plugins.xml
  │    └─ PluginManager.initPlugins()           ← 创建插件 classloader 并注册插件扩展点
  │
  └─ JdeskApplication.start()
       └─ PluginManager.startPlugins()          ← 注册插件声明的扩展实现
```

---

## 文件清单

```
jdesk-framework-demo/
├── EXTENSION_POINTS.md                        ← 本文档
├── src/main/resources/META-INF/
│   └── app-plugins.xml                        ← 扩展点 + 内置扩展声明
├── src/main/java/com/jdesk/demo/
│   ├── GreetingProvider.java                  ← 扩展点接口
│   ├── ToolWindowProvider.java                ← 扩展点接口
│   ├── builtin/
│   │   ├── SystemInfoGreetingProvider.java    ← 内置实现
│   │   └── JavaInfoGreetingProvider.java      ← 内置实现
demo-greeter/                                  ← 示例插件模块（嵌在本模块目录下）
└── src/main/
    ├── java/com/jdesk/demo/plugin/
    │   └── DemoGreeterExtension.java          ← 插件实现（由 demo-greeter 插件贡献）
    └── resources/META-INF/
        └── plugin.xml
```

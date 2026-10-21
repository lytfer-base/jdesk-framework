# jdesk-framework-demo

`jdesk-framework-demo` 是框架的 Swing 集成验证应用。它不是最终产品形态，而是用来确认 core、frame 和插件机制可以一起工作。

## 运行方式

需要带 JCEF 的 JetBrains Runtime 17，和构建要求相同。在仓库根目录执行：

```powershell
mvn -B package -DskipTests
& "$env:JAVA_HOME\bin\java" -jar jdesk-framework-demo/target/jdesk-framework-demo.jar
```

- `package` 会把运行依赖放进 `jdesk-framework-demo/target/lib/`，并把示例插件 `demo-greeter` 复制到 `.root/demo-app/plugins/`。
- 也可以在 IDE 里直接运行主类 `com.jdesk.demo.DemoApp`。先执行一次 `mvn package`，让示例插件就位。
- 开发态下，应用的 home 是仓库里的 `.root/demo-app/`：框架会从工作目录开始往上找含 `.root` 的目录，所以工作目录设在仓库内任意位置都可以。
- 默认 WebView 后端是 `auto`，在 JetBrains Runtime 上会用 JCEF；配置在 `.root/demo-app/system/application.properties`。
- Demo 自己的文字只有英文，所以没在设置里选过语言时，界面按英文显示；在设置里选了别的语言，框架自带的文字会跟着换。

## Demo 覆盖的能力

| 区域 | 验证内容 |
|---|---|
| 菜单和工具栏 | `ActionManager`、`AnAction`、`ActionGroup`、Swing Action 转换。 |
| 左侧工具面板 | greeting 扩展点、VFS 文件树。 |
| 右侧工具面板 | 模块状态工具窗口扩展点。 |
| 底部工具面板 | `SystemInfo` 运行环境摘要。 |
| 设置窗口 | `SettingsGroup`、`SearchableConfigurable`、`SettingsDialog`。 |
| 运行期参数 | descriptor-driven runtime 设置页，绑定 `RuntimeParamRegistry` 中的 frame/demo 参数。 |
| 通知 | `Notification.info/warning/error`。 |
| 主题和图标 | `JdfLafManager` light/dark 切换，`JdfIconManager` SVG 图标加载。 |
| 插件管理 | 插件描述符扫描、状态展示、lazy applicationService。 |
| VFS | `VirtualFileManager` 和 `LocalFileSystem`。 |
| 消息总线 | `MessageBus` 发布订阅。 |
| 并发 | `AppLock` 基础读写锁行为。 |
| WebView | 内嵌网页演示、Level 1 能力兼容性检查、启动耗时测量。 |

## 启动流程

`DemoApp.run()` 的主要步骤：

1. 使用 Demo classloader 通过 `JdeskApplicationBuilder` 创建并初始化 `JdeskApplication`。
2. frame 层加载 `META-INF/frame-plugins.xml`，注册动作、设置和 EDT 调度等框架服务。
3. frame 层读取 `META-INF/app-plugins.xml`，加载 Demo 自己的扩展点声明和内置扩展。
4. 并行读取插件描述符，随后按依赖拓扑顺序初始化插件 classloader 和插件声明的扩展点。
5. builder 安装默认 `ComponentStore`，应用主题，并创建 `MainFrame` / `ApplicationContext`。
6. 启动插件贡献，注册插件 lazy extension 和 lazy applicationService。
7. 在 legacy `setup` 回调中注册 `DemoService`、菜单、工具栏、设置页和工具动作。
8. 应用进入 `RUNNING` 并显示主窗口。
9. 在 legacy `started` 回调中构建工具窗口、展示启动通知并输出插件状态。

## Demo 扩展点

扩展点声明位于：

```text
src/main/resources/META-INF/app-plugins.xml
```

当前包含两个扩展点：

| 扩展点 | 接口 | 用途 |
|---|---|---|
| `deskframe.demo.greeting` | `GreetingProvider` | 提供问候语，显示在 Greetings 面板和对话框中。 |
| `deskframe.demo.toolWindow` | `ToolWindowProvider` | 提供可嵌入主窗口的 Swing 工具面板组件。 |

更多说明见 [EXTENSION_POINTS.md](EXTENSION_POINTS.md)。

## 内置设置页

Demo 当前注册的设置页：

- `GeneralSettingsConfigurable`
- `FontConfigurable`
- `EditorTabsConfigurable`
- `EditorSpacingConfigurable`
- descriptor-driven `Runtime / Runtime Parameters` 页面

这些页面用于验证 Settings 框架的树形分组、搜索、修改状态、Apply 和 Reset。

Runtime 页面使用 `SettingDescriptor` 直接声明，当前覆盖：

- Demo toolbar 高度参数：`deskframe.demo.toolbar.default.height`。
- Frame 紧凑模式：`deskframe.ui.compact`。
- Frame 主题偏好：`deskframe.ui.theme`。

## 示例插件

开发模式下，插件目录由 `PathManager` 指向：

```text
jdesk-framework/.root/demo-app/plugins/
```

示例插件的源码在 `jdesk-framework-demo/demo-greeter/`，描述符是其中的 `src/main/resources/META-INF/plugin.xml`。构建时，它的 jar 会自动复制成 `.root/demo-app/plugins/demo-greeter.jar`；`.root/demo-app/plugins/bundled-plugins.xml` 会把它登记为内置插件。

该插件贡献：

- `DemoGreeterExtension`，注册到 `deskframe.demo.greeting`。
- `PluginGreeterService` / `PluginGreeterServiceImpl`，打包在插件 JAR 中，通过 `<applicationService>` 注册为懒加载服务。

在 Demo 中选择 `Tools -> Test Lazy Plugin Service...` 可以触发服务首次实例化。

## 配置文件

Demo 使用 `ComponentStore` 保存 `DemoConfig`：

```text
.root/demo-app/config/DemoConfig.xml
```

配置在应用生命周期的 `onApplicationSaving()` 阶段保存。

## 常用验证动作

启动 Demo 后可以通过菜单验证：

- `Help -> About`：应用信息、模块与运行环境。
- `File -> Settings...`：设置对话框。
- `File -> Configuration...`：配置目录。
- `Edit -> Show Info/Warning/Error Notification`：通知气泡。
- `Tools -> Show Greetings...`：扩展点收集。
- `Tools -> Test DI Container...`：服务容器。
- `Tools -> Test AppLock...`：读写锁。
- `Tools -> Test Message Bus...`：消息总线。
- `Tools -> Plugin Manager...`：插件状态。
- `Tools -> Test Lazy Plugin Service...`：插件懒加载服务。
- `Tools -> Browse File System...`：VFS 文件树。
- `Tools -> Test WebView...`、`WebView Level 1 Compatibility...`、`WebView Startup Benchmark...`：WebView 演示、兼容性检查与启动耗时。前两个面板的重新加载会新建一个 WebView 替换旧的：第一页已经锁定了导航基准，再调 `loadHtml` 会被拦住。

## 维护约定

- Demo 应尽量覆盖框架能力，而不是堆叠业务功能。
- 新增框架能力时，优先在 Demo 中补一个可手动验证的入口。
- Demo 中的硬编码扩展可逐步迁移到 XML 声明，保持与插件扩展机制一致。

# jdesk-framework-frame

`jdesk-framework-frame` 是构建在 `jdesk-framework-core` 之上的 Swing 应用框架层。它面向普通桌面应用开发者，封装启动、主窗口、动作系统、设置窗口、对话框、通知、主题/图标、窗口状态、基础 JDF 组件和 EDT 调度，让应用代码主要关注界面和业务功能。

## 主要内容

| 包 | 说明 |
|---|---|
| `com.jdesk.frame.boot` | `JdeskApplicationBuilder`、`JdeskApplication`、`ApplicationContext`，提供推荐启动入口。 |
| `com.jdesk.frame.action` | `ActionManager`、`AnAction`、`ActionGroup`、`ActionContext`，用于菜单、工具栏和快捷键。 |
| `com.jdesk.frame.settings` | `SettingsManager`、`SettingsDialog`、`SettingsGroup`、`Configurable`，用于注册和展示设置页。 |
| `com.jdesk.frame.ui` | `MainFrame`、`JdfRootPane`、`JdfHeader`、`JdfStatusBar`、`JdfToolWindowArea`、`DialogBuilder`、`Notification` 等基础 Swing UI。 |
| `com.jdesk.frame.ui.components` | `JdfPanel`、`JdfNavButton`、`JdfIconButton`、`JdfStatusLabel`、`JdfWorkspacePanel` 等轻量组件。 |
| `com.jdesk.frame.ui.scale` | `JdfScale`、`JdfFonts`，处理 JRE-managed HiDPI、紧凑模式和 Swing 默认字体。 |
| `com.jdesk.frame.laf` | `JdfLafManager`、`JdfTheme`、`JdfColors`、`ThemeSettingsConfigurable`，提供 light/dark/system 主题入口。 |
| `com.jdesk.frame.icon` | `JdfIconManager` 和 SVG loader，按应用 classpath 加载 SVG 图标并支持缓存。 |
| `com.jdesk.frame.state` | `WindowStateService`，保存和恢复主窗口位置、尺寸和最大化状态。 |
| `com.jdesk.frame.window` | `JdfWindowWatcher`，为窗口生命周期观察和后续窗口管理预留 frame 服务。 |
| `com.jdesk.frame.swing` | `SwingUiDispatcher`，集中处理 EDT 调度和断言。 |
| `com.jdesk.frame.webview` | `WebViewService`、`JdfWebView` 和可配置 JCEF/JxBrowser 后端，提供可选加载的浏览器视图能力。 |
| `com.jdesk.frame.storage` | `DatabaseService`、`DatabaseHandle`、`DatabaseSession`、迁移、生命周期和通用 SQLite 数据库资源入口。 |
| `com.jdesk.frame.storage.dao` | `EntityDao`、`Repository`、`RowMapper`、`TypeConverter` 和基础实体 CRUD。 |
| `com.jdesk.frame.storage.entity` | `@Table`、`@Column`、`@Id`、`@Index`、`@Transient`、`@SoftReference` 和 record entity schema 解析。 |
| `com.jdesk.frame.storage.sqlite` | SQLite 后端实现，负责 WAL、busy timeout、migration、单写队列和数据库文件管理。 |

## 启动示例

```java
public static void main(String[] args) {
    JdeskApplicationBuilder.create("My App")
            .applicationClassLoader(MyApp.class.getClassLoader())
            .applicationActivity(context -> {
                ActionManager actions = context.getActionManager();
                SettingsManager settings = context.getSettingsManager();
                MainFrame frame = context.getMainFrame();

                // register actions, settings, and custom UI here
            })
            .postStartupActivity(context -> {
                context.getMainFrame().showNotification("Ready");
            })
            .launchBlocking();
}
```

`JdeskApplicationBuilder` 会通过 coroutine-first 启动链创建 `JdeskApplication`，由它作为 core `Application` 进入全局 `ApplicationManager`，初始化 core 与 frame 服务、设置 Swing Look & Feel、创建 `MainFrame`，并默认注册 `ComponentStore`，在应用保存阶段调用 `saveAll()`。
插件描述符加载会与 frame/application XML 配置加载并发执行；插件初始化、扩展注册和插件启动仍在确定性阶段按顺序完成。
`PostStartupActivity` 在主窗口显示后于 EDT 上执行，因此可以安全地读取窗口屏幕位置并显示通知、对话框等依赖可见窗口的 UI。

frame 层内置服务由 `src/main/resources/META-INF/frame-plugins.xml` 声明，当前包括：

| 服务 | 说明 |
|---|---|
| `ActionManager` | 动作注册、分组、菜单栏和工具栏构建。 |
| `SettingsManager` | 设置页注册、descriptor-driven 设置页和设置窗口。 |
| `JdfLafManager` | light/dark/system 主题切换，并把主题刷新到已注册的 JDF 组件。 |
| `JdfIconManager` | 从应用 classpath 加载 SVG 图标，提供应用图标辅助方法。 |
| `SwingUiDispatcher` | EDT 调度和断言。 |
| `WindowStateService` | 将窗口状态保存到 `window-state.xml`。 |
| `JdfWindowWatcher` | 当前作为窗口观察服务入口，后续可继续扩展。 |
| `WebViewService` | 应用层 WebView 入口，默认自动优先选择 JCEF，可配置切换到 JxBrowser，并保持懒加载。 |
| `DatabaseService` | frame 层通用 SQLite 数据库入口，支持模块/插件各自创建和释放数据库资源，并保持懒加载。 |

设置里的名称可以随界面语言切换：`SettingsManager` 的 `getOrCreateGroup`、`registerConfigurable`、`registerSettingsPage`，`SettingDescriptor.Builder` 的 `displayName`、`description`、`group`，以及 `SettingChoice.of`、`JdfTheme.of` 都有 `Supplier<String>` 重载，名称在每次绘制时现取，写成 `() -> I18n.message("键")` 即可；String 版本按常量处理。
框架内置两个分组 id：`SettingsManager.APPEARANCE_GROUP_ID`（`appearance`，放主题页与语言页）和 `GENERAL_GROUP_ID`（`general`，描述符设置的默认分组）。分组名由第一个注册者决定，框架先注册外观组，应用按 id 往里加页即可。

## WebViewService

`WebViewService` 是 frame 层面向应用的浏览器视图入口。应用只依赖 `com.jdesk.frame.webview` 下的
`WebViewService`、`JdfWebView`、`WebViewOptions`、`WebViewEvent` 等抽象，不直接引用 JCEF 或 JxBrowser 类型。

```java
WebViewService webViewService = app.getService(WebViewService.class);
JdfWebView webView = webViewService.createWebView(
        WebViewOptions.builder()
                .initialHtml("<!doctype html><html><body>Hello</body></html>")
                .build());
panel.add(webView.component(), BorderLayout.CENTER);
```

WebView 在 `frame-plugins.xml` 中注册为懒加载的 `ConfigurableWebViewService`。应用不调用
`getService(WebViewService.class)`，并且没有启用 WebView 预热时，JCEF/JxBrowser 后端不会被创建。

应用可通过配置选择后端：

```properties
jdesk.webview.backend=auto
jdesk.webview.backend=jcef
jdesk.webview.backend=jxbrowser
```

`auto` 是默认值，当前优先使用 JBR 自带 JCEF，JCEF 不可用时再回退到 JxBrowser。

应用可在 `<home>/system/application.properties` 中配置启动后预热：

```properties
jdesk.webview.warmup=none
jdesk.webview.warmup=engine
jdesk.webview.warmup=view
```

- `none` 是默认值，不加载 WebView 后端。
- `engine` 在主窗口显示后用后台任务初始化浏览器运行时。
- `view` 在 `engine` 基础上创建隐藏 WebView 并加载最小 HTML，以降低首次可见 WebView 创建耗时。

`WebViewWarmupActivity` 作为 `PostStartupActivity` 被调度，但实际 `getService(WebViewService.class)` 和预热调用会进入
应用级后台任务，不在 EDT 上同步创建浏览器服务。隐藏 WebView 的 Swing 组件创建阶段仍会由后端切回 EDT。

更完整的运行机制、license 配置和注意事项见 [WebViewService 运行机制与使用说明](../docs/web/webview-service.md)。

## DatabaseService / SQLite Storage

`DatabaseService` 是 frame 层的通用 SQLite 数据访问入口。它管理数据库文件路径、默认 SQLite 配置、
schema migration、实体注解解析、DAO 创建、单数据库写队列和生命周期兜底释放。调用模块拿到的是
`DatabaseHandle`，可以按自己的生命周期主动 `dispose()` / `close()`，不必等到应用关闭。

默认数据库路径为：

```text
<home>/system/databases/<owner>/<name>.sqlite
```

示例：

```java
@Table(
        value = "records",
        indexes = @Index(name = "idx_records_updated_at", columns = "updated_at")
)
record RecordEntity(
        @Id @Column("id") String id,
        @Column("title") String title,
        @Column("body_path") String bodyPath,
        @Column("updated_at") long updatedAt
) {
}

DatabaseService storage = app.getService(DatabaseService.class);
DatabaseHandle database = storage.open(DatabaseDescriptor.builder("myapp.records")
        .owner(DatabaseOwner.application("myapp"))
        .name("records")
        .entity(RecordEntity.class)
        .migration(new RecordsV1Migration())
        .build());

EntityDao<RecordEntity, String> records =
        database.entityDao(RecordEntity.class, String.class);

records.insert(new RecordEntity("r1", "Draft", "records/r1.md", System.currentTimeMillis()));
```

第一版默认使用弱约束策略：不启用 foreign key，不生成外键约束；关系字段保存普通 id，
后续通过 Repository、诊断和修复工具处理孤儿数据。SQLite 默认启用 WAL、`synchronous=NORMAL`
和 `busy_timeout=5000`，以优先保证桌面应用中的读写并发体验。

当前已实现的能力包括：

- `DatabaseService` / `DatabaseHandle` / `DatabaseSession` / `DatabaseTransaction` 抽象。
- 模块和插件 owner 的数据库文件路径管理。
- 每个数据库 handle 可由调用方独立释放，应用关闭时兜底释放未关闭资源。
- WAL、busy timeout、foreign keys 默认关闭、`synchronous=NORMAL`。
- `jdesk_schema_version` 和 `jdesk_schema_migration_log`。
- 按版本顺序执行 migration，失败 rollback 并记录错误信息。
- 基于 Java record 的轻量 Entity 注解建表、索引和基础 CRUD。
- `RowMapper`、基础类型转换和受控 `DatabaseRow`。
- 同步/异步读写 API、单写队列、EDT 同步 I/O 拒绝。

仍然计划后续完善的能力包括：可插拔 `TypeConverter` 注册、分页/排序查询对象、基础 Repository 实现类、
安全新增列、软引用诊断、慢查询日志、读连接池、备份恢复、加密和全文索引集成。

更完整的功能介绍、用法示例和当前边界见 [SQLite Storage 功能介绍与使用说明](../docs/storage/sqlite-storage.md)。

## McpServerService（Agent 工具桥）

`McpServerService`（`com.jdesk.frame.mcp`）是应用内嵌的 MCP server：基于官方 MCP Java SDK
（`mcp-core` + `mcp-json-jackson2`）和嵌入式 Jetty，在 loopback（127.0.0.1）上提供
Streamable HTTP 端点 `/mcp`，供本机 agent sidecar（如 Goose）调用应用注册的只读工具。

- `start()` 启动服务；端口由运行期参数 `frame.mcp.port` 控制，缺省或未设时为 0（随机端口）。
  启动后把端点 URL 写进端口文件 `<system>/mcp/<serverName>.url`（dispose 时删除），供 stdio 桥发现。
- `registerTool(name, description, inputSchemaJson, handler)` 注册工具，`removeTool` 反注册；
  本类只承载与业务无关的通用机制，业务工具由应用层经 `registerTool` 注册。
- 每个请求先过本机校验：`Host` 只认 `127.0.0.1:<端口>` 与 `localhost:<端口>`，带 `Origin` 时只认同样这两个 origin，否则 403；
  请求体上限 1 MiB，先按 `Content-Length` 判，分块上传读到上限即停，超限 413；POST 同时最多 10 个在途，
  多出来的排队，等满 30 秒回 503。它挡的是浏览器网页（DNS 重绑定、跨源）和撑爆堆的请求，
  不是应用级鉴权：本机进程读到端口文件照样能调。
- 实现 `Disposable`，关闭时先 `closeGracefully()` 断开 MCP 会话再停 Jetty。

`McpStdioBridge` 是同 jar 的 stdio→HTTP 桥 main 类，给只支持 stdio 的 MCP 客户端（如 Claude Desktop）用：

```text
java -cp <app.jar> com.jdesk.frame.mcp.McpStdioBridge <url | 端口 | 端口文件路径>
```

桥按行转发 JSON-RPC：通知（无 id）不回写，请求回单行 JSON；会话头（`Mcp-Session-Id`）自动接力，
单条消息失败回协议错误而不杀桥。stdout 只写协议消息，诊断走 stderr。
桥不转发 server 推送（GET 流），经桥接入的客户端收不到服务端主动发出的消息。

端点守卫的几处取舍：

- MCP SDK 自带的 `securityValidator` 没有配置，本机边界全靠回环绑定加上面这道守卫，所以 Host / Origin 校验必须在读请求体之前做。
- 只含一条 `notifications/roots/list_changed` 通知的 POST 直接回 202、不交给 SDK：SDK 收到它会向声明了 roots 能力的客户端反查 `roots/list`，有的客户端（如 Goose）不回，会话加载就要白等满 10 秒。
- POST 的在途名额要等 SDK 返回之后才归还：SDK 在工具跑完之前一直占着请求线程和读进来的请求体，提前归还的话，名额限不住线程和内存。

`AcpClient`（`com.jdesk.frame.acp`）只按 ACP 规范经 stdio 驱动外部 Agent，不写死任何 Agent 的专有行为；对接 Goose 实测有几处要注意：

- 模型或 Provider 出错时，Goose 可能把错误渲染成普通的助手文本、照常结束回合，不走协议错误通道，所以不能把「收到回答」当成功判据。
- `mcpServers` 里的 HTTP 条目必须带 `headers` 字段，空数组也行；缺这个字段的条目会在 Goose 反序列化时被静默丢弃，Agent 就看不到这个 MCP server。
- 切换模式只发 `session/set_mode` 这个 snake_case 方法名，Goose 认的是这种拼法。

## ApplicationActivity 和 PostStartupActivity

`ApplicationActivity` 用于需要 `ApplicationContext` 的启动关键工作。它会在主窗口创建之后、首个窗口显示之前于 EDT 上运行，
适合安装菜单、工具栏、工作区和必须在可见前完成的窗口组件。该阶段失败会中断启动，并触发正常的启动回滚。

`PostStartupActivity` 用于首个窗口显示后的非关键工作。它通过应用级 `ApplicationTaskScope` 投递执行；单个 activity 失败只会记录日志，
不会关闭应用，也不会阻止后续 post-startup activity 继续执行。

应用和插件都可以通过扩展点贡献 activity：

```xml
<extensions>
    <extension point="deskframe.applicationActivity"
               implementation="com.example.InstallMainToolbarActivity"/>
    <extension point="deskframe.postStartupActivity"
               implementation="com.example.WarmCachesAfterStartupActivity"/>
</extensions>
```

`JdeskApplicationBuilder.setup(...)` 仍然保留，作为 `ApplicationActivity` 的兼容适配器。新代码优先使用
`applicationActivity(...)`。

`JdeskApplicationBuilder.started(...)` 仍然保留，作为 `PostStartupActivity` 的兼容适配器。新代码优先使用
`postStartupActivity(...)`。

## 当前 UI 基础设施

当前 frame UI 已经能支撑 Demo 和实际应用的外壳：

- `MainFrame` 提供 header、菜单栏、工具栏、左/右/底部工具窗口、中央 workspace 和状态栏。
- `JdfRootPane`、`JdfHeader`、`JdfStatusBar`、`JdfToolWindowArea` 负责通用桌面壳结构。
- `JdfPanel` 和相关组件通过 `JdfThemedComponent` 接入 `JdfLafManager`，可以随主题刷新颜色。
- `JdfScale` 按 Java/Swing 的 HiDPI 语义做逻辑尺寸缩放，避免在 JRE-managed HiDPI 下重复放大。
- `JdfFonts` 修正 Windows 默认 Swing 字体，并提供 label、mono、derive 等常用字体入口。
- `JdfIconManager` 支持当前常用 SVG 子集，已用于 Demo 菜单/工具栏图标和应用图标。

当前还没有系统化的 Tree/Table/TabbedPane/SearchTextField/Breadcrumb/Progress 组件，也没有完整设计 token 或组件变体库。这些是下一轮 frame UI 层最自然的补齐方向。

### 主题与原生画法的控件

Windows LaF 在系统视觉样式开着时，把滚动条（`WindowsScrollBarUI`）、勾选框和单选框的字形（`WindowsIconFactory`）以及弹出菜单交给原生主题绘制，`ScrollBar.*`、`PopupMenu.background` 这些 UIDefaults 一个都不读，`CheckBox.background` 也只管底色、不管方框；离屏采样可见，写进深色后滚动条轨和未勾选的方框仍是系统浅色。所以 `JdfLafManager` 连画法一起换：滚动条用 `JdfScrollBarUI`，勾选框和单选框用 `JdfToggleIcon`，弹出菜单换回 `BasicPopupMenuUI`，浅色主题下它们也随之变成扁平样式。这些委托和字形的颜色都在绘制时现取：`updateComponentTreeUI` 只重建委托、字形图标是共享单例，缓存下来的颜色会冻结在旧主题上。未勾选方框的描边取 `Jdf.weakText`（调色板里仍需阅读的最弱一档），不取 `Jdf.border`，后者是发丝线，画成方框淡到几乎看不见；`Jdf.disabledText` 留给真正不可用的状态。

`JdfLafManager.unregisterTheme(id)` 给自带设计语言的应用摘掉框架内置主题：内置调色板和应用自己的设计令牌未必一致，留在设置页里，用户选中后只有 Swing 部分变色。摘掉当前主题不会立刻改界面，下次解析时先退到 `DEFAULT_THEME_ID`，它也被摘了就退到注册顺序里的第一个，所以摘完内置主题要显式 `setCurrentTheme(...)` 指定自己的默认。

### 深色主题开窗不闪白

窗口刚显示、Swing 还没画出第一帧时（以及开窗动画合成期间），原生表面按白底打底，DWM 把深色内容和白底合成为一帧可见的灰。三层要一起堵：

| 层 | 做法 | 位置 |
|---|---|---|
| AWT 窗口背景 | 构造时 `setBackground(主题背景色)`，切主题时刷新已开窗口 | `JdfWindowFrame`、`MainFrame` 构造函数；`JdfLafManager` |
| Win32 窗口类画刷 | `addNotify` 之后把 `GCLP_HBRBACKGROUND` 设成主题色；AWT 注册的 `SunAwtFrame`、`SunAwtDialog` 类不带画刷，首帧擦除用的就是它 | `NativeWindowBackground` |
| DWM 深色属性 | 同样在 `addNotify` 里、显示之前设 `DWMWA_USE_IMMERSIVE_DARK_MODE`；等到 `WINDOW_OPENED` 再设，开窗动画已经按系统浅色窗口面板合成 | `NativeTitleBar` |

画刷是窗口类级共享的，同一个句柄会装到 `SunAwtFrame` 和 `SunAwtDialog` 两个类上，而切主题只刷得到当时开着的窗口。所以画刷按颜色缓存、永不 `DeleteObject`：没开对话框时 `SunAwtDialog` 还握着旧句柄，删了它，下次开对话框的首帧擦除就是 GDI use-after-free。画刷数只随用过的背景色种数增长，进程退出时由系统回收；不走 `JdfWindowFrame` / `MainFrame` 的窗口（对话框等）由 `JdfLafManager` 的全局窗口监听器在首次显示时补设画刷和深色标题栏。

### 窗口状态恢复

`WindowStateService` 把边界和扩展状态分开记、分开恢复。只有 `NORMAL` 帧的边界可信（最大化帧的 `getBounds()` 是全屏值，最小化帧是 -32000 那类哨兵值），两者绑在一起的话，一直最大化着关窗的用户没有可信边界，最大化状态也就跟着恢复不了；恢复时先设边界再最大化，顺序反过来，Windows 上后设的边界会改写还原尺寸。扩展状态照存，恢复时剥掉 `ICONIFIED` 位：最小化一扇最大化的窗口，Windows 存下的是 `MAXIMIZED_BOTH|ICONIFIED`，原样恢复会得到一扇最小化、坐标停在 -32000 的窗口；保存时也不能跳过最小化的观测，只在关窗时保存的调用方会因此丢掉最大化。

恢复的尺寸有 320×240 的底线：改显示比例后出现过存下的尺寸只剩一条标题栏大小，原样还原就开出一扇几乎看不见的窗口；底线比任何真实窗口都小，撑不大用户故意调小的窗口，屏幕更小时仍由屏幕上限胜出。窗口自己的最小尺寸只认 `setMinimumSize` 显式声明过的，别直接用 `getMinimumSize()`：没声明时它返回布局推导出来的值，Swing 自己并不强制，照单全收会把用户存下的小窗口悄悄撑大。夹取按各屏幕的工作区（扣掉任务栏）而不是整屏边界：任务栏在顶部时按整屏夹取，标题栏正好压在任务栏底下、拖不动；保存的边界与所有屏幕都不相交（例如副屏已拔）时放弃边界，用调用方的默认尺寸和平台级联位置。

### 惰性内容窗口

`JdfLazyContentFrame.pendingContentLoad()` 告诉退出流程这扇窗还有没有自己发起的后台装配在跑，已被 reset 或 dispose 丢弃、但还在写盘的那次也算。关窗既不等装配也不取消它，退出时不先等这个信号，就是在写盘中途杀进程；取消也指望不上，装配里的 `Files.createDirectories` 与 JDBC 写入都不响应中断。从没调用过 `ensureContentLoaded()` 的窗口回「没有在跑」，前提是装配在 `onFrameOpened` 里发起、靠 EDT 先进先出排在退出检查之前；把装配挪到更晚的时机，退出就会漏等这一份。

## 维护约定

- `frame` 可以依赖 Swing/AWT；`core` 不可以。
- 普通应用优先使用 `JdeskApplicationBuilder` 和 `ApplicationContext`，避免直接接触 `ApplicationCore`、服务注册和插件加载细节。
- 具体应用的数据模型、业务服务、页面文案和业务流程不放入本模块，留在应用自己的模块里。
- JCEF 后端内置在 frame；JxBrowser 后端在独立模块 `jdesk-webview-jxbrowser`，不在本模块内，classpath 上没有它时该后端视为不可用。公共 API 不暴露后端类型。
- SQLite storage 第一版已经进入 frame 主模块；搜索和文档工作区仍应先用应用场景验证，再决定是否抽成 frame 可选模块。

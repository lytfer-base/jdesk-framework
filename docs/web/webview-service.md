# WebViewService 运行机制与使用说明

本文说明 `jdesk-framework-frame` 中 WebView 能力的运行机制、使用方式和注意事项。本仓库自带 JCEF 后端，浏览器内核由带 JCEF 的 JetBrains Runtime（JBR）提供；应用层只依赖 frame API，不直接引用 `org.cef.*` 或具体后端包里的类型。

## 设计目标

- WebView 是 frame 层能力，应用通过 `WebViewService` 使用浏览器视图。
- 浏览器后端保持可选加载；应用不使用 WebView 时，不应创建 `WebViewService`，也不应初始化浏览器运行时。
- WebView 公共 API 不暴露任何后端类型，后续可以把公共 API 与后端拆成独立模块。
- 浏览器运行时冷启动较慢，应用可以通过配置选择启动后后台预热。

## 主要 API

公共 API 位于 `com.jdesk.frame.webview`：

| 类型 | 说明 |
|---|---|
| `WebViewService` | 应用层入口，用于创建 WebView、查询后端状态、触发预热。 |
| `JdfWebView` | 单个浏览器视图包装，暴露 Swing 组件、加载 URL/HTML、执行 JavaScript（只发不回值）、执行原生编辑命令（`JdfEditorCommand`：剪切 / 复制 / 粘贴 / 删除，作用于焦点元素）和监听加载事件。 |
| `WebViewOptions` | 创建选项，包括渲染模式、初始 URL/HTML（二选一）、用户数据目录和导航策略。 |
| `WebViewNavigationPolicy` | 主框架导航的放行策略，见「导航策略」。 |
| `WebViewEvent` / `WebViewListener` | 标题、URL、加载完成和加载失败等事件。 |
| `WebViewWarmupResult` | 预热结果，包含成功/失败状态、耗时和失败摘要。 |
| `WebViewBackendStatus` | 后端状态，如 `NOT_STARTED`、`WARMING_UP`、`READY`、`FAILED`、`DISPOSED`。 |

应用代码只应引用这些类型，不应引用 `org.cef.*` 或后端实现包（如 `com.jdesk.frame.webview.jcef.*`）。需要在日志或界面里显示当前内核时，可使用 `WebViewService#getBackendName()`，JCEF 后端返回 `jcef`。

## 服务加载机制

`frame-plugins.xml` 只把接口注册为懒加载服务：

```xml
<applicationService serviceInterface="com.jdesk.frame.webview.WebViewService"
                    serviceImplementation="com.jdesk.frame.webview.ConfigurableWebViewService"/>
```

这意味着：

- 框架启动时只注册服务描述符，不创建具体浏览器后端实例。
- 第一次调用 `application.getService(WebViewService.class)` 时，才会创建 `ConfigurableWebViewService`。
- 具体后端在首次查询状态、预热或创建 WebView 时按配置选择，并按类名加载实现类。
- 默认没有启用预热时，不使用 WebView 的应用不会加载浏览器后端。
- `WebViewWarmupActivity` 是 `deskframe.postStartupActivity` 扩展，但默认 `jdesk.webview.warmup=none` 时只读配置后返回，不会创建 `WebViewService`。

## 后端选择

后端选择支持 JVM property、环境变量和运行期参数，按这个顺序取第一个有值的；值不认识时按 `auto` 处理：

```properties
jdesk.webview.backend=auto
jdesk.webview.backend=jcef
jdesk.webview.backend=jxbrowser
```

| 来源 | 配置 |
|---|---|
| JVM property | `-Djdesk.webview.backend=auto\|jcef\|jxbrowser` |
| 环境变量 | `JDESK_WEBVIEW_BACKEND=auto\|jcef\|jxbrowser` |
| 运行期参数 | `jdesk.webview.backend=auto\|jcef\|jxbrowser` |

`auto` 是默认值：JCEF 可用时选 JCEF，否则若 `jxbrowser` 后端可用则用它，都不可用时在首次使用 WebView 时抛出 `WebViewException`。JCEF 可用指 `JcefRuntime.isAvailable()` 成立：`org.cef.CefApp` 可加载，且当前 `java.home` 下有 `bin/jcef_helper`（Windows 上是 `jcef_helper.exe`）与 `lib/resources.pak`。`jxbrowser` 对应一个本仓库之外的商业后端，框架只按类名 `com.jdesk.frame.webview.jxbrowser.JxBrowserWebViewService` 加载它，本仓库不含，类不在 classpath 上时视为不可用。显式配置 `jcef` 或 `jxbrowser` 时，如果对应后端不可用，会在首次使用 WebView 时抛出 `WebViewException`，由应用显示可见降级界面。

## 预热机制

浏览器后端的冷启动主要发生在运行时初始化和第一个浏览器视图创建阶段。框架提供三种启动预热模式：

```properties
jdesk.webview.warmup=none
jdesk.webview.warmup=engine
jdesk.webview.warmup=view
```

支持来源：

| 来源 | 配置 |
|---|---|
| JVM property | `-Djdesk.webview.warmup=none\|engine\|view` |
| 运行期参数 | `jdesk.webview.warmup=none\|engine\|view` |

JVM property 优先于运行期参数；值不认识时记一条 WARN 并按 `none` 处理。

| 模式 | 行为 | 适用场景 |
|---|---|---|
| `none` | 默认值，不创建 `WebViewService`，不加载浏览器后端。 | 应用不一定使用 WebView，或希望完全按需加载。 |
| `engine` | 主窗口显示后，在后台任务中创建 `WebViewService` 并初始化浏览器运行时。 | 希望降低首次 WebView 创建成本，但不提前创建隐藏浏览器。 |
| `view` | 在 `engine` 基础上预热视图，具体做法由后端决定。JCEF 后端只预热引擎、不建隐藏视图：原生浏览器要等组件挂到窗口上才创建，没有父窗口的隐藏视图等不到创建完成。 | 应用启动后很快会打开 WebView，愿意用后台资源换取首次打开速度。 |

`WebViewWarmupActivity` 自身通过 post-startup activity 触发，但实际的 `getService(WebViewService.class)` 和 warmup 调用会提交到 `ApplicationTaskScope.launchDefault(...)`。因此，即使 post-startup activity 在 EDT 上被调度，也不会在 EDT 上同步创建浏览器服务。

应用也可以在明确知道后续要使用 WebView 时手动预热：

```java
WebViewService webViewService = app.getService(WebViewService.class);
webViewService.warmUpViewAsync().whenComplete((result, failure) -> {
    if (failure != null || !result.isSuccess()) {
        // 预热失败不一定要中断应用，通常记日志并在真正打开 WebView 时显示失败界面。
    }
});
```

注意：一旦手动调用 `getService(WebViewService.class)`，就会创建可配置服务；具体后端仍会在首次查询、预热或创建 WebView 时才选择。如果应用希望完全不加载浏览器后端，就不要主动获取该服务，并保持 `jdesk.webview.warmup=none`。

## 创建和使用 WebView

基础用法：

```java
WebViewService webViewService = app.getService(WebViewService.class);

JdfWebView webView = webViewService.createWebView(
        WebViewOptions.builder()
                .initialHtml("<!doctype html><html><body>Hello</body></html>")
                .build());

JComponent component = webView.component();
panel.add(component, BorderLayout.CENTER);
```

异步创建：

```java
WebViewService webViewService = app.getService(WebViewService.class);

webViewService.createWebViewAsync(WebViewOptions.builder()
        .initialUrl("https://example.com")
        .build()).thenAccept(webView -> {
    SwingUtilities.invokeLater(() -> panel.add(webView.component(), BorderLayout.CENTER));
});
```

常用操作：

```java
webView.loadUrl("https://example.com/docs/");
// 只发不回值；页面还没建出来时脚本直接丢弃。需要结果时让页面经 WebBridge 回报。
webView.executeJavaScript("document.title = 'Ready'");
// 交给浏览器原生执行，焦点在哪个可编辑元素就作用于哪个；后端不支持时静默忽略。
webView.executeEditorCommand(JdfEditorCommand.PASTE);

webView.addListener(event -> {
    if (event.getType() == WebViewEvent.Type.LOAD_FAILED) {
        // 显示失败提示或重试入口。
    }
});
```

`executeJavaScript` 在同一 WebView 上按调用顺序执行，已释放时抛 `WebViewException`；没有取值重载，结果要由页面经 [WebBridge](webbridge-architecture.md) 回报。JCEF 后端的编辑命令：剪切、复制、删除走 `CefFrame` 自带命令，粘贴经 DevTools 协议派发一次真 Ctrl+V——浏览器进程直接下达的粘贴没有用户激活，渲染进程可能读到空剪贴板。

`JdfWebView` 是 `Disposable`。如果没有把它注册到更大的 disposable parent 下，使用结束后应主动释放：

```java
Disposer.dispose(webView);
```

当前后端内部会把创建出的 WebView 注册到共享 `WebViewService` 下；应用关闭或服务释放时会统一清理。但长期存在的工具窗口、对话框或临时预览页仍建议显式管理生命周期，见 [Disposer 机制与使用指南](../framework/disposer.md)。

### 导航策略

`WebViewOptions.navigationPolicy(...)` 决定主框架导航放不放行，默认 `WebViewNavigationPolicy.sameOrigin()`：基准锁在这个 WebView 第一篇内容文档的 origin 上（`about:blank` 之类引导页不算），之后去别的 origin 的主框架导航一律拦下并记一条 WARN；`sameOriginPlus(origins)` 在同源之外再放行一组 origin。JCEF 后端在 `onBeforeBrowse` 里判，Java 侧发起的 `loadUrl` / `loadHtml` 同样受管。`loadHtml` 走 data URL，文档没有 origin：拿它做第一篇内容会把基准锁成不透明源，之后一切导航都拒。JCEF 后端的 popup（`window.open`、在新窗口打开）一律拒绝。

## Web Storage 和稳定 origin

JCEF 可以持久化 localStorage，但它依赖浏览器的用户数据目录，并且 localStorage 按页面 origin 隔离。JCEF 通过 `CefSettings.cache_path` 持久化 HTML5 storage，后端把它设成用户数据目录：默认 `<home>/system/jcef/user-data`，可用 `WebViewOptions.userDataDirectory(...)` 覆盖（只在引擎首次创建时生效）。如果没有稳定 cache path，localStorage 不能可靠跨会话保存。

设计原则：

- 业务权威数据不要只放在 localStorage，应通过 Java/Kotlin 服务和 SQLite/VFS 等统一存储保存。
- localStorage 适合保存 Web 页面工作状态和缓存，例如打开标签、激活项、滚动位置、光标位置和最近渲染快照。
- 依赖 localStorage 恢复工作状态的生产页面不应使用 `file:` URL。
- 开发期可以使用 Vite 之类的 localhost dev server。
- 生产期使用 frame 受控的资源服务：页面 origin 固定为应用指定的 `http://<label>.localhost`（如 `http://myapp.localhost`），服务只绑 127.0.0.1 的随机端口并由后端接上，每个请求带会话 cookie 鉴权，端口与暴露面由框架统一管理。
- 资源经 frame 层 `WebResourceServer` 的本地回环 HTTP 供应，各后端通用；WebView 后端只负责在引擎初始化时接上映射（JCEF：`--host-resolver-rules`）并在首次导航前种入会话 cookie。范围与未完成的安全边界见 [资源 HTTP 服务](resource-http-server.md)。

## 运行配置

JCEF 后端要求运行 JVM 是带 JCEF 的 JetBrains Runtime：`org.cef.CefApp` 可加载，`java.home` 下有 CEF 的 helper（`bin/jcef_helper`）与资源包（`lib/resources.pak`，`lib/locales` 有则一并使用），后端把这几个路径交给 `CefSettings`，native 文件须与平台架构匹配。这些路径由实际运行时决定，不应写入仓库或文档中的固定工作站路径。无头环境下创建 WebView 直接抛 `WebViewException`。

远程调试端口默认关闭，调试网页时才打开；打开后可以用 Chrome 访问 `http://127.0.0.1:<端口>` 检查页面：

| 来源 | 配置 |
|---|---|
| JVM property | `-Djdesk.webview.remoteDebuggingPort=9333` |
| 环境变量 | `JDESK_WEBVIEW_REMOTE_DEBUGGING_PORT=9333` |

这个端口没有鉴权，本机任何进程都能操纵页面，只在调试时打开。值不是 1–65535 的整数时按没配处理；只读 JVM property 和环境变量，写进运行期参数无效。

## JCEF 依赖方式

JCEF 后端直接编译依赖 `org.cef.*`。编译期类型来自 [`jdesk-jcef-api`](../../jdesk-jcef-api/pom.xml) 模块：它没有源码，构建时从 `JAVA_HOME/jmods/jcef.jmod` 抽出 `org.cef` 的类（根 pom 的 `<release>17</release>` 看不见 JBR 私有的 `jcef` 模块）；frame 以 `provided` scope 依赖它，它不进任何发行包，运行期用的永远是 JBR 自带的 `jcef` 模块。因此构建机的 `JAVA_HOME` 也必须是带 JCEF 的 JBR，否则构建在这个模块停下并给出提示。

后端实现类由 `ConfigurableWebViewService` 按类名加载、不硬引用：跑在不带 JCEF 的普通 JRE 上时不会链接到 `org.cef.*`，只是 JCEF 后端不可用。公共 API 仍保持 `WebViewService` / `JdfWebView`，不暴露 `org.cef.*`。

演进方向：

- 如果 WebView 能力继续扩大，可以把公共 API 与后端拆成独立模块（例如 `webview-api`、`webview-jcef`），应用公共 API 不变。
- 不建议直接复用 IntelliJ 的 `JBCef*` 封装类。它们依赖 IntelliJ 平台的 registry、application、disposer、proxy、certificate、notification、action system 等基础设施，直接引入会把框架重新绑回 IntelliJ 平台。

## 与 IntelliJ JCEF 封装的差异

IntelliJ Platform 的 `platform/ui.jcef` 是完整的 IDE 平台级 JCEF 子系统；本框架的实现是轻量 WebView 后端。两者重点不同：

| 维度 | IntelliJ `ui.jcef` | JDesk Framework 当前实现 |
|---|---|---|
| API 定位 | 暴露 `JBCefApp`、`JBCefBrowser`、`JBCefClient`、`JBCefJSQuery` 等 IntelliJ 封装。 | 应用只使用 `WebViewService` 和 `JdfWebView`，不暴露后端类型。 |
| 依赖方式 | 直接编译依赖 `org.cef.*` 和 `com.jetbrains.cef.*`。 | 编译期依赖 `jdesk-jcef-api` 抽出的 `org.cef.*`（provided），运行期用 JBR 的 `jcef` 模块；后端实现类按类名加载。 |
| 可用性检查 | registry 开关、headless、OS、JBR 模块来源、CEF/JCEF 最低版本等完整检查。 | `JcefRuntime.isAvailable()` 查 `org.cef.CefApp` 可加载，以及 `java.home` 下的 helper 与 `resources.pak`；无头环境在引擎初始化时报错。还不查 OS 与版本。 |
| 启动配置 | 通过 `JCefAppConfig` 和 `SettingsHelper` 合并 JBR settings、proxy、GPU、sandbox、debug、日志等参数。 | 手动创建 `CefSettings`，配置 cache、cookies、界面语言、日志级别、首帧底色、可选的远程调试端口，以及 helper、resources、locales；命令行只给 browser 进程追加开关（关闭 Windows 原生遮挡检测、资源服务的 host 映射）。 |
| Browser 创建 | 使用 `JBCefBrowserBuilder`，支持 OSR handler、DevTools 菜单、mouse wheel、frame rate、包装已有 browser。 | 根据 `WebViewOptions` 创建 windowed/off-screen browser，能力更精简。 |
| Handler 管理 | `JBCefClient` 支持多 handler、按 browser 维度管理 context menu、dialog、download、permission、keyboard、request 等。 | 全进程共用一个 `CefClient`，注册 message router、load、display、life span，以及文件对话框、下载、右键菜单（清空原生菜单）与 request handler（导航兜底、上传改写、渲染进程终止日志）。 |
| HTML 加载 | `loadHTML(html, url)` 通过自定义 scheme/虚拟 file 请求保留 URL/base 语义。 | `loadHtml` 使用 base64 data URL，简单但对相对资源和大文档不够友好，且文档没有 origin（见「导航策略」）。 |
| JS Bridge | `JBCefJSQuery` 支持 query pool，规避 native browser 创建后不能新增 JS query 的限制。 | 固定一个 `window.jdeskQuery`（`CefMessageRouter`），只给 WebBridge 用；`executeJavaScript` 只发不回值。 |
| 生命周期 | 绑定 IntelliJ `Disposable`、应用退出、client/browser handler 清理和 `CefApp.dispose()`。 | 绑定框架 `Disposable`；释放顺序为视图 → message router → client → `CefApp`，并最多等 5 秒到 CEF 进入 `TERMINATED`；`CefApp` 关掉后本进程不能再起 JCEF。 |

后续增强 JCEF 时，优先参考 IntelliJ 的设计思路，而不是直接复制实现。尤其是可用性检查（OS、版本）、虚拟 scheme、JS query 池、certificate/proxy handler，这些是更值得迁移的思想。

## 注意事项

- 不要在应用层直接依赖任何后端 API；只通过 `WebViewService` 和 `JdfWebView` 使用。
- 不使用 WebView 的应用保持 `jdesk.webview.warmup=none`，避免加载浏览器后端。
- 需要改善首次打开速度时优先使用 `view` 预热；只想降低引擎初始化成本时使用 `engine`。JCEF 后端下两者效果相同。
- WebView Swing 组件创建必须在 EDT 上完成；框架内部会处理切换。应用更新 Swing 容器时仍应回到 EDT。
- JCEF 的引擎初始化要在 EDT 上跑完：后台等引擎就绪时别堵住 EDT。在 EDT 上同步调用 `createWebView` 时，JCEF 后端用二级事件循环等待，EDT 照常处理事件。
- 复杂右键菜单、浮动工具条、tooltip、选区菜单等尽量在 Web 内部实现，不依赖 Java lightweight 组件覆盖浏览器视图；JCEF 后端不显示原生右键菜单。
- 不要把任一后端专属能力设计成平台基础能力。不同后端在 DOM Java API、扩展、DevTools、打印、PDF 和媒体能力上可能存在差异。
- 预热失败应视为非关键失败：记日志，真正打开 WebView 时提供可见的失败说明或降级界面。
- 预热会在启动后短时间内占用 CPU/磁盘资源；对启动后立即重交互的应用，需要结合实际体验决定是否默认开启。

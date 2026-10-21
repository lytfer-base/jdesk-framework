# jdesk-framework-core

`jdesk-framework-core` 是框架内核模块，承载通用工具（`com.jdesk.util`）、并发与 EDT 调度（`com.jdesk.concurrency`）、应用生命周期、服务容器、扩展点、插件、消息总线、VFS 和配置持久化。除 `com.jdesk.concurrency` 包（EDT 调度天生要碰 Swing）外不依赖 Swing/AWT；动作系统、设置窗口和基础 UI 在 `jdesk-framework-frame`。

模块内部按包分层保持单向：`util` 不 import 其他包，`concurrency` 只 import `util`，两者都不 import `core`。这是包级约定，由评审与 `CoreBoundaryTest` 的 Swing 豁免边界共同守着。

## 子系统概览

| 子系统    | 主要类                                                                                                  | 说明                                           |
|--------|------------------------------------------------------------------------------------------------------|----------------------------------------------|
| 通用工具   | `Pair`、`Triple`、`StringUtil`、`SystemInfo`、`Platform`、`XmlHelper`                                   | 无业务语义的基础工具：元组、字符串、运行环境信息、XML 序列化。见「通用工具」章节。 |
| 并发调度   | `AppLock`、`EdtScheduler`、`JdeskTaskScope`、`JdeskStartupScope`                                        | 应用级读写锁、Swing EDT 调度、协程任务与启动作用域。见「并发与 EDT 调度」章节。 |
| 应用内核   | `Application`、`ApplicationManager`、`ApplicationCore`、`ApplicationLifecycleListener`、`PathManager` | 应用核心状态、全局应用访问、路径解析、生命周期通知和核心子系统装配。          |
| DI 容器  | `ServiceContainer`、`ServiceDescriptor`、`ServiceContainerImpl`                                        | 支持实例注册、类名懒加载、预加载、Disposer 树接入和逆序销毁。          |
| 扩展点    | `ExtensionRegistry`、`ExtensionPoint`、`ExtensionDescriptor`、`ExtensionPointImpl`                      | 支持 eager 和 lazy 扩展，lazy 解析会保留真实注册顺序。         |
| 插件     | `PluginManager`、`PluginManagerImpl`、`PluginDescriptor`、`PluginClassLoader`                           | 解析插件描述符、创建插件类加载器、注册插件扩展和 applicationService。 |
| 消息总线   | `MessageBus`、`MessageBusConnection`、`MessageBusKey`、`MessageBusManager`                              | 基于动态代理的同步和异步发布订阅，支持按 key 创建独立 bus。           |
| VFS    | `VirtualFile`、`VirtualFileSystem`、`LocalFileSystem`、`VirtualFileManager`                             | 本地文件系统抽象、URL 解析、刷新和事件分发。                     |
| 配置持久化  | `PersistentStateComponent`、`ComponentStore`、`@State`、`@Storage`                                      | 应用级组件状态的 XML 文件加载和保存。                    |
| 启动追踪   | `StartupTrace`、`StartupSpan`、`StartupTraceWriter`、`StartupTraces`                                    | 记录启动耗时、父子 span、计数器、失败信息，并输出摘要或 report。       |

## 启动边界

`core` 不再提供可直接启动的默认应用实现。普通 Swing 应用应使用 `jdesk-framework-frame` 的
`JdeskApplicationBuilder`；它会创建 `JdeskApplication`，并把该实例作为 core `Application`
注册到 `ApplicationManager`。

初始化成功后，可以通过全局入口获取当前应用：

```java
Application app = ApplicationManager.getApplication();
```

frame 层启动入口会驱动 `ApplicationCore` 完成：

- 注册 core 内置扩展点和核心服务描述符。
- 配置 `VirtualFileManager` 和本地文件系统。
- 由 frame 层加载 `META-INF/frame-plugins.xml` 和宿主应用 `META-INF/app-plugins.xml`。
- 初始化扩展注册表和服务容器。
- 加载并初始化插件描述符。

`JdeskApplication.start()` 会启动插件，并在 UI 显示前和应用进入运行态后发送生命周期事件。Look & Feel 和 EDT 调度由 `jdesk-framework-frame` 负责。

`JdeskApplication.shutdown()` 会先发布可取消的关闭准备事件，再进入不可逆的资源释放阶段：

- `appClosing()` / `onApplicationClosing()`：收到关闭请求，适合做提示或最后检查。
- `canExitApplication()`：任一监听器返回 `false` 时取消本次关闭。
- `onApplicationSaving()`：退出已确认，适合保存配置和应用状态。
- `appWillBeClosed()` / `onApplicationWillClose()`：保存完成后、真正释放资源前触发。
- `onApplicationShuttingDown()`：应用进入不可逆关闭阶段后触发。

早期启动事件通过 `Application.addLifecycleListener()` 直接注册才能可靠接收，包括 `beforeApplicationLoaded()`、
`applicationInitialized()` 和 `beforeUiShown()`。应用级 `MessageBus` 创建之后，生命周期事件也会继续广播给
`ApplicationLifecycleListener` 的 bus 订阅者。

进入 `SHUTTING_DOWN` 后，应用级 `MessageBus` 会拒绝新连接，但保留已有订阅和发布能力，直到服务容器释放完成后再最终销毁。

## 启动追踪

`StartupTrace` 用于记录应用启动过程的耗时、阶段树、计数器和失败信息。它是 core 能力，具体启动入口和报告写出由
`jdesk-framework-frame` 驱动。

启动追踪模式通过运行时参数 `jdesk.startup.trace.mode` 控制：

- `time`：默认模式，只输出总启动耗时摘要。
- `report`：记录完整 span 树、线程、details、计数器和慢 span，并由 frame 层写出 JSON/HTML 报告。
- `false`：关闭启动追踪。

创建和获取：

```java
StartupTrace trace = StartupTrace.create();
StartupTrace reportTrace = StartupTrace.report();

StartupTrace current = StartupTraces.getCurrent();
```

Java 推荐使用 `runSpan(...)`，它会自动完成 `begin`、失败标记和 `close`：

```java
startupTrace.runSpan("plugins.loadAndInitialize", () -> {
    loadPlugins();
    initPlugins();
});
```

需要返回值或写入 details 时，可以使用接收 `StartupSpan` 的重载：

```java
PluginManager manager = startupTrace.runSpan("pluginManager.resolve", span -> {
    span.detail("service", PluginManager.class.getName());
    return app.getService(PluginManager.class);
});
```

仍然可以使用底层 `StartupSpan`，但应只在需要手动控制生命周期时使用：

```java
try (StartupSpan span = startupTrace.begin("custom.phase")) {
    try {
        doWork();
    } catch (RuntimeException | Error e) {
        span.failed(e);
        throw e;
    }
}
```

Kotlin 推荐使用 `span { ... }` 风格：

```kotlin
val appInfo = startupTrace.span("app info") {
    detail("source", "shadow")
    ApplicationInfoLoader.load(classLoader)
}
```

协程代码使用 `suspendSpan { ... }`：

```kotlin
startupTrace.suspendSpan("frame.theme.applyConfigured") {
    withContext(Dispatchers.Swing) {
        application.getService(JdfLafManager::class.java).applyConfiguredTheme()
    }
}
```

同步嵌套 span 会自动形成父子关系；跨线程或协程异步任务需要显式捕获父 span id，并使用 child span：

```kotlin
startupTrace.suspendSpan("application.init.parallel") {
    val parentId = startupTrace.currentSpanId

    val plugins = startupScope.asyncIo("plugins.load") {
        startupTrace.suspendChildSpan(parentId, "plugins.load") {
            core.loadPlugins()
        }
    }

    plugins.await()
}
```

Java 异步任务也可以使用 `beginChild(...)` 或 `task(...)`：

```java
Integer parentId = startupTrace.getCurrentSpanId();
Callable<Void> loadPlugins = startupTrace.task(parentId, "plugins.load", () -> {
    core.loadPlugins();
    return null;
});
```

计数器和关键时间点：

```java
startupTrace.incrementCounter("plugins.loaded");
startupTrace.addCounter("core.services.registered", 5);
startupTrace.markFirstWindowVisible();
```

报告输出：

```java
LOG.info(startupTrace.summary());
StartupTraceWriter.writeIfEnabled(startupTrace);
```

使用约定：

- span 名称使用稳定、层级清晰的点分形式，例如 `application.init.parallel`。
- 小而关键的启动阶段应单独建 span，避免一个大 span 包住全部启动流程。
- 异步子任务必须显式绑定父 span，否则不同线程上的 span 无法自动归属到正确父节点。
- 失败不需要调用方重复写入 details；`span` / `suspendSpan` / `runSpan` 会自动记录异常类型和消息。
- 高频循环中优先使用 counter，避免创建大量细碎 span。

## 服务容器

注册已有实例：

```java
app.getServiceContainer().registerInstance(MyService.class, new MyService());
MyService service = app.getService(MyService.class);
```

注册懒加载服务：

```java
app.getServiceContainer().registerService(
        MyService.class,
        ServiceDescriptor.ofClassName(
                MyService.class,
                "com.example.MyServiceImpl",
                getClass().getClassLoader()
        )
);
```

查询服务是否已经创建：

```java
MyService maybeCreated = app.getServiceContainer().getServiceIfCreated(MyService.class);
```

服务容器会为所有实现 `Disposable` 的服务建立统一的 Disposer 根节点。服务销毁仍按注册顺序的反向顺序执行，但每个
`Disposable` 服务会通过 `Disposer.dispose(service)` 释放，因此注册在服务下的子 disposable 会先于服务本身被清理。仅实现
`AutoCloseable` 的服务会在销毁阶段调用 `close()`。

## 扩展点

声明扩展点：

```java
ExtensionRegistry registry = app.getExtensionRegistry();
registry.registerExtensionPoint("demo.greeting", GreetingProvider.class);
```

注册 eager 扩展：

```java
registry.registerExtension("demo.greeting", (GreetingProvider) () -> "Hello");
```

注册 lazy 扩展：

```java
registry.registerLazy(
        "demo.greeting",
        new ExtensionDescriptor<>(
                GreetingProvider.class,
                "com.example.PluginGreetingProvider",
                pluginClassLoader,
                "pluginGreeting"
        )
);
```

消费扩展：

```java
registry.forEachExtensionSafe(
        "demo.greeting",
        GreetingProvider.class,
        provider -> System.out.println(provider.getGreeting())
);
```

当前实现要点：

- lazy 扩展不会在注册时加载实现类。
- 第一次查询扩展时才实例化 lazy descriptor。
- eager 和 lazy 混合注册时保留真实注册顺序。
- 反射实例化支持非 public 无参构造器。

## 消息总线

定义 topic：

```java
public interface ProjectEvents {
    void projectOpened(String name);
}
```

订阅和发布：

```java
MessageBus bus = app.getMessageBus();
MessageBusConnection connection = bus.connect();

connection.subscribe(ProjectEvents.class, name -> {
    System.out.println("Opened: " + name);
});

bus.syncPublisher(ProjectEvents.class).projectOpened("demo");
bus.asyncPublisher(ProjectEvents.class).projectOpened("background");
```

绑定生命周期：

```java
Disposable parent = Disposer.newDisposable();
MessageBusConnection connection = bus.connect(parent);

Disposer.dispose(parent);
```

为项目、服务等拥有独立生命周期的对象创建消息总线：

```java
MessageBusManager manager = app.getService(MessageBusManager.class);
MessageBusKey key = MessageBusKey.service(MyService.class);
MessageBus serviceBus = manager.getOrCreateBus(key, service);
```

`MessageBusManager` 负责保存和查找 `MessageBusKey` 对应的 bus，实际释放仍由 lifecycle owner 的 `Disposable` 生命周期驱动。

应用进入 `SHUTTING_DOWN` 或 `DISPOSED` 后，`MessageBusManager` 不再创建新的 bus；已经存在的 bus 可以继续被查找，直到其
lifecycle owner 被释放。

## Core/Frame 边界

`ApplicationCore` 只注册 core 自有能力：`applicationService` 扩展点、`ExtensionRegistry`、
`MessageBusManager`、`PluginManager` 和 `VirtualFileManager`。它不读取 XML，也不通过
`ServiceLoader` 反向加载上层框架模块。

`jdesk-framework-frame` 负责加载 `META-INF/frame-plugins.xml`，注册 `ActionManager`、
`SettingsManager` 和 `SwingUiDispatcher` 等 Swing 应用框架服务。宿主应用自己的扩展点、
内置扩展和服务仍声明在 `META-INF/app-plugins.xml`。

## VFS

```java
VirtualFileManager vfm = app.getService(VirtualFileManager.class);
VirtualFile file = vfm.findFileByUrl("file:///C:/work/readme.txt");

vfm.addVfsListener(event -> {
    System.out.println(event.getType() + ": " + event.getFile());
});

vfm.refresh(true);
```

`ApplicationCore` 初始化时会注册 `LocalFileSystem`，协议名为 `file`。

## 配置持久化

```java
@State(name = "DemoConfig", storages = @Storage("DemoConfig.xml"))
public class DemoConfig implements PersistentStateComponent<DemoConfig.State> {
    public static class State {
        public String theme = "system";
    }

    private State state = new State();

    @Override public State getState() { return state; }
    @Override public void loadState(State state) { this.state = state; }
    @Override public State getDefaultState() { return new State(); }
}
```

注册和保存：

```java
ComponentStore store = new ComponentStore(PathManager.getConfigDir());
store.register(new DemoConfig());
store.saveAll();
```

## 插件描述符

目录型插件示例：

```text
plugins/
└── my-plugin/
    ├── META-INF/plugin.xml
    └── lib/my-plugin.jar
```

`plugin.xml` 示例：

```xml
<deskframe-plugin>
    <id>com.example.myplugin</id>
    <name>My Plugin</name>
    <version>1.0.0</version>
    <vendor email="dev@example.com">Example Inc.</vendor>

    <depends>com.jdesk.core</depends>

    <extensionPoints>
        <extensionPoint name="my.customGreeting"
                        interface="com.example.CustomGreeting"/>
    </extensionPoints>

    <extensions>
        <extension point="demo.greeting"
                   implementation="com.example.MyGreeting"/>

        <applicationService serviceInterface="com.example.MyService"
                            serviceImplementation="com.example.MyServiceImpl"/>
    </extensions>
</deskframe-plugin>
```

当前能力与限制：

- 目录型插件已支持读取 `META-INF/plugin.xml`。
- 单 JAR 插件已支持读取 jar 内 `META-INF/plugin.xml`，并将该 jar 作为插件 classpath。
- 插件依赖图会在初始化前校验；缺失依赖和循环依赖会让插件进入 `FAILED` 状态，不会创建 classloader 或启动。
- 插件卸载会释放类加载器并注销已注册 lazy 扩展、插件声明的扩展点和 applicationService。

## 通用工具（com.jdesk.util）

只放通用、无业务语义的工具类；不引入应用生命周期、插件、UI 或业务概念，工具方法保持无状态或显式输入输出。新工具优先放在语义明确的子包下，例如 `text`、`xml`。

| 类 | 说明 |
|---|---|
| `Pair<A, B>` | 不可变二元组，使用 `Pair.of(first, second)` 创建。 |
| `Triple<A, B, C>` | 不可变三元组，使用 `Triple.of(first, second, third)` 创建。 |
| `StringUtil` | 字符串判空、分割、连接、大小写转换、截断、XML 转义、文件大小和耗时格式化。 |
| `SystemInfo` | OS、JVM、CPU、内存、用户路径等运行环境信息。 |
| `Platform` | Kotlin object，提供平台名称、Unix 判断和信息 Map；Java 调用经 `Platform.INSTANCE`。 |
| `XmlHelper` | 基于 Jackson `XmlMapper` 的 XML 序列化、反序列化和文件读写。 |

```java
Pair<String, Integer> port = Pair.of("http", 8080);
boolean blank = StringUtil.isEmptyOrSpaces("  ");
String escaped = StringUtil.escapeXml("<name>");

if (SystemInfo.isWindows) {
    System.out.println(SystemInfo.getSummary());
}

String xml = XmlHelper.toXml(state);
MyState restored = XmlHelper.fromXml(xml, MyState.class);
```

## 并发与 EDT 调度（com.jdesk.concurrency）

桌面应用常用的并发基础设施；本包是 core 中唯一允许使用 Swing EDT API 的包。与 Swing 线程相关的 API 应清楚标注是否在 EDT 上执行；涉及锁语义变更时补充并发测试；新增协程任务 API 时同时考虑 Kotlin suspend 用法和 Java 过渡用法。

| 类 | 说明 |
|---|---|
| `AppLock` | 基于 fair `ReentrantReadWriteLock` 的应用级读写锁。读并发、写排他；fair 模式下等待写入会阻止新读插队。 |
| `EdtScheduler` | Swing EDT 调度器，支持立即执行、阻塞执行、延迟执行和周期执行；`isEdt()` / `assertEdt()` 做线程断言，用完 `dispose()`。 |
| `JdeskTaskScope` | 通用协程任务作用域：命名任务、IO/Default/EDT 调度、关键任务 fail-fast、后台任务监督，以及 Java 兼容的阻塞等待包装。 |
| `TaskDeferred` | Java 友好的任务句柄，包装协程 `Deferred` 并保留任务名称。 |
| `JdeskStartupScope` | 启动阶段专用的任务作用域门面，内部委托 `JdeskTaskScope`，表达启动任务图和等待边界。 |
| `StartupDeferred` | Java 友好的启动任务句柄，包装 `TaskDeferred` 并保留启动任务名称。 |

### AppLock 与 EdtScheduler

```java
AppLock lock = new AppLock();
lock.read(() -> { /* 读取共享状态 */ });
lock.write(() -> { /* 修改共享状态，写锁排他 */ });

EdtScheduler scheduler = new EdtScheduler();
scheduler.invokeLater(() -> { /* 在 Swing EDT 上执行 */ });
scheduler.invokeAndWait(() -> { /* 在 EDT 上执行并等待 */ });
ScheduledFuture<?> periodic = scheduler.schedulePeriodic(
        () -> System.out.println("tick"), 0, 1, TimeUnit.SECONDS);
scheduler.dispose();
```

当前 `AppLock` 是 fair 读写锁，不是显式三层 `read / write-intent / write` 模型；需要更接近 IntelliJ 的 write-intent 语义时在此类上扩展。

### JdeskTaskScope

管理两类任务：critical task（`asyncIo` / `asyncDefault` / `asyncEdt` / `launchIo` / `launchDefault`，任一失败取消同 scope 兄弟 critical 任务）和 background task（`launchBackgroundIo` / `launchBackgroundDefault`，监督模型，互不牵连）。

```kotlin
JdeskTaskScope("metadata-load").use { scope ->
    val appConfig = scope.asyncIo("load-app-config") { loadAppConfig() }
    val model = scope.asyncDefault("build-start-model") { buildStartModel(appConfig.await()) }
    val ui = scope.asyncEdt("create-ui") { createSwingUi(model.await()) }
    scope.await(ui)
}
```

```java
try (JdeskTaskScope scope = new JdeskTaskScope("metadata-load")) {
    TaskDeferred<AppConfig> appConfig = scope.asyncIo("load-app-config", () -> loadAppConfig());
    AppConfig app = scope.await(appConfig);
}
```

使用约定：每个 scope 都有明确的生命周期拥有者，结束时 `close()` 或 `cancel()`；任务名稳定可读；UI 变更只进 `asyncEdt`；不要在 EDT 中调用阻塞式 `await`；非关键工作放 background scope。

### JdeskStartupScope

只在应用启动编排中使用；普通异步流程用 `JdeskTaskScope`。插件发现、描述符读取等只读 IO 可以并行启动；扩展注册、服务容器变更等有顺序要求的阶段显式 `await` 后再执行；EDT 任务只承载 UI 创建或更新。

```kotlin
JdeskStartupScope("jdesk-startup").use { startup ->
    val plugins = startup.asyncIo("load-plugin-descriptors") { pluginManager.loadPluginsAsync() }
    val loadedPlugins = plugins.await()
    startup.launchDefault("register-extensions") { registerExtensions(loadedPlugins) }.join()
    startup.asyncEdt("create-main-window") { createMainWindow() }.await()
}
```

Java 入口可以继续使用阻塞式包装：

```java
JdeskStartupScope.runBlockingStartup("demo-launch", () -> builder.launchBlocking());
```

## 维护约定

- 不在 `core` 中引入 IDE 专属能力。
- 除 `com.jdesk.concurrency` 包外不引入 Swing/AWT；`jdesk-framework-frame` 依赖在全模块禁止（`CoreBoundaryTest` 守着这两条）。
- `com.jdesk.util` 与 `com.jdesk.concurrency` 不 import `com.jdesk.core`，保持包级单向。
- 核心服务和插件服务优先懒加载。
- 新增跨子系统行为时，应补充集成测试。
- 反射和类加载相关改动需要关注插件卸载、访问权限和注册顺序。

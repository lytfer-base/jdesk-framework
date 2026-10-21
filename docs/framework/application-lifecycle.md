# 应用生命周期设计

本文总结 JDesk Framework 当前的应用生命周期设计。core 层定义 `Application` 契约和 `ApplicationCore` 内核管理面；
frame 层的 `JdeskApplication` 是普通 Swing 应用的实际启动实现。生命周期主要由 `Application`、
`JdeskApplication`、`ApplicationCore`、`ApplicationLifecycleListener`、`ServiceContainer`、`PluginManager`、`MessageBus` 和 `Disposer` 共同完成。

## 设计目标

`Application` 是运行中应用的顶层对象，承担类似 IntelliJ Platform `Application` 的角色。它不是单纯的启动入口，而是一个应用级容器，负责组织以下基础设施：

- 应用状态机和生命周期方法。
- 应用级服务容器。
- 扩展点注册表。
- 插件发现、初始化和启动。
- 应用级消息总线。
- frame 层 UI 启动和 EDT 调度服务。
- 关闭时的资源释放顺序。

当前设计强调两点：启动阶段尽量少加载实现类，插件扩展和服务默认懒加载；关闭阶段按明确顺序通知、释放和清理。

## 状态机

`Application.State` 定义了应用的状态流：

```text
NOT_INITIALIZED
  -> INITIALIZING
  -> INITIALIZED
  -> STARTING
  -> RUNNING
  -> SHUTTING_DOWN
  -> DISPOSED
```

状态含义如下：

| 状态                | 含义                                       |
|-------------------|------------------------------------------|
| `NOT_INITIALIZED` | `JdeskApplication` 已创建，但尚未调用 `init()`。 |
| `INITIALIZING`    | 正在解析路径、初始化 core/frame 服务、加载应用声明并初始化插件描述符。 |
| `INITIALIZED`     | 基础设施已准备好，应用代码可以继续注册自身服务、动作、扩展和监听器。   |
| `STARTING`        | 正在进入运行态，并启动插件贡献。                         |
| `RUNNING`         | 应用已启动完成，生命周期启动事件已经可以发布。                  |
| `SHUTTING_DOWN`   | 正在关闭，开始发送关闭通知并释放资源。                      |
| `DISPOSED`        | 应用已关闭，服务容器、消息总线和扩展注册表已清理。                |

`JdeskApplication` 通过统一的 `transitionTo(State)` 改变状态。每次状态真实变化时，都会输出日志：

```text
Application state changed: OLD_STATE -> NEW_STATE
```

## 初始化阶段

调用顺序从 `JdeskApplication.init()` 开始。该方法只允许在 `NOT_INITIALIZED` 状态调用，否则抛出异常。

初始化阶段的主要步骤是：

1. 将当前应用注册到 `ApplicationManager`，作为全局应用访问点。
2. 切换到 `INITIALIZING`。
3. 发布 `beforeApplicationLoaded(app)`。
4. 通过 `PathManager.getPluginsDir()` 解析插件目录。
5. 安装 frame parent-first 包规则。
6. 通过 `ApplicationCore.initializeCoreServices()` 注册 core 内置扩展点、核心服务和 VFS。
7. 并行解析 frame XML、宿主应用 XML 和插件描述符；解析阶段只生成模型，不修改扩展注册表或服务容器。
8. 按 core -> frame -> app 的顺序应用 XML 模型，注册 frame/app 扩展点、lazy extension 和服务描述符。
9. 调用 `PluginManager.initPlugins()`，创建插件 classloader 并注册插件声明的扩展点。
10. 初始化 `ExtensionRegistryImpl` 和 `ServiceContainerImpl`。
11. 切换到 `INITIALIZED`。
12. 发布 `applicationInitialized(app)`。

如果使用 `JdeskApplicationBuilder`，Swing Look & Feel、UI scale 和默认字体会在 frame 启动编排中与应用基础设施初始化并行处理；直接调用 `JdeskApplication.init()` 不会创建窗口，也不负责完整 frame UI 显示链路。

初始化成功后，框架代码可以通过全局入口获取应用：

```java
Application app = ApplicationManager.getApplication();
```

如果应用尚未初始化，`ApplicationManager.getApplication()` 会抛出 `IllegalStateException`；需要容忍未初始化状态的代码可以使用
`ApplicationManager.getApplicationIfCreated()`。

`ApplicationCore` 直接注册 core 内置能力，包括 `applicationService` 扩展点、`MessageBusManager`、
`PluginManager` 和 `VirtualFileManager`。frame 层服务不进入 core，而是由 `frame-plugins.xml` 声明。

核心服务注册分为两类：

| 类型    | 当前示例                                                | 特点                                                                                  |
|-------|-----------------------------------------------------|-------------------------------------------------------------------------------------|
| 预构造实例 | `ExtensionRegistry`、`VirtualFileManager`             | 初始化时直接放入服务容器。                                                                       |
| 懒加载服务 | `MessageBusManager`、`PluginManager`、frame 层服务       | 只记录接口和实现类名，首次访问时才加载实现类。实现类如果声明 `Application` 构造器，则注入由 `ApplicationManager` 提供的当前应用。 |

`VirtualFileManager` 当前是单例对象。初始化时会设置应用引用，注册 `LocalFileSystem`，并把 `VirtualFileManager` 注册进服务容器，方便通过
`app.getService(VirtualFileManager.class)` 获取。

## 启动阶段

`JdeskApplication.start()` 只允许在 `INITIALIZED` 状态调用。

启动阶段的步骤是：

1. 切换到 `STARTING`。
2. 调用 `PluginManager.startPlugins()`。
3. 发布 `beforeUiShown(app)`。
4. 切换到 `RUNNING`。
5. 发布 `ApplicationLifecycleListener.onApplicationStarted(app)`。

这里有一个关键边界：插件在 `init()` 阶段只完成描述符解析、扩展点注册和类加载器创建；插件声明的扩展实现和
`applicationService` 要到 `startPlugins()` 才注册。这样应用代码可以在 `init()` 和 `start()` 之间注册自己的扩展点，插件启动时再向这些扩展点贡献实现。

## 服务容器

`ServiceContainerImpl` 是应用级 DI 容器，负责服务注册、懒加载、实例缓存和销毁。

服务创建策略由 `ServiceDescriptor` 描述：

- 预构造实例：注册时已有对象。
- 按 `Class` 懒加载：实现类已加载，但实例延迟创建。
- 按类名懒加载：只保存实现类名和类加载器，首次 `getService()` 时再 `Class.forName()`。

服务实现类可以声明单参数 `Application` 构造器。服务容器创建实例时会优先使用这个构造器，并通过
`ApplicationManager.getApplication()` 获取当前应用进行注入，否则使用无参构造器。`Application` 本身不作为普通 service
注册进容器。

服务容器初始化时只会预加载标记为 `preload` 的服务。普通服务在第一次调用 `app.getService(SomeService.class)` 时创建并缓存。

关闭时，服务容器按注册顺序的反向顺序清理已创建实例。所有实现了框架 `Disposable` 的服务都会注册到服务容器的 Disposer
根节点下；销毁单个服务时通过 `Disposer.dispose(service)` 执行，因此服务下注册的子 disposable 会先于服务本身被清理。仅实现
`AutoCloseable` 的服务会在销毁阶段调用 `close()`。

## 插件生命周期

插件生命周期由 `PluginManagerImpl` 管理，分为发现、初始化、启动、停止、卸载几个阶段。

### loadPlugins()

`loadPlugins()` 负责插件发现和描述符解析：

- 确保插件目录存在。
- 读取 `bundled-plugins.xml`，识别 bundled 插件。
- 扫描插件目录和 jar 入口。
- 解析目录型插件或单 JAR 插件的 `META-INF/plugin.xml` 或 `plugin.xml`。
- 记录 `PluginDescriptor`，初始状态为 `UNLOADED`。

### initPlugins()

`initPlugins()` 按依赖拓扑顺序处理插件：

- 注册插件声明的扩展点。
- 为每个插件创建 `PluginClassLoader`。
- 将插件状态置为 `LOADED`。

此阶段不会注册插件扩展实现，也不会实例化插件扩展类。

### startPlugins()

`startPlugins()` 同样按依赖拓扑顺序启动插件：

- 把插件声明的普通扩展注册为 `ExtensionDescriptor`。
- 把插件声明的 `applicationService` 注册为懒加载 `ServiceDescriptor`。
- 将插件状态置为 `STARTED`。

插件扩展实现类不会在注册时加载，而是在第一次查询扩展点时由 `ExtensionPointImpl.resolveLazy()` 创建实例。

### stopPlugin() 和 unloadPlugin()

停止插件时，当前实现会先注销该插件注册过的应用级 service，移除对应 `ServiceDescriptor`，并销毁已创建的 service
实例；随后注销该插件注册过的 lazy 扩展描述符。如果扩展已经解析成实例，也会按实现类名移除。

卸载插件时，如果插件仍处于 `STARTED`，会先停止插件，再释放插件类加载器，并将状态置为 `UNLOADED`。

`PluginManager.dispose()` 会按反向拓扑顺序卸载所有插件。

## 消息总线和生命周期事件

`MessageBus` 是事件系统，用于发布生命周期事件和其他 topic 事件。框架通过应用级 `MessageBusManager` 统一创建和查找不同 key
的消息总线。

应用级 `MessageBus` 不是作为普通 service 实例注册进 `ServiceContainer`，而是由 `JdeskApplication` 直接持有字段。第一次调用
`app.getMessageBus()` 时，会通过懒加载的 `MessageBusManager` 使用固定的 `MessageBusKey.application()` 获取
`MessageBusImpl`。

`MessageBusManager` 会保存 `MessageBusKey` 到 bus 的引用，用于避免重复创建；但它不拥有 bus 生命周期。项目级、服务级等 bus
应使用 `MessageBusKey.project(projectId)`、`MessageBusKey.service(ServiceClass.class)` 等稳定 key 创建，并注册到对应
lifecycle owner 的 `Disposable` 下。owner 释放时 bus 随之释放，并自动从 manager 缓存中移除引用。

`MessageBusManager` 会通过 `ApplicationManager` 感知应用状态。应用进入 `SHUTTING_DOWN` 或 `DISPOSED` 后，manager
允许返回已经创建且未释放的 bus，但不允许再创建新的 bus。

`MessageBusImpl` 支持：

- 同步发布：`syncPublisher(topic)`。
- 异步发布：`asyncPublisher(topic)`。
- 连接订阅：`connect()`。
- 父对象绑定连接：`connect(parentDisposable)`。

生命周期事件接口是 `ApplicationLifecycleListener`。事件会发送给通过 `Application.addLifecycleListener()` 注册的直接监听器；应用级
`MessageBus` 创建之后，也会发送给 bus 订阅者。`beforeApplicationLoaded(app)` 发生在 message bus 可用之前，需要用直接监听器接收。

- `beforeApplicationLoaded(Application app)`：`init()` 开始、框架配置和插件加载前发布。
- `applicationInitialized(Application app)`：框架基础设施和插件描述符初始化完成后发布。
- `beforeUiShown(Application app)`：`start()` 中插件启动后、顶层 UI 显示前发布。
- `onApplicationStarted(Application app)`：应用进入 `RUNNING` 后发布。
- `appClosing(Application app)` / `onApplicationClosing(Application app)`：收到关闭请求后发布。
- `canExitApplication(Application app)`：返回 `false` 可取消本次关闭。
- `onApplicationSaving(Application app)`：退出已确认后发布，用于保存配置和应用状态。
- `appWillBeClosed(Application app)` / `onApplicationWillClose(Application app)`：保存完成后、真正释放资源前发布。
- `onApplicationShuttingDown(Application app)`：进入不可逆关闭阶段后发布。

## 关闭阶段

`JdeskApplication.shutdown()` 是应用关闭入口。若应用已经在 `SHUTTING_DOWN` 或 `DISPOSED`，重复调用会直接返回。

关闭流程分为“可取消的准备阶段”和“不可逆的释放阶段”。

准备阶段通过 `ApplicationLifecycleListener` 发布：

1. `appClosing(app)` / `onApplicationClosing(app)`：收到关闭请求，适合做提示或最后检查。
2. `canExitApplication(app)`：任一监听器返回 `false` 时取消本次关闭，应用保持原状态。
3. `onApplicationSaving(app)`：退出已确认，适合保存配置和应用状态。
4. `appWillBeClosed(app)` / `onApplicationWillClose(app)`：保存完成后、真正释放资源前触发。

只有准备阶段通过后，应用才进入不可逆释放阶段：

1. 切换到 `SHUTTING_DOWN`。
2. 如果应用级 `MessageBus` 已创建，调用 `closeForNewConnections()`，禁止新的 bus 连接。
3. 发布 `onApplicationShuttingDown(app)`。
4. 调用 `serviceContainer.dispose()`，通过服务 Disposer 根节点和反向注册顺序销毁服务实例。
5. 调用 `extensionRegistry.dispose()`，清空扩展点和扩展。
6. 通过 `Disposer.dispose(messageBus)` 销毁消息总线及其连接。
7. 切换到 `DISPOSED`。
8. 从 `ApplicationManager` 清除当前应用引用。

应用级 `MessageBus` 会保留到服务销毁之后再最终释放，因此服务的 `dispose()` 仍可发布已有订阅能收到的清理通知。但从
`SHUTTING_DOWN` 开始，应用不会允许创建新的 message bus，也不会允许在已有应用级 bus 上创建新连接。

`Disposer` 采用父子树模型，父对象销毁前会先递归销毁子对象。`MessageBus.connect()`
创建的连接默认注册为消息总线的子对象，因此应用关闭时会随消息总线一起清理。更完整的机制说明见 [Disposer 机制与使用指南](disposer.md)。

## Demo 应用的装配方式

`jdesk-framework-demo` 展示了框架的推荐装配方式。

`DemoApp.run()` 的主要流程是：

1. 通过 `JdeskApplicationBuilder` 创建 `JdeskApplication`。
2. `JdeskApplication.init()` 完成 core/frame 服务、Demo `app-plugins.xml` 和插件描述符初始化。
3. builder 默认注册 `ComponentStore`，并在 `onApplicationSaving()` 阶段保存配置。
4. builder 应用配置主题并创建 `MainFrame` / `ApplicationContext`。
5. 启动插件贡献，注册插件 lazy extension 和 lazy applicationService。
6. Demo 在 `setup` 回调中注册 `DemoService`、动作、菜单、工具栏和设置页。
7. 应用进入 `RUNNING` 并显示主窗口。
8. builder 在 EDT 上执行 `started` 回调，Demo 在这里构建工具窗口并显示启动通知。

## 当前边界和后续方向

需要明确保存回执的单文件设置可以调用 `ComponentStore.saveState(component, candidate)`：与 `saveAll()` 共用保存锁，文件写成功后才通过 `loadState` 提交内存，写入失败抛出 `IOException`。候选状态需提前校验，组件必须只有一个非弃用的可写存储；`loadState` 只更新内存，不等待 UI 或发布事件，界面生效和广播放在该调用返回之后。批量 `saveAll()` 仍保留逐项尽力保存并记录失败的行为。

状态文件的 XML 序列化只写非 null 字段（`NON_NULL`）：默认配置会把 null 字段写成空元素 `<field/>`，Jackson 读回空元素得到的是 `""`，「从没存过」与「存过空串」在盘上就分不开，组件用 null 表示「没有值」的约定一落盘就失效。读的一侧刻意不把空元素当 null，否则拿空串当哨兵值的字段会一起被归成 null。`NON_NULL` 只管写，修不了存量文件：此前写成空元素的字段照样读成 `""`，组件原样交回时，之后每次保存写的仍是空元素；要让它回到「从没存过」，得由组件自己把这个值置回 null。

当前生命周期设计已经覆盖应用级容器、服务、插件、扩展、消息总线和资源释放，但仍有一些明确边界：

- 目前只有 application-level 生命周期，还没有 project-level 生命周期。
- 插件动态卸载已经会清理应用级 service、lazy 扩展和类加载器，但扩展实例自身的 `Disposable` 语义后续还可以继续细化。
- `ComponentStore` 目前覆盖 application-level 状态，后续还可以继续扩展 project-level 存储、迁移策略和更细的存储作用域。
- `ApplicationActivity` 和 `PostStartupActivity` 已覆盖首窗显示前后的基础启动活动模型；更完整的 Splash/Welcome、后台任务进度展示和可取消启动任务还未建模。

这些边界后续可以继续参考 IntelliJ Platform 的 `ApplicationImpl`、`ComponentStore`、`StartupActivity` 和插件动态卸载模型逐步演进。

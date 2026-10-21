# PluginManager 使用说明

本文基于 `jdesk-framework-core` 中当前最新的 `PluginManager`、`PluginManagerImpl`、`PluginDescriptor`、`PluginClassLoader`、`ServiceContainer` 实现，总结 JDesk Framework 项目里插件系统的使用方式和运行语义。

`PluginManager` 的职责是管理应用级插件生命周期：发现插件、解析描述符、创建插件类加载器、注册插件声明的扩展点、启动插件贡献的扩展和应用级服务，并在停止或卸载时清理这些贡献。

## 核心入口

`PluginManager` 是一个 core 应用级 service，由 `ApplicationCore` 注册为懒加载服务，并由 `ServiceContainer` 在首次访问时创建：

```java
PluginManager pm = app.getService(PluginManager.class);
```

在正常应用中，业务代码不需要手动调用 `loadPlugins()`、`initPlugins()` 和 `startPlugins()`。这些阶段由 `JdeskApplication` 自动串起来：

```text
JdeskApplication.initAsync()
  -> ApplicationCore.initializeCoreServices()
       -> registerService(PluginManager.class, PluginManagerImpl)
  -> parallel startup tasks
       -> PluginManager.loadPlugins()
             -> discover plugin paths and parse descriptors
       -> FrameConfigLoader
             -> parse META-INF/frame-plugins.xml into a model
       -> ApplicationConfigLoader
             -> parse META-INF/app-plugins.xml into a model
  -> apply parsed XML models
       -> register frame extension points and services
       -> register application extension points, built-in lazy extensions, and services
  -> PluginManager.initPlugins()
       -> create plugin classloaders and register plugin extension points
  -> ApplicationCore.initializeContainers()

JdeskApplication.startAsync()
  -> PluginManager.startPlugins()
```

这个顺序很重要。插件描述符、frame XML 和 app XML 可以并行解析，但运行时注册会按 core -> frame -> app -> plugins 的顺序确定性执行，因此 plugins 目录下的插件可以依赖宿主应用声明的扩展点。`initAsync()` 阶段只完成插件描述符解析、插件 classloader 创建和插件声明扩展点注册；插件贡献的普通扩展和 `applicationService` 要到 `startAsync()` 阶段才注册。

frame 层推荐通过 builder 启动，应用代码通常只需要显式传入应用 classloader：

```java
JdeskApplicationBuilder.create("JDesk Demo")
        .applicationClassLoader(DemoApp.class.getClassLoader())
        .setup(context -> {
            PluginManager pm = context.getApplication().getService(PluginManager.class);
        })
        .launchBlocking();
```

## 插件目录

默认插件目录由 `PathManager.getPluginsDir()` 决定：

| 运行模式 | 插件目录 |
|---|---|
| 开发模式 | `<项目根>/.root/<appId>/plugins/` |
| 生产模式 | `<user.dir>/plugins/` |

开发模式指工作目录或它的某一级上层目录里有 `.root/`，离工作目录最近的那一层就是项目根（见 `PathManager`）。`<appId>` 来自宿主应用 `META-INF/application-info.xml` 根元素的 `id` 属性。
测试或特殊启动场景可以在创建 `JdeskApplication` 前显式设置 home 目录，插件目录固定为 `<home>/plugins/`：

```java
PathManager.setHomePathForTests(homeDir);
JdeskApplication app = new JdeskApplication();
app.init(); // Java 兼容阻塞包装器，内部走 initAsync()
```

`PluginManagerImpl.loadPlugins()` 如果发现插件目录不存在，会尝试创建目录并继续运行。

## 插件目录结构

当前支持目录型插件和单 JAR 插件：

```text
plugins/
├── my-plugin/
│   ├── META-INF/
│   │   └── plugin.xml
│   ├── lib/
│   │   └── my-plugin.jar
│   └── classes/
│       └── ...
└── tools-plugin.jar
```

查找规则：

- 优先读取 `my-plugin/META-INF/plugin.xml`。
- 如果不存在，再读取 `my-plugin/plugin.xml`。
- `lib/*.jar` 会加入插件 classpath。
- `classes/` 目录如果存在，也会加入插件 classpath。
- 单 JAR 插件优先读取 jar 内 `META-INF/plugin.xml`，如果不存在再读取 jar 根目录的 `plugin.xml`。
- 单 JAR 插件会把该 jar 自身加入插件 classpath。

## bundled-plugins.xml

插件目录下可以放置 `bundled-plugins.xml`：

```xml
<?xml version="1.0"?>
<bundled-plugins>
  <plugin id="com.example.myplugin" path="my-plugin"/>
  <plugin id="com.example.tools" path="tools-plugin.jar"/>
</bundled-plugins>
```

当前实现会读取其中的插件 ID 和可选的 `path`。带 `path` 的条目会先按 XML 顺序解析，`path` 相对插件目录，可以指向插件目录或单 JAR 插件；随后才扫描插件目录下未列出的插件。插件描述符解析完成后，仍然以 `id` 匹配来把 `PluginDescriptor.isBundled()` 标记为 `true`。不带 `path` 的旧格式仍兼容，但只能参与 bundled 身份标记，不能参与定位和优先发现。bundled 插件目前还没有额外权限或保护策略。

## plugin.xml 格式

插件描述符根元素为 `<deskframe-plugin>`。最小可用格式如下：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<deskframe-plugin>
    <id>com.example.myplugin</id>
    <name>My Plugin</name>
    <version>1.0.0</version>

    <extensions>
        <extension point="demo.greeting"
                   implementation="com.example.MyGreeting"/>

        <applicationService serviceInterface="com.example.MyService"
                            serviceImplementation="com.example.MyServiceImpl"/>
    </extensions>
</deskframe-plugin>
```

支持的元数据：

| 元素或属性 | 必填 | 说明 |
|---|---|---|
| `<id>` | 是 | 插件唯一 ID。 |
| `<name>` | 是 | 展示名称。 |
| `<version>` | 否 | 版本号，缺省为 `0.0.0`。 |
| `<description>` | 否 | 插件说明。 |
| `<vendor>` | 否 | 供应商名称，可带 `email` 和 `url` 属性。 |
| `<depends>` | 否 | 依赖插件 ID，可出现多次。 |
| `<extensionPoints>` | 否 | 本插件声明的新扩展点。 |
| `<extensions>` | 否 | 本插件贡献的扩展和应用级 service。 |

`<depends>` 默认指向 plugins 目录中能发现的其他插件 ID。`com.jdesk.core` 是内置平台依赖，视为总是已满足；其他未知依赖会让当前插件进入 `FAILED` 状态。

完整示例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<deskframe-plugin>
    <id>com.example.greeter</id>
    <name>Greeter Plugin</name>
    <version>1.0.0</version>
    <description>Provides greetings and a greeter service.</description>
    <vendor email="dev@example.com" url="https://example.com">Example Team</vendor>

    <depends>com.jdesk.core</depends>

    <extensionPoints>
        <extensionPoint name="example.customGreeting"
                        interface="com.example.CustomGreetingProvider"/>
    </extensionPoints>

    <extensions>
        <extension point="deskframe.demo.greeting"
                   implementation="com.example.DemoGreetingProvider"
                   id="exampleGreeting"/>

        <applicationService serviceInterface="com.example.GreeterService"
                            serviceImplementation="com.example.GreeterServiceImpl"/>
    </extensions>
</deskframe-plugin>
```

## 生命周期阶段

### loadPlugins()

`loadPlugins()` 负责发现和解析插件描述符：

1. 确保插件目录存在。
2. 读取 `bundled-plugins.xml`。
3. 扫描插件目录下的目录和 jar 入口。
4. 对目录型插件解析 `META-INF/plugin.xml` 或 `plugin.xml`，对单 JAR 插件解析 jar 内同名描述符。
5. 构造 `PluginDescriptor`。
6. 初始状态写为 `UNLOADED`。

这一阶段只读取 XML 和文件路径，不创建插件类加载器，也不注册插件贡献的扩展实现。

### initPlugins()

`initPlugins()` 按依赖拓扑顺序处理插件：

1. 校验插件依赖图。
2. 对缺失依赖、循环依赖、依赖已失败的插件标记 `FAILED`，并跳过后续初始化。
3. 为可加载插件创建 `PluginClassLoader`，其 parent 是 `Application.getApplicationClassLoader()`。
4. 使用插件 classloader 注册插件自己声明的 `<extensionPoint>`。
5. 将可加载插件状态置为 `LOADED`。

### startPlugins()

`startPlugins()` 同样按依赖拓扑顺序启动插件：

1. 把 `<extension>` 注册为 lazy `ExtensionDescriptor`。
2. 把 `<applicationService>` 注册为 lazy `ServiceDescriptor`。
3. 记录每个插件注册过的扩展和 service，供停止时清理。
4. 将插件状态置为 `STARTED`。

服务接口类找不到时只记录告警并跳过该服务。**服务注册失败（含 `preload="await"` 同步实例化时构造器抛异常，以及插件类加载、初始化报 `LinkageError`，如 `NoClassDefFoundError`、`ExceptionInInitializerError`、`UnsupportedClassVersionError`）会让整个插件启动失败**：

1. 回滚本次启动登记的全部 service 与扩展（已实例化的 service 会被 dispose；带 `@State` 的 service 先存一次状态再移出 `ComponentStore`）。
2. 反注册该插件声明的扩展点，并释放其 `PluginClassLoader`。
3. 插件状态置为 `FAILED`，与加载期失败保持同一资源语义——不占用任何运行期资源。
4. 所有尚未启动的（直接或间接）依赖方同样释放资源并置为 `FAILED`。
5. 异常不会向 `startPlugins()` 传播，其余无依赖关系的插件照常启动。内存溢出这类其他 Error 仍然放行。

插件声明的扩展点接口、插件扩展在注册时报 `LinkageError`，与报 `RuntimeException` 一样只记录日志并跳过该扩展点或扩展，插件本身继续加载。

普通扩展和应用 service 在启动时都不会立即实例化实现类（`preload` 属性除外，见 service 容器文档）：

- `<extension>` 只保存实现类名、扩展接口、classloader 和可选 ID。
- `<applicationService>` 只保存 service 接口 class、实现类名和 classloader。

真正加载实现类的时间点分别是：

- 扩展：第一次查询对应扩展点时。
- Service：第一次调用 `app.getService(ServiceInterface.class)` 时。

### stopPlugin(pluginId)

`stopPlugin()` 只对 `STARTED` 状态的插件生效。当前停止顺序是：

1. 状态切换为 `STOPPING`。
2. 反向注销该插件注册过的应用级 service。
3. 对已经创建的 service 实例执行销毁。
4. 注销该插件注册过的 lazy 扩展描述符。
5. 状态切换为 `STOPPED`。

Service 清理语义：

- 如果插件 service 还没有被创建，只移除 `ServiceDescriptor`。
- 如果已经创建，则从 `ServiceContainer` 缓存中移除。
- 如果实例是带 `@State` 的持久化组件，销毁前先存一次状态，再移出 `ComponentStore`，插件的类加载器不再被它引用，之后同名组件可以重新注册。
- 如果实例实现了 `Disposable`，通过 `Disposer.dispose(instance)` 销毁，因此 service 下注册的 child disposable 会先被递归释放。
- 如果实例实现了 `AutoCloseable`，会调用 `close()`。
- 如果同一个实例还被其他 service key 注册，反注册其中一个 key 不会提前销毁该实例。

扩展清理语义：

- 如果 lazy 扩展尚未解析，只移除 pending descriptor。
- 如果 lazy 扩展已经实例化，会按注册时保存的 descriptor 身份从扩展点中移除对应实例并通知 listener。
- 当前扩展实例自身没有统一接入 `Disposer`，如果扩展对象持有外部资源，应优先放进插件 service 或后续扩展生命周期机制中管理。

### unloadPlugin(pluginId)

`unloadPlugin()` 用于释放插件类加载器：

1. 如果插件仍是 `STARTED`，先调用 `stopPlugin(pluginId)`。
2. 状态切换为 `UNLOADING`。
3. 反注册该插件在 `initPlugins()` 阶段声明的扩展点，并释放扩展点内部的 lazy descriptor、已解析扩展实例和 listener。
4. 从 classloader map 中移除 `PluginClassLoader`。
5. 调用 `PluginClassLoader.dispose()`，内部会 `close()` URLClassLoader 并清空加载类记录。
6. 状态切换为 `UNLOADED`。

`unloadPlugin()` 不会删除 `PluginDescriptor`，因此 `getAllPlugins()` 仍能看到已发现的插件描述符。

### dispose()

`PluginManager.dispose()` 会按反向拓扑顺序卸载所有插件，然后清空内部状态。`PluginManager` 自身作为应用 service，会随 `ServiceContainer.dispose()` 一起释放。

## 插件状态

`PluginState` 当前包含：

| 状态 | 含义 |
|---|---|
| `UNLOADED` | 描述符已解析或未知插件默认状态，但 classloader 不可用。 |
| `LOADED` | classloader 已创建，插件类可加载。 |
| `STARTED` | 扩展和 service descriptor 已注册，插件处于活动状态。 |
| `STOPPING` | 正在停止，清理扩展和 service。 |
| `STOPPED` | 已停止，classloader 仍可用。 |
| `UNLOADING` | 正在卸载，准备释放 classloader。 |
| `FAILED` | 插件描述符、依赖图无效，或启动阶段失败（资源已回滚释放）；不会创建/保留 classloader，也不会启动。 |

常用查询：

```java
PluginManager pm = app.getService(PluginManager.class);

List<PluginDescriptor> all = pm.getAllPlugins();
List<PluginDescriptor> started = pm.getStartedPlugins();

PluginDescriptor descriptor = pm.getPlugin("com.example.greeter");
PluginState state = pm.getPluginState("com.example.greeter");

boolean startedNow = pm.isPluginStarted("com.example.greeter");
boolean classLoaderAvailable = pm.isPluginLoaded("com.example.greeter");
PluginClassLoader loader = pm.getPluginClassLoader("com.example.greeter");
```

`getPluginState()` 对未知 ID 返回 `UNLOADED`。`isPluginLoaded()` 在 `LOADED`、`STARTED`、`STOPPED` 时返回 `true`，对 `FAILED` 返回 `false`。

## 扩展点使用方式

### 声明扩展点

扩展点可以由 core、frame、应用或插件声明。core 内置扩展点由 `ApplicationCore` 直接注册；frame 层扩展点位于 `jdesk-framework-frame/src/main/resources/META-INF/frame-plugins.xml`，由 `JdeskApplication.init()` 加载。

应用扩展点位于宿主应用 classpath 的 `META-INF/app-plugins.xml`，由 `ApplicationConfigLoader` 在 `JdeskApplication.init()` 中加载。Demo 应用的文件位于 `jdesk-framework-demo/src/main/resources/META-INF/app-plugins.xml`。

Java 代码中也可以直接注册：

```java
app.getExtensionRegistry().registerExtensionPoint(
        "deskframe.demo.greeting",
        GreetingProvider.class
);
```

### 插件贡献扩展

插件通过 `<extension>` 向扩展点贡献实现：

```xml
<extensions>
    <extension point="deskframe.demo.greeting"
               implementation="com.jdesk.demo.plugin.DemoGreeterExtension"/>
</extensions>
```

消费扩展：

```java
app.getExtensionRegistry().forEachExtensionSafe(
        "deskframe.demo.greeting",
        GreetingProvider.class,
        provider -> System.out.println(provider.getGreeting())
);
```

第一次消费扩展时，`ExtensionPointImpl` 会把 lazy descriptor 解析为真实实例。解析失败会记录错误并移除失败的 descriptor。

## applicationService 使用方式

插件通过 `<applicationService>` 注册应用级服务：

```xml
<extensions>
    <applicationService serviceInterface="com.example.MyService"
                        serviceImplementation="com.example.MyServiceImpl"
                        preload="async"/>
</extensions>
```

使用方式和普通应用 service 一样：

```java
MyService service = app.getService(MyService.class);
```

懒加载检查：

```java
MyService before = app.getServiceContainer().getServiceIfCreated(MyService.class);
// before == null 表示 descriptor 已注册，但实例还未创建

MyService service = app.getService(MyService.class);
```

`PluginManagerImpl.startPlugin()` 在注册 service 时会先加载 service interface class，因为它要作为 `ServiceContainer` 的 key。service implementation class 不会在此时加载，而是保存在 `ServiceDescriptor` 中。

`<applicationService>` 支持可选 `preload` 属性：

| 值 | 含义 |
|---|---|
| 缺省或 `none` | 保持首次 `getService()` 时懒加载。 |
| `await` | service descriptor 注册后立即同步创建，会阻塞当前插件启动。 |
| `async` | service descriptor 注册后提交后台创建任务，不阻塞当前插件启动。 |

插件按照依赖拓扑顺序启动，因此某个插件服务的 `await` preload 只能依赖已经启动的插件贡献。如果它需要另一个插件的 service 或 extension，应通过 `<depends>` 显式声明依赖，让被依赖插件先启动。

插件停止后：

```java
pm.stopPlugin("com.example.greeter");

boolean exists = app.getServiceContainer().hasService(MyService.class); // false
MyService created = app.getServiceContainer().getServiceIfCreated(MyService.class); // null
```

再次调用 `app.getService(MyService.class)` 会抛出 `IllegalStateException`，因为 service 已反注册。

## ClassLoader 语义

每个插件有自己的 `PluginClassLoader`。加载顺序大致为：

1. 已加载类缓存。
2. JDK 和平台包：委托父 classloader。
3. 框架 API 包：委托父 classloader，确保插件看到同一套框架接口类型。
4. 插件自身 classpath：`lib/*.jar` 和 `classes/`。
5. 依赖插件 classloader。
6. 应用 classloader。

框架 API 包即 core 模块的全部包，按以下前缀匹配：

```text
com.jdesk.core.
com.jdesk.concurrency.
com.jdesk.util.
```

这意味着插件实现类可以实现 core 框架中的接口，且类型检查会落在同一个父 classloader 的接口定义上。`jdesk-framework-frame` 属于上层应用框架模块，插件需要使用其 API 时应通过宿主应用 classpath 提供，不由 core classloader 白名单感知。

core 公共接口用到的三方库同样父优先，插件即使自带也使用宿主那一份：

```text
kotlin.
kotlinx.coroutines.
org.slf4j.
```

core 接口暴露了协程类型（`Deferred`、`Job`、挂起函数），插件若用自带的 Kotlin 标准库或协程库，调用这些接口会报 `LinkageError`；插件若自带 slf4j，日志会绑定不到宿主的实现而丢失。宿主不带的库（例如 `kotlinx.serialization`）不在此列，仍从插件自己的 classpath 加载。

插件声明自己的 extension point 时，接口会通过该插件的 `PluginClassLoader` 解析；接口可以放在插件 JAR 内。插件贡献到宿主已经声明的 extension point 时，实现类通过插件 classloader 懒加载，宿主接口仍来自父 classloader。

## 依赖和拓扑顺序

插件可以通过 `<depends>` 声明依赖：

```xml
<depends>com.example.base</depends>
```

`PluginManagerImpl` 在 `initPlugins()`、`startPlugins()` 和 `dispose()` 中都会使用拓扑顺序：

- 初始化和启动：被依赖的插件（前置条件）先处理，依赖它的插件后处理。
- dispose：反向拓扑顺序，先卸载依赖者，再卸载被依赖者。

如果依赖 ID 未发现、依赖形成环，或某个依赖已经进入 `FAILED`，当前插件会被标记为 `FAILED` 并跳过初始化和启动。`com.jdesk.core` 是内置平台依赖，视为始终满足。

## 在 Demo 中的实际使用

Demo 示例插件位于：

```text
jdesk-framework/jdesk-framework-demo/demo-greeter/src/main/resources/META-INF/plugin.xml
jdesk-framework/.root/demo-app/plugins/demo-greeter.jar
```

它贡献了两个东西：

```xml
<extension point="deskframe.demo.greeting"
           implementation="com.jdesk.demo.plugin.DemoGreeterExtension"/>

<applicationService serviceInterface="com.jdesk.demo.plugin.PluginGreeterService"
                    serviceImplementation="com.jdesk.demo.plugin.PluginGreeterServiceImpl"/>
```

Demo 中的 `Tools -> Plugin Manager...` 使用：

```java
PluginManager pm = ctx.getApplication().getService(PluginManager.class);

for (PluginDescriptor pd : pm.getAllPlugins()) {
    String state = pm.getPluginState(pd.getPluginId()).name();
    int extensionCount = pd.getExtensions().size();
}
```

Demo 中的 `Tools -> Test Lazy Plugin Service...` 使用：

```java
PluginGreeterService beforeSvc = app.getServiceContainer()
        .getServiceIfCreated(PluginGreeterService.class);

PluginGreeterService svc = app.getService(PluginGreeterService.class);

String text = svc.greet("World");
```

这里 `beforeSvc == null` 表示 service descriptor 已经注册，但实现类还没有实例化。第一次 `app.getService(PluginGreeterService.class)` 会触发 `PluginGreeterServiceImpl` 构造。

## 常见使用场景

### 查看所有插件

```java
PluginManager pm = app.getService(PluginManager.class);

for (PluginDescriptor plugin : pm.getAllPlugins()) {
    System.out.printf(
            "%s %s %s%n",
            plugin.getPluginId(),
            plugin.getVersion(),
            pm.getPluginState(plugin.getPluginId())
    );
}
```

### 判断插件是否活动

```java
if (pm.isPluginStarted("com.example.greeter")) {
    // 扩展和 service descriptor 已注册
}
```

### 停止插件

```java
pm.stopPlugin("com.example.greeter");
```

停止后：

- 插件贡献的应用级 service 被反注册。
- 已创建的插件 service 被 dispose 或 close。
- 插件贡献的 lazy 扩展被移除。
- classloader 仍保留。
- 状态为 `STOPPED`。

### 卸载插件

```java
pm.unloadPlugin("com.example.greeter");
```

卸载后：

- 如果插件还在 `STARTED`，会先 stop。
- classloader 被关闭并移除。
- 状态为 `UNLOADED`。
- 描述符仍在 `getAllPlugins()` 结果中。

### 重新启动已停止插件

当前 `startPlugins()` 会跳过已经 `STARTED` 的插件，但会重新处理 `STOPPED` 的插件。因此，一个已停止但尚未卸载的插件可以通过再次调用 `startPlugins()` 恢复注册：

```java
pm.stopPlugin("com.example.greeter");
pm.startPlugins();
```

这会对所有未处于 `STARTED` 状态的已发现插件执行启动逻辑。当前还没有公开的单插件 `startPlugin(pluginId)` API。

对于已经 `unloadPlugin()` 的插件，当前不支持热重载：`loadPlugins()` / `initPlugins()` 不做幂等守卫，对已加载或已启动插件重复调用会导致类加载器泄漏、扩展点注册记录丢失、插件状态被错误覆盖等资源不一致。需要重新加载插件时，应重启应用进程。

## 错误处理和注意事项

- `PluginManager` 被 dispose 后，再调用生命周期方法会抛出 `IllegalStateException`。
- `loadPlugins()` / `initPlugins()` 必须各自只调用一次；对已加载或已启动插件重复调用会造成资源不一致（见上文热重载说明）。当前仓内所有调用方（frame 启动序列、`TestApplication`）均满足单次调用约束。
- 插件目录不存在时会自动创建。
- 单个插件描述符解析失败会记录错误，并跳过该插件。
- `<extension>` 指向不存在的扩展点时会记录 warn，该扩展不会注册。
- `<applicationService>` 的 service interface 找不到时会记录 warn，该 service 不会注册。
- 插件 service implementation 找不到或实例化失败，会在首次 `getService()` 时抛出运行时异常。
- `getPluginState(unknownId)` 返回 `UNLOADED`，这不代表真的发现过该插件。
- 当前没有插件启用/禁用持久化列表，也没有版本兼容性检查。
- 插件依赖图会在初始化前严格校验；缺失依赖、循环依赖或依赖已失败的插件会进入 `FAILED`，不会创建 classloader，也不会启动。
- 启动阶段插件服务注册失败（含 `preload="await"` 实例化抛异常）时，该插件回滚登记并释放资源后进入 `FAILED`，尚未启动的依赖方级联 `FAILED`，其余插件照常启动。
- 当前扩展实例没有统一 dispose 生命周期，资源型扩展建议通过插件 service 间接管理。

## 推荐实践

- 宿主应用自己的扩展点优先放在 `META-INF/app-plugins.xml` 中声明。只有直接使用 `JdeskApplication.init()` / `start()` 分离启动时，才适合在 `init()` 之后、`start()` 之前手动注册扩展点。
- 插件服务优先使用接口访问，不要依赖实现类。
- 插件中持有资源的对象优先实现 `Disposable`，并作为 `applicationService` 管理。
- 插件 service 如果创建 child disposable，应注册到 service 自身下；插件停止时会通过 `Disposer.dispose(service)` 递归释放。
- 对需要动态卸载的插件，避免把插件实现对象缓存到全局静态字段或长生命周期单例中。
- 如果插件接口属于框架或宿主应用 API，应放在应用或框架 classpath 中，避免接口类由插件私有 classloader 重复定义。
- 插件依赖框架、Kotlin 标准库、kotlinx.coroutines、slf4j 时用 `provided`，不要把这些 jar 打进插件或插件的 `lib/`。
- 新增插件能力时，建议补充类似 `PluginManagerImplServiceUnloadTest` 的集成测试，覆盖停止和卸载后的清理语义。

## 当前边界

当前插件机制已经覆盖目录型插件和单 JAR 插件发现、bundled 插件注册表、描述符解析、依赖拓扑校验、classloader 创建、插件声明扩展点、lazy 扩展、lazy applicationService、插件停止清理 service 和扩展、插件卸载释放 classloader。

仍可继续演进的方向：

- 引入版本约束、可选依赖和兼容性检查。
- 增加单插件启动或 reload API。
- 为扩展实例引入更明确的 dispose 生命周期。
- 增加插件启用/禁用状态持久化。

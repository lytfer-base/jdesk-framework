# XML 配置文件使用说明

本文总结 JDesk Framework 当前几类 XML 文件的用途和写法：

- `frame-plugins.xml`：frame 层的扩展点和应用级服务声明。
- `app-plugins.xml`：宿主应用自身的扩展点、内置扩展和应用级服务声明。
- `plugin.xml`：单个插件的描述符。
- `bundled-plugins.xml`：插件目录下的 bundled 插件登记表。

这些文件职责不同。`frame-plugins.xml` 定义 Swing frame 层提供哪些扩展能力和内置服务；`app-plugins.xml` 定义宿主应用向框架声明的应用级扩展点、内置扩展和服务；`plugin.xml` 定义某个插件是什么、依赖谁、贡献什么；`bundled-plugins.xml` 告诉插件管理器哪些插件属于随应用分发的 bundled 插件。

所有这些 DOM 配置解析都通过 `SecureXmlParser` 完成。解析器会启用 secure processing，拒绝 `DOCTYPE`，禁用外部 general/parameter entity、外部 DTD 和外部 schema 访问；因此 XML 配置文件不能依赖外部实体展开。

## 加载顺序

应用初始化和启动时的大致顺序如下：

```text
new JdeskApplication()
  -> ApplicationInfoLoader.load(applicationClassLoader)
       -> 读取 META-INF/application-info.xml
       -> PathManager.setApplicationIdForCurrentProcess(applicationInfo.id)
  -> ApplicationStartupConfig.load()
       -> 读取 <home>/system/application.properties
       -> 写入 RuntimeParamRegistry
  -> StartupTrace.create()

JdeskApplication.init()
  -> ApplicationManager.setApplication(app)
  -> PathManager.getPluginsDir()
  -> ApplicationCore.initializeCoreServices()
       -> 注册 core 内置 extensionPoint 和 applicationService 描述符
       -> 注册 VFS 相关实例服务
  -> parallel descriptor parsing
       -> FrameConfigLoader.parseFrameConfiguration()
            -> 解析 META-INF/frame-plugins.xml 为模型，不注册运行时状态
       -> ApplicationConfigLoader.parseApplicationConfiguration()
            -> 解析 META-INF/app-plugins.xml 为模型，不注册运行时状态
       -> PluginManager.loadPlugins()
            -> 读取 <plugins>/bundled-plugins.xml
            -> 扫描插件目录或单 JAR
            -> 并行解析插件 plugin.xml
  -> ordered descriptor apply
       -> FrameConfigLoader.applyFrameConfiguration()
            -> 注册 frame 级 extensionPoint 和 applicationService 描述符
       -> ApplicationConfigLoader.applyApplicationConfiguration()
            -> 注册应用级 extensionPoint、应用内置 lazy extension 和 applicationService 描述符
  -> PluginManager.initPlugins()
       -> 创建插件 classloader
       -> 注册插件声明的 extensionPoint
  -> ApplicationCore.initializeContainers()
       -> ExtensionRegistry.initialize()
       -> ServiceContainer.initialize()

JdeskApplication.start()
  -> RuntimeParamRegistry.completeBoot()
  -> PluginManager.startPlugins()
       -> 注册插件贡献的 extension
       -> 注册插件贡献的 applicationService 描述符
```

注意：`applicationService` 在 XML 中声明为扩展点，但当前实现会对 `<applicationService>` 标签做专门解析，最终注册到 `ServiceContainer`，而不是作为普通 extension instance 放入 `ExtensionRegistry`。

## frame-plugins.xml

位置：

```text
jdesk-framework-frame/src/main/resources/META-INF/frame-plugins.xml
```

运行时通过 classpath 读取：

```java
appClassLoader.getResourceAsStream("META-INF/frame-plugins.xml")
```

根元素：

```xml
<frame-plugins>
    ...
</frame-plugins>
```

### extensionPoints

`<extensionPoints>` 用于声明 frame 层提供的扩展点。当前支持两种声明风格。

`interface` 用于行为型扩展点，表示贡献的实现类需要实现该接口：

```xml
<extensionPoint name="my.feature"
                interface="com.example.MyFeature"/>
```

当前 `frame-plugins.xml` 已声明 `deskframe.applicationActivity` 和 `deskframe.postStartupActivity` 两个行为型扩展点，用于让应用或插件贡献首窗显示前后的启动活动。Demo 应用自己的 `deskframe.demo.greeting` 和 `deskframe.demo.toolWindow` 位于 `app-plugins.xml`。

`beanClass` 用于描述型扩展点。当前实现会把它作为扩展点的 contract class 注册：

```xml
<extensionPoint name="my.frameFeature"
                beanClass="com.example.MyFrameFeatureDeclaration"
                dynamic="true"/>
```

需要注意：当前还没有通用的“把任意 XML 属性解析成 bean extension 实例”的机制。`applicationService` 是专门处理的标签，解析器会把它转换成服务注册描述：

```text
serviceInterface -> serviceImplementation
```

`dynamic="true"` 当前主要是声明语义，表示这个扩展点设计上允许动态注册/卸载。实际动态卸载能力由 `PluginManagerImpl.stopPlugin()` 和 `ServiceContainer.unregisterService()` 等代码配合完成。

### extensions

`<extensions>` 用于 frame 层向已声明扩展点贡献内置实现。`FrameConfigLoader` 会解析其中的普通 `<extension>` 与 `<applicationService>`：前者被登记到 `ExtensionRegistry`，后者被登记为服务描述符。普通扩展仍按扩展注册表的惰性加载语义创建实现。

frame 内置应用级服务示例：

```xml
<extensions>
    <applicationService serviceInterface="com.jdesk.frame.action.ActionManager"
                        serviceImplementation="com.jdesk.frame.action.ActionManager"/>

    <applicationService serviceInterface="com.jdesk.frame.settings.SettingsManager"
                        serviceImplementation="com.jdesk.frame.settings.SettingsManager"/>

    <applicationService serviceInterface="com.jdesk.frame.laf.JdfLafManager"
                        serviceImplementation="com.jdesk.frame.laf.JdfLafManager"/>

    <applicationService serviceInterface="com.jdesk.frame.icon.JdfIconManager"
                        serviceImplementation="com.jdesk.frame.icon.JdfIconManager"/>

    <applicationService serviceInterface="com.jdesk.frame.swing.SwingUiDispatcher"
                        serviceImplementation="com.jdesk.frame.swing.SwingUiDispatcher"/>

    <applicationService serviceInterface="com.jdesk.frame.state.WindowStateService"
                        serviceImplementation="com.jdesk.frame.state.WindowStateService"/>
</extensions>
```

core 相关服务（例如 `MessageBusManager`、`PluginManager`、`VirtualFileManager`）不再通过 XML 声明，而是由 `ApplicationCore` 直接注册。

这些服务会被注册为按类名创建的 service descriptor。缺省情况下只保存接口类和实现类名；首次调用：

```java
app.getService(ActionManager.class)
```

时才加载实现类并创建实例。

`<applicationService>` 支持可选的 `preload` 属性：

| 值 | 含义 |
|---|---|
| 缺省或 `none` | 不在启动期创建服务，保持首次访问时懒加载。 |
| `await` | 在服务容器初始化边界同步创建服务。 |
| `async` | 在服务容器初始化后提交后台创建任务，不阻塞启动边界。 |

`preload` 取值大小写不敏感；非法值会记录 warn 并按 `none` 处理。frame/app 服务的 preload 发生在插件贡献注册之前，因此这些服务的构造逻辑不应依赖插件 `<extension>` 或插件 `<applicationService>`。

如果实现类声明单参数 `Application` 构造器：

```java
public PluginManagerImpl(Application app)
```

服务描述符创建实例时会通过 `ApplicationManager.getApplication()` 获取当前应用并注入。`Application` 本身不注册为普通 service。

## plugin.xml

`plugin.xml` 是单个插件的描述符。当前支持目录型插件和单 JAR 插件。

目录型插件结构：

```text
<plugins>/
└── my-plugin/
    ├── META-INF/
    │   └── plugin.xml
    ├── lib/
    │   └── my-plugin.jar
    └── classes/
        └── ...
```

单 JAR 插件结构：

```text
<plugins>/
└── my-plugin.jar
    └── META-INF/plugin.xml
```

查找规则：

- 目录型插件优先读取 `my-plugin/META-INF/plugin.xml`。
- 如果不存在，再读取 `my-plugin/plugin.xml`。
- `lib/*.jar` 会加入插件 classpath。
- `classes/` 如果存在，也会加入插件 classpath。
- 单 JAR 插件优先读取 jar 内 `META-INF/plugin.xml`，如果不存在再读取 jar 根目录的 `plugin.xml`。
- 单 JAR 插件会将该 jar 自身加入插件 classpath。

根元素：

```xml
<deskframe-plugin>
    ...
</deskframe-plugin>
```

最小示例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<deskframe-plugin>
    <id>com.example.myplugin</id>
    <name>My Plugin</name>
    <version>1.0.0</version>

    <extensions>
        <extension point="deskframe.demo.greeting"
                   implementation="com.example.MyGreeting"/>

        <applicationService serviceInterface="com.example.MyService"
                            serviceImplementation="com.example.MyServiceImpl"/>
    </extensions>
</deskframe-plugin>
```

### 元数据

支持的基本元素：

| 元素或属性 | 必填 | 说明 |
|---|---|---|
| `<id>` | 是 | 插件唯一 ID。 |
| `<name>` | 是 | 插件显示名称。 |
| `<version>` | 否 | 插件版本，缺省为 `0.0.0`。 |
| `<description>` | 否 | 插件说明。 |
| `<vendor>` | 否 | 供应商名称，可带 `email` 和 `url` 属性。 |
| `<depends>` | 否 | 依赖插件 ID，可出现多次。 |
| `<extensionPoints>` | 否 | 本插件声明的新扩展点。 |
| `<extensions>` | 否 | 本插件贡献的扩展实现和应用级服务。 |

示例：

```xml
<vendor email="dev@example.com" url="https://example.com">Example Inc.</vendor>
<depends>com.example.base</depends>
```

当前 `<depends>` 用于插件依赖拓扑校验和依赖插件 classloader 查找。`com.jdesk.core` 被视为内置平台依赖；其他缺失依赖、循环依赖或依赖已失败会让插件进入 `FAILED` 状态，不会创建 classloader，也不会启动。版本约束、可选依赖等语义尚未实现。

### 声明扩展点

插件可以声明自己的扩展点：

```xml
<extensionPoints>
    <extensionPoint name="my.greeter"
                    interface="com.example.Greeter"/>

    <extensionPoint name="my.config"
                    beanClass="com.example.MyConfigBean"
                    dynamic="true"/>
</extensionPoints>
```

当前解析器支持 `interface` 和 `beanClass` 两种属性。`dynamic` 属性会被保留为 XML 语义，但当前 `PluginDescriptor.ExtensionPointDeclaration` 只记录扩展点名称和扩展类名，尚未记录 dynamic 标记。

插件声明的 extension point 接口会在 `PluginManager.initPlugins()` 中使用声明该扩展点的插件 classloader 解析。因此接口类可以放在插件 JAR 内；如果它依赖其他插件中的类型，则需要通过 `<depends>` 建立依赖关系。

### 贡献普通扩展

普通扩展使用 `<extension>`：

```xml
<extensions>
    <extension point="my.greeter"
               implementation="com.example.DefaultGreeter"
               id="defaultGreeter"/>
</extensions>
```

含义：

- `point`：目标扩展点名称。
- `implementation`：实现类全限定名。
- `id`：可选扩展 ID。

普通扩展在 `PluginManager.startPlugins()` 阶段注册为 lazy extension。实现类不会在解析 `plugin.xml` 时立即加载，而是在扩展点首次被查询时创建。

### 贡献应用级服务

应用级服务使用 `<applicationService>`：

```xml
<extensions>
    <applicationService serviceInterface="com.example.MyService"
                        serviceImplementation="com.example.MyServiceImpl"
                        preload="async"/>
</extensions>
```

含义：

- `serviceInterface`：服务接口全限定类名，作为 `ServiceContainer` 的 key。
- `serviceImplementation`：服务实现类全限定类名，首次 `getService()` 时加载并实例化。
- `preload`：可选，取值为 `none`、`await` 或 `async`；缺省为 `none`。

插件服务在 `PluginManager.startPlugins()` 阶段注册为按类名创建的 service descriptor。注册时会立即应用 preload 策略：`await` 会阻塞当前插件启动，`async` 会提交后台创建任务。插件停止时，`PluginManager.stopPlugin()` 会注销这些服务，并 dispose 已创建的服务实例。

## bundled-plugins.xml

位置：

```text
<plugins>/bundled-plugins.xml
```

其中 `<plugins>` 来自 `PathManager.getPluginsDir()`。默认路径：

| 场景 | 插件目录 |
|---|---|
| 开发模式 | `<项目根>/.root/<appId>/plugins/` |
| 生产模式 | `<user.dir>/plugins/` |

开发模式指工作目录或它的某一级上层目录里有 `.root/`，离工作目录最近的那一层就是项目根（见 `PathManager`）。`<appId>` 来自宿主应用 `META-INF/application-info.xml` 根元素的 `id` 属性。

示例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<bundled-plugins>
    <plugin id="com.example.base" path="base-plugin"/>
    <plugin id="com.example.tools" path="tools-plugin.jar"/>
</bundled-plugins>
```

作用：

- 带 `path` 的条目会先按 XML 顺序解析。`path` 相对插件目录，可以指向插件目录或单 JAR 插件。
- 未在 `bundled-plugins.xml` 中通过 `path` 列出的插件，会在后续普通扫描中发现。
- 插件描述符解析完成后，如果 `plugin.xml` 中的 `<id>` 出现在 `bundled-plugins.xml` 中，则 `PluginDescriptor.isBundled()` 会被标记为 `true`。
- 不带 `path` 的旧格式仍兼容，只参与 bundled 身份标记，不能参与路径定位或优先发现。
- 插件依赖顺序仍应使用 `<depends>` 表达；`bundled-plugins.xml` 的顺序只影响发现顺序，不替代依赖排序。

当前限制：

- bundled 插件当前没有额外权限或保护策略，只是身份标记。

## app-plugins.xml

位置：

```text
jdesk-framework-demo/src/main/resources/META-INF/app-plugins.xml
```

运行时由 `ApplicationConfigLoader` 在 `JdeskApplication.init()` 阶段通过 application classloader 读取：

```java
applicationClassLoader.getResourceAsStream("META-INF/app-plugins.xml")
```

根元素：

```xml
<app-plugins>
    ...
</app-plugins>
```

它声明宿主应用自己的扩展点：

```xml
<extensionPoints>
    <extensionPoint name="deskframe.demo.greeting"
                    interface="com.jdesk.demo.GreetingProvider"/>

    <extensionPoint name="deskframe.demo.toolWindow"
                    interface="com.jdesk.demo.ToolWindowProvider"/>
</extensionPoints>
```

并注册应用内置扩展：

```xml
<extensions>
    <extension point="deskframe.demo.greeting"
               implementation="com.jdesk.demo.builtin.SystemInfoGreetingProvider"/>
</extensions>
```

`app-plugins.xml` 里的内置 `<extension>` 会注册为 lazy `ExtensionDescriptor`。实现类不会在 `init()` 阶段立即实例化，而是在对应扩展点首次被查询时加载。

`app-plugins.xml` 也可以声明应用级服务：

```xml
<extensions>
    <applicationService serviceInterface="com.example.AppService"
                        serviceImplementation="com.example.AppServiceImpl"/>
</extensions>
```

这些服务使用 application classloader 注册为按类名创建的 service descriptor，同样支持 `preload="none|await|async"`。frame/app 服务的 preload 在 `core.initializeContainers()` 阶段生效。

## 关系总览

```text
frame-plugins.xml
  定义 frame 层扩展点和内置 applicationService
  由 FrameConfigLoader 在 JdeskApplication.init() 中先解析成模型，再按顺序应用

app-plugins.xml
  定义宿主应用自己的扩展点、内置扩展和应用级服务
  由 ApplicationConfigLoader 在 JdeskApplication.init() 中先解析成模型，再按顺序应用

bundled-plugins.xml
  定义哪些插件属于随应用分发的 bundled 插件
  由 PluginManager 在 loadPlugins() 中读取

plugin.xml
  定义单个插件的元数据、依赖、扩展点、扩展和服务
  由 PluginManager 在 loadPlugins() 中解析

```

一句话区分：

```text
frame-plugins.xml 是 Swing frame 层自己的声明文件；
app-plugins.xml 是宿主应用自己的扩展声明文件；
plugin.xml 是插件自己的声明文件；
bundled-plugins.xml 是插件目录里的 bundled 身份和路径索引。
```

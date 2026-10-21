# Frame 图标系统

## 目标

图标系统是 frame 层的桌面应用基础设施，设计上参考 IntelliJ Platform 的
`ApplicationInfo`、`IconManager`、`IconLoader` 以及生成式 `AllIcons` 模型。
当前实现会保持 JDesk 自身结构足够小：

- `jdesk-framework-core` 负责不依赖 Swing 的产品元数据。
- `jdesk-framework-frame` 负责图标加载、缓存以及窗口图标接入。
- 应用通过 `META-INF/application-info.xml` 声明应用 ID、产品名称、版本和图标路径。

## 应用信息

`ApplicationInfo` 会在 `JdeskApplication` 构造阶段，从应用 classpath 上的
`META-INF/application-info.xml` 加载。这里会先读取应用 ID 并交给 `PathManager`，
之后 `ApplicationCore.initializeCoreServices()` 会注册同一个产品信息实例。
如果描述文件不存在，框架会注册一个安全的默认产品信息对象。

当前支持的描述字段：

```xml
<application-info id="demo-app">
    <names product="JDesk Framework" fullName="JDesk Framework — Phase 2"/>
    <version major="0" minor="2" suffix="Demo" build="phase-2"/>
    <company name="JDesk Lab" url="https://example.test"/>
    <description>Desktop application description.</description>
    <icon svg="icons/app.svg" svg-small="icons/app_16.svg"/>
</application-info>
```

`JdeskApplicationBuilder.create()` 会使用 `ApplicationInfo.fullProductName`
作为默认主窗口标题。`create(String title)` 仍然可以显式覆盖标题。

## JdfIconManager

`JdfIconManager` 是一个应用级服务，由
`jdesk-framework-frame/src/main/resources/META-INF/frame-plugins.xml` 注册。

当前已经实现：

- 按 classpath 路径加载图标。
- 支持 SVG、PNG、JPEG、GIF 资源。
- 按尺寸区分缓存 key。
- 暗色变体查找：优先尝试文件扩展名前的 `_dark` 版本。
- 按请求尺寸缩放图标。
- 生成禁用态和透明态图标变体。
- 应用图标辅助方法：
  - `getApplicationIcon(int size)`
  - `getSmallApplicationIcon()`
  - `getApplicationIconImages()`
  - `updateWindowIcon(Window)`
- 已接入主窗口和自定义标题栏中的产品图标。
- 已接入主题系统：当前主题变化时，`JdfLafManager` 会更新图标暗色状态并清空图标缓存。

## SVG 支持

当前 SVG 渲染器是一个只依赖 JDK 的子集渲染器。早期框架阶段刻意不引入 Batik
或其他 UI 库。

当前支持的 SVG 特性：

- `path`、`rect`、`circle`、`ellipse`、`line`、`polyline`、`polygon`、`g`。
- 路径命令：`M`、`L`、`H`、`V`、`C`、`S`、`Q`、`T`、`Z`。
- 弧线命令 `A` 暂时近似为一条连接到弧线终点的直线。
- `fill`、`stroke`、`stroke-width`、透明度、线帽、线连接、填充规则。
- 基础 `style` 属性。
- 基础 `translate`、`scale`、`rotate`、`matrix` 变换。
- 通过 `url(#id)` 引用简单渐变时，暂时使用第一个 stop 颜色。

需要持续记住的限制：

- 尚未实现完整 SVG 规范。
- 尚未精确渲染弧线。
- 尚未支持裁剪、遮罩、滤镜和真实渐变绘制。
- 除内联 `style` 外，尚未实现 CSS 级联。
- 尚未实现生成式强类型 `AllIcons` 等价物。
- 尚未实现插件级 icon path patcher 或主题调色板 patcher。
- 尚未实现文件、对象图标以及分层 overlay 图标 provider。

## 资源策略

Demo 应用的部分图标取自 IntelliJ Platform（Apache-2.0，出处见仓库根目录的 NOTICE），应用图标为自绘。

长期来看，应用特有资产应该放在各自 app 模块的 resources 中；可复用的框架图标
应该放在 `jdesk-framework-frame` 中。

## 后续工作

- 增加生成式 `JdfIcons` 目录或构建期图标索引。
- 增加类似 IntelliJ `IconPathPatcher` 的主题感知图标路径替换能力。
- 当真实产品图标需要时，补齐更完整的 SVG 支持。
- 增加文件类型、模块类型和状态 overlay 图标 provider。
- 增加扫描 classpath 图标资源的图标校验测试。
- 在 HiDPI 细节需要时，增加 `@2x` 和 scale context 感知加载。

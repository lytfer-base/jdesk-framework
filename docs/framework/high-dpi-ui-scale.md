# 高 DPI 与 UI 字体缩放

## 背景

JDesk Framework 在 Windows 高 DPI 环境下曾出现界面文字明显偏小的问题。表面上看像是 Java 高分辨率设置没有生效，但实际原因更接近 IntelliJ 里长期处理过的那类问题：Swing 的默认 Look and Feel 字体、系统 UI 字体、屏幕缩放、DPI 和 JRE 的 HiDPI 接管状态没有被统一建模。

这次修复后的原则是：默认尊重 JDK/OS 的高 DPI 行为，不依赖启动配置强行指定字体大小；只有用户在 `application.properties` 中显式写入 `jdesk.ui.scale=...` 时，才把它作为额外的用户缩放。

## IntelliJ 的参考思路

IntelliJ 启动时不是简单地设置一个全局字号，而是先建立基础 Look and Feel，然后预加载和修正 UI 缩放状态。

关键点有三类：

- `JBUIScale` 区分 `systemScale`、`userScale` 和 `pixelScale`。
- `JBUI` 把尺寸、边距、线宽、字体等入口统一收口到缩放 helper。
- `JreHiDpiUtil` 判断当前环境是否已经由 JRE 接管 HiDPI。

在字体方面，IntelliJ 会读取系统 UI 字体数据。Windows 下一个非常关键的来源是：

```java
Toolkit.getDefaultToolkit().getDesktopProperty("win.messagebox.font")
```

这个值通常比 Swing 当前 `UIManager.getFont("Label.font")` 更能代表 Windows 当前实际界面字体设置。

## 本机观测到的实际值

这台机器上排查时看到的典型值是：

| 项 | 实际值 | 含义 |
| --- | --- | --- |
| `UIManager.getFont("Label.font")` | `SimSun 7pt` | 系统 LAF 给出的 Swing 默认字体，明显偏小 |
| `win.messagebox.font` | `Microsoft YaHei UI 12pt` | Windows 实际系统消息框字体，更符合当前 UI 期望 |
| `GraphicsConfiguration.getDefaultTransform()` | `scaleX=1.75, scaleY=1.75` | JRE 已把逻辑坐标映射到 1.75 倍设备像素 |
| `Toolkit.getScreenResolution()` | `168 DPI` | `168 / 96 = 1.75`，与屏幕缩放一致 |

因此这次问题的关键不是屏幕缩放没有被识别，而是框架继续沿用了错误的 Swing 默认字体 `SimSun 7pt`。修复后应该优先使用 Windows 的实际系统 UI 字体 `win.messagebox.font`，再把它写回常见 Swing UI defaults。

## 核心概念

### 系统 UI 字体

`win.messagebox.font` 是 Windows 提供给 AWT/Swing 的桌面属性之一，表示系统消息框使用的字体。它包含字体族、样式和字号。

JDesk Framework 当前策略是：

- Windows 下优先使用 `win.messagebox.font` 作为基础 UI 字体。
- 如果当前 `Label.font` 明显异常，例如小于 `10pt`，也使用系统字体覆盖它。
- 把修正后的字体写入 `Label`、`Button`、`Menu`、`MenuItem`、`Tree`、`List`、`Table`、`TextField`、`ComboBox`、`ToolTip`、`TitledBorder` 等常见 Swing key。

### 屏幕缩放与 systemScale

屏幕缩放的首选读取方式是：

```java
graphicsConfiguration.getDefaultTransform().getScaleX()
```

这个 transform 表示 Java 逻辑坐标到设备像素的映射。例如 `1.75` 表示一个逻辑像素会映射到约 `1.75` 个设备像素。

在现代 Java 17 / JBR / Windows 环境中，如果这个值大于 `1.0`，通常说明 JRE 已经接管了 HiDPI。

### DPI 与 fallback scale

DPI 可以通过下面的方式读取：

```java
Toolkit.getDefaultToolkit().getScreenResolution()
```

传统 100% 缩放一般以 `96 DPI` 为基准：

```text
dpiScale = screenDpi / 96
```

例如：

```text
168 / 96 = 1.75
```

在 transform 不可用，或者 JRE 没有接管 HiDPI 的老环境中，DPI 可以作为 `systemScale` 的回退来源。

### userScale

`userScale` 表示用户层面的 UI 缩放，和系统屏幕缩放不是同一个概念。

JDesk Framework 现在的策略是：

```text
userScale = 显式 jdesk.ui.scale
         或 系统 UI 字体大小 / 默认系统字体大小
```

如果没有 `jdesk.ui.scale=...`，就根据系统 UI 字体大小推导。例如 Windows 默认基准为 `12pt`：

```text
Microsoft YaHei UI 12pt / 12pt = 1.0
```

如果系统字体变为 `15pt`：

```text
15pt / 12pt = 1.25
```

该值会按 `0.25` 离散化，并限制在 `0.75..4.0`。

### pixelScale

`pixelScale` 用于图像、图标、SVG raster backing 等需要真实设备像素清晰度的场景。

```text
pixelScale = systemScale * userScale
```

例如本机：

```text
systemScale = 1.75
userScale = 1.0
pixelScale = 1.75
```

这意味着请求一个 16 逻辑像素的 SVG 图标时，布局上仍然占 `16x16`，但内部可以用约 `28x28` 的 backing image 渲染，以保证高 DPI 下清晰。

## JRE-managed HiDPI

JRE-managed HiDPI 指的是 Java 运行时已经负责把 Swing/AWT 的逻辑坐标映射到高 DPI 设备像素。此时应用层不应该再把布局尺寸乘以系统屏幕缩放，否则会出现双重放大。

IntelliJ 的判断比较完整，不只是看 JDK 版本。它会综合：

- macOS / Wayland 等平台特征。
- JetBrains Runtime 的 `hidpi` 属性。
- `SunGraphicsEnvironment.isUIScaleEnabled()` 的反射结果。

JDesk Framework 第一版采用不依赖 JBR 私有 API 的简化判断：

```text
GraphicsConfiguration.defaultTransform.scaleX > 1.01
```

如果 transform 是 `1.75`，说明 JRE 已经按 175% 缩放接管绘制。

如果 transform 是 `1.0`，但 DPI 是 `144` 或 `168`，则更像是旧式或非 JRE-managed 环境，可以回退到 `DPI / 96` 作为系统缩放。

## 尺寸与字体的最终关系

JDesk Framework 当前使用下面的模型：

```text
systemScale = defaultTransform.scaleX
dpiScale = screenDpi / 96
userScale = explicit jdesk.ui.scale
         或 discrete(systemFontSize / defaultSystemFontSize)

layoutScale = JRE-managed ? userScale : systemScale * userScale
pixelScale = systemScale * userScale
```

在 JRE-managed HiDPI 下：

- Swing 布局尺寸只乘 `userScale`。
- 不把 `systemScale` 再乘进普通 `Dimension`、`Insets`、`EmptyBorder`。
- 图标、图片 backing 可以乘 `pixelScale`。
- 字体以系统 UI 字体为基础；没有显式 `jdesk.ui.scale` 时，不再次把系统 DPI 缩放乘到字号上。

在非 JRE-managed 环境下：

- 普通布局尺寸需要考虑 `systemScale * userScale`。
- 这样旧环境中 150% 或 175% DPI 才不会继续显示得过小。

## JDesk Framework 的落地入口

### JdfScale

`JdfScale` 负责维护缩放状态：

- `installEarly()`
- `userScale()`
- `systemScale()`
- `systemScale(Component)`
- `pixelScale(Component)`
- `scale(int)`
- `scale(float)`
- `scaleFontSize(float)`
- `updateFromSystemFont(Font)`

显式用户缩放通过：

`<home>/system/application.properties`

```properties
jdesk.ui.scale=1.25
```

诊断日志通过：

```properties
jdesk.ui.scale.verbose=true
```

### JdfFonts

`JdfFonts` 负责统一字体入口：

- `label()`
- `label(float)`
- `small()`
- `mini()`
- `mono()`
- `mono(float)`
- `derive(Font, int, float)`
- `installDefaults()`

Windows 下会优先读取 `win.messagebox.font`，并把修正后的 `FontUIResource` 写回 Swing 的常见默认字体 key。

### JdfUi

`JdfUi` 负责统一尺寸入口：

- `dimension(w, h)`
- `size(w, h)`
- `insets(...)`
- `empty(...)`
- `lineBorder(...)`
- `strut(width)`

框架和 demo 中不再直接散落固定 `Dimension`、`Insets`、`EmptyBorder` 常量。

### JdfIconManager

`JdfIconManager` 接收逻辑尺寸，返回的 `Icon.getIconWidth()` 和 `Icon.getIconHeight()` 仍保持逻辑尺寸。

高 DPI 下只提高内部 raster backing image 的像素密度，不改变布局尺寸。

## 排查清单

如果以后再次出现字体或界面尺寸异常，优先检查这些值：

```java
UIManager.getFont("Label.font")
Toolkit.getDefaultToolkit().getDesktopProperty("win.messagebox.font")
GraphicsEnvironment.getLocalGraphicsEnvironment()
    .getDefaultScreenDevice()
    .getDefaultConfiguration()
    .getDefaultTransform()
Toolkit.getDefaultToolkit().getScreenResolution()
```

重点判断：

- `Label.font` 是否异常偏小。
- `win.messagebox.font` 是否是系统实际 UI 字体。
- `defaultTransform` 是否大于 `1.0`。
- `DPI / 96` 是否和系统缩放一致。
- 是否错误地把 `systemScale` 同时用于 JRE-managed 布局尺寸。

## 实践规则

- 不把 `sun.java2d.uiScale` 作为框架默认方案；它只适合临时验证。
- 不在框架 UI 中硬编码 `11f`、`13f`、`18f` 这类裸字号，统一走 `JdfFonts`。
- 不在框架 UI 中直接散落固定边距和尺寸，统一走 `JdfUi`。
- JRE-managed HiDPI 下，布局不再乘系统屏幕缩放。
- 图标和图片 backing 可以乘 `pixelScale`，但对外逻辑尺寸保持稳定。
- 主题切换后需要重新应用字体 defaults，避免 Look and Feel 覆盖掉已修正的系统字体。

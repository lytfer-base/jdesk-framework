# 内存与对象管理原则

> 配套：[Disposer 机制与使用指南](disposer.md)。

## 为什么要有这份原则

基于 JDesk Framework 的应用通常长时间运行：用户可能连开几小时甚至几天。Java 对象一旦“该释放却没释放”，会随使用时间无界累积，逼近 JVM 堆上限——表现为越用越卡、GC 频繁、最终 OOM。这是 Java 桌面应用最常见的体验杀手。

因此本框架对“对象生命周期与内存”有统一方针：**写任何 Java 代码（每加一个字段、监听器、缓存、线程、窗口）都要先想清楚它何时、由谁释放。** 本文是这套方针的统一落点，结合本框架已有机制给出“该怎么做”。

## 总体方针

1. **每个对象有明确属主和释放路径。** 有生命周期或外部关系的对象用 `Disposer` 表达“子先于父释放”，而不是各处手写清理。
2. **不持有比自己活得久的东西。** 短命对象（UI 面板、临时连接、一次性 handler）不能被长命对象或全局列表/缓存钉住。
3. **全局/共享资源用时现取、不缓存。** `Application`/`MessageBus`/全局服务经 `ApplicationManager` 现取，避免缓存指向已释放的死对象。
4. **显式生命周期优于隐式 GC。** 本框架不靠弱/软引用兜底回收，而用 Disposer 显式释放——释放时机和顺序是确定的，不赌 GC。
5. **持久化数据不常驻堆。** 大体量/长期数据保存到 SQLite 或文件；堆里只留运行期必需对象，并保持可释放或有界。
6. **凡“开”必配“关”。** 每个 `addListener`/`subscribe`/`new ExecutorService`/`createWebView`/`open` 都要有配对的、绑定到某个生命周期的释放。

## 落到本框架的机制

### 1. Disposer 所有权树（核心机制）

带生命周期/外部关系的对象实现 `Disposable`，用 `Disposer.register(parent, child)` 表达归属、`Disposer.dispose(parent)` 递归释放（子先于父）；`dispose()` 只清自身资源，不摘树节点。何时该接入、标准写法见 [Disposer 机制与使用指南](disposer.md)。判断口诀：销毁时若要写 `removeListener` / `unsubscribe` / `close` / `cancel` / `shutdown`，就该实现 `Disposable` 并挂到合适父级。

### 2. 全局对象现取、不缓存

`Application` 是进程单例，由 `ApplicationManager` 把守；`MessageBus`、全局服务由它派生。任何业务/工具类都**不缓存** `Application`/`MessageBus` 字段，而是用时现取：

```java
Application app = ApplicationManager.getApplicationIfCreated();
if (app == null || app.isDisposed()) return;
app.getMessageBus().syncPublisher(SomeTopic.class).onSomething(event);
```

例外只有初始化链/属主（`Application` 实现自身、`ApplicationCore`、由属主创建并随其释放的 `ApplicationContext`/`MainFrame` 等）。先例：`JdfLafManager.publishThemeChanged`、`MessageBusManagerImpl`、`ServiceDescriptor`。

### 3. 监听器与连接：凡注册必反注册

- **消息总线连接是受管的**：`app.getMessageBus().connect(parentDisposable).subscribe(Topic.class, handler)`，父 `Disposable` 释放即自动退订——优先用这条。
- **裸监听器列表要手动清。** 例如 `VirtualFileManager.addVfsListener(...)` 是直接列表，**必须**在对象销毁时 `removeVfsListener(...)`，否则该监听器（及它持有的 UI 等）永远留在列表里、无法回收——这就是经典的 **lapsed listener 泄漏**。Swing 监听器同理（在 `removeNotify()` 或自身 dispose 时清）。能包成 `Disposable` 绑到父级最好。

### 4. 线程池与后台任务：不造孤儿

不要 `new` 一个 `ExecutorService`/定时器就不管它。要么接入 `Disposer`（dispose 时 `shutdown()`），要么走框架已有设施：应用级 `ApplicationTaskScope`（shutdown 时 `cancelAll()`）、消息总线自带的有界异步执行器（随 bus 释放）。线程用 daemon，并保证有明确的 shutdown 路径。

### 5. 缓存与快照：不做无界堆缓存

本框架刻意回避无界堆内缓存。将重数据/可持久数据写入 **SQLite 或文件**，可以避免把完整数据集长期常驻 Java 堆；但这不表示 SQLite“完全不占堆”：JDBC 驱动、native buffer、查询结果和业务对象仍会使用堆内或堆外内存。VFS 只在 SQLite 中维护“文件身份 + 目录快照”，**故意不缓存文件内容**（见 [Managed VFS](../storage/managed-vfs.md) 的“当前边界”）。确需堆内缓存时：必须**有界**（容量/LRU 上限）且**有显式失效**（随某个 `Disposable`/窗口关闭清空），不要用 `WeakHashMap` 当“反正 GC 会收”的借口。

### 6. 弱/软引用不是兜底手段

框架除 `Disposer` 内部用 `WeakReference` 记“已释放”状态外，不用弱/软引用做缓存或监听器管理。**不要**靠 `WeakReference`/`WeakHashMap` 来“自动”释放本该显式管理的东西——它让释放时机不可预测，且容易掩盖真正的所有权问题。先想清楚属主，再谈引用强度。

### 7. 插件 ClassLoader：卸载要彻底

每个插件一个 `PluginClassLoader`。卸载时框架会 `close()` 它并清空已加载类记录；框架 API 类走父加载器白名单（插件间共享同一份类型）。业务上要注意：**别让任何长命对象残留一个对插件类/实例的引用**，否则会钉死整个 classloader 及其所有类（经典的“卸载了却没真正释放”泄漏）。插件里带资源的 service 实现 `Disposable`，卸载时随容器 `Disposer.dispose` 释放。

### 8. 静态字段与单例：能不静态就不静态

`static` 字段持有的东西活到进程结束，等于永不释放。框架把静态收得很紧（`ApplicationManager.application` 关闭时清空、`VirtualFileManager` 进程单例只存协议/监听映射、`PathManager` 路径）。**不要把业务状态、UI、缓存放进 `static`**；确需全局的，挂到 `Application`/服务上随其生命周期走，并留清理路径。

### 9. 原生与重资源（WebView 等）：必须显式释放

WebView（JCEF/JxBrowser）、文件句柄、native handle 等占用堆外/原生内存，**绝不能只靠 GC**。这类资源必须显式 `dispose()`/`close()`，且绑定到所属窗口/面板的 Disposer 生命周期（Swing 面板可在 `removeNotify()` 或自身 `dispose()` 里释放）。重资源遗漏释放的代价远大于普通 Java 对象。

## 写 Java 代码时的自检清单

加一个字段 / 监听器 / 缓存 / 线程 / 窗口前，问自己：

- [ ] 这个对象/字段的**属主**是谁？它何时、经哪条路径被释放？
- [ ] 我是不是缓存了一个**全局/共享**对象（app/bus/service）？能不能改成用时现取？
- [ ] 我 `addListener`/`subscribe` 了吗？配对的反注册在哪、绑到哪个生命周期？
- [ ] 我 `new` 了线程池/定时器吗？谁 `shutdown` 它？
- [ ] 我在堆里缓存数据吗？有界吗？何时失效？能不能落 SQLite？
- [ ] 我持有原生/重资源（WebView 等）吗？显式释放路径在哪？
- [ ] 我往 `static` 里放东西了吗？它真的需要活到进程结束吗？

拿不准时，按“有生命周期就接 Disposer、全局就现取、重数据就落盘”三条默认走。

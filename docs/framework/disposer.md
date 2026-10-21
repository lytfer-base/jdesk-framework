# Disposer 机制与使用指南

本文说明 JDesk Framework 中 `Disposer` 的机制原理和推荐用法。`Disposer` 位于 `jdesk-framework-core` 模块，核心类包括：

- `com.jdesk.core.disposer.Disposable`
- `com.jdesk.core.disposer.Disposer`

`Disposer` 用来管理对象之间的生命周期依赖。它表达的是“父对象销毁时，子对象必须先销毁”的关系，适合管理消息订阅、UI 面板、服务、插件组件、临时资源组等需要明确释放的对象。

## 设计目标

`Disposer` 的设计目标是把资源释放从分散的手写清理逻辑收拢成统一的父子树：

- 子对象总是先于父对象销毁。
- 同一个子对象只能挂在一个父对象下面。
- 调用 `Disposer.dispose(parent)` 会递归销毁整棵子树。
- 重复调用 `Disposer.dispose(obj)` 是安全的，不会重复调用 `obj.dispose()`。
- 对象从活动树移除后，`Disposer.isDisposed(obj)` 仍能判断它已经被销毁。

这套模型参考 IntelliJ Platform 的 Disposer 思路，但当前实现只保留 JDesk Framework 已经需要的核心能力。

## 核心概念

| 概念 | 说明 |
|---|---|
| `Disposable` | 需要释放资源的对象，只有一个 `dispose()` 方法。 |
| 父子关系 | 通过 `Disposer.register(parent, child)` 建立，表示 child 跟随 parent 生命周期。 |
| 活动树 | 当前仍处于活动状态、可以继续注册子节点的 Disposer 树。 |
| disposed 记录 | 对已经销毁的对象保存弱身份引用，用于幂等和状态判断。 |
| 身份语义 | Disposer 按对象身份管理节点，不依赖对象的 `equals()` 或 `hashCode()`。 |

`Disposable.dispose()` 通常不应该由业务代码直接调用。推荐入口是：

```java
Disposer.dispose(disposable);
```

这样才能保证子树先被释放、活动树正确摘除、disposed 状态正确记录。

## 什么时候应该使用 Disposer

判断一个对象是否应该接入 Disposer，可以看它是否有“独立生命周期”和“结束时必须清理的外部关系”。只要满足其中一类，就应该优先使用 `Disposable` 和 `Disposer`。

应该使用 Disposer 的场景：

- 对象会注册消息总线连接、事件监听器、回调、观察者或其他订阅关系。
- 对象拥有需要显式释放的子对象，并且这些子对象必须先于父对象释放。
- 对象代表一个 UI 生命周期，例如窗口、面板、工具窗口、对话框、编辑器页签或临时视图。
- 对象代表框架生命周期组件，例如 application service、plugin component、extension instance、action manager、message bus connection。
- 对象启动了后台任务、定时器、线程池、异步执行器、文件监听、VFS 监听或其他长期运行资源。
- 对象持有非内存资源，例如文件句柄、网络连接、native handle、缓存目录锁等，并且生命周期不适合用局部 `try-with-resources` 表达。
- 对象是一个生命周期分组根，本身不做清理，但需要统一承载多个子 disposable。

不需要使用 Disposer 的场景：

- 对象是纯数据对象、DTO、配置值对象或无状态工具类。
- 资源只在一个方法内部临时使用，可以用 `try-with-resources` 或 `try/finally` 完整关闭。
- 对象没有注册外部回调，也没有持有需要显式释放的子资源。
- 对象生命周期完全由 JVM GC 管理，并且释放顺序没有业务意义。

一个简单判断标准是：如果对象销毁时需要写 `removeListener`、`unsubscribe`、`close`、`cancel`、`shutdown`、`stop` 或“释放子对象”，通常就应该实现 `Disposable`，并挂到合适的父级 Disposer 树下。

## 活动树和 disposed 记录

当前实现把“活动树”和“已销毁状态”拆开保存：

```text
active nodeMap
  只保存还在活动树里的 Disposable 节点

disposedObjects
  使用弱身份引用记录已经销毁过的 Disposable
```

这样做有两个好处：

1. `Disposer.dispose(obj)` 完成后，节点会从活动树中移除，避免活动树长期持有已经销毁的对象。
2. 节点移除后，`Disposer.isDisposed(obj)` 仍然可以返回 `true`，并且再次 `Disposer.dispose(obj)` 会直接返回，不会二次销毁。

disposed 记录使用弱引用，因此不会因为 `isDisposed()` 的诊断能力而永久阻止对象被 GC。

## 注册子对象

使用 `register(parent, child)` 把子对象挂到父对象下面：

```java
Disposable parent = Disposer.newDisposable("panel");
Disposable child = () -> {
    // release child resources
};

Disposer.register(parent, child);
```

当父对象被释放时，子对象会先被释放：

```java
Disposer.dispose(parent);
```

销毁顺序是：

```text
child.dispose()
parent.dispose()
```

如果子对象已经挂在其他父对象下面，再次注册会把它移动到新的父对象下。

## 创建分组节点

有时并没有一个自然的业务对象适合作父节点，可以用 `newDisposable()` 创建一个纯生命周期分组节点：

```java
Disposable panelDisposable = Disposer.newDisposable("settings panel");

MessageBusConnection connection = app.getMessageBus().connect(panelDisposable);
Disposer.register(panelDisposable, temporaryResource);

Disposer.dispose(panelDisposable);
```

分组节点自己的 `dispose()` 是空操作，只负责承载子节点。

## 安全注册

`tryRegister(parent, child)` 会在父对象已经销毁时返回 `false`，不会抛出异常：

```java
if (!Disposer.tryRegister(parent, child)) {
    Disposer.dispose(child);
}
```

这适合关闭阶段或并发场景中“父对象可能已经开始销毁”的情况。

普通业务代码更推荐使用 `register(parent, child)`。它会在非法状态下及时抛出异常，暴露生命周期错误。

## 销毁流程

`Disposer.dispose(disposable)` 是唯一推荐的销毁入口。当前流程如下：

1. 如果对象已经在 disposed 记录中，直接返回。
2. 如果对象不在活动树中，记录 disposed 状态，然后直接调用对象的 `dispose()`。
3. 如果对象在活动树中，先标记当前节点正在销毁，防止重入。
4. 拷贝当前子节点列表，并递归 `Disposer.dispose(child)`。
5. 调用当前对象的 `dispose()`。
6. 从父节点 children 中摘除当前节点。
7. 从活动树 `nodeMap` 中移除当前节点。
8. 保留弱身份 disposed 记录，供后续幂等判断和 `isDisposed()` 查询使用。

因此，业务对象不需要也不应该在自己的 `dispose()` 方法里调用 `Disposer.removeNode(this)`。

## removeNode 的定位

`removeNode(disposable)` 不是销毁 API，它不会调用 `disposable.dispose()`。

它只用于“对象已经通过外部机制完成清理，只需要从活动树中解除跟踪”的特殊情况。当前实现对它加了严格前置条件：

- 如果节点仍有未销毁子节点，会抛出 `IllegalStateException`。
- 调用后会记录 disposed 状态。
- 调用后会从活动树移除该节点。

普通业务代码不应该调用它。常见错误写法如下：

```java
// 不推荐
disposable.dispose();
Disposer.removeNode(disposable);
```

更好的写法是：

```java
Disposer.dispose(disposable);
```

也不要在实现类的 `dispose()` 中主动调用：

```java
@Override
public void dispose() {
    // 清理自己的资源即可
    // 不要调用 Disposer.removeNode(this)
}
```

节点移除由 `Disposer.dispose()` 统一完成。

## isDisposed 和 isDisposing

`isDisposed(disposable)` 用于判断对象是否已经被销毁：

```java
if (Disposer.isDisposed(owner)) {
    return;
}
```

它会同时检查活动树中的 disposed 标记和弱身份 disposed 记录。

`isDisposing(disposable)` 只表示对象是否正处在活动树销毁过程中。它主要用于内部判断或调试，不建议作为普通业务逻辑的核心条件。

## 与消息总线的关系

消息总线连接本身实现了 `Disposable`。创建连接时可以选择生命周期归属。

应用级连接：

```java
MessageBusConnection connection = app.getMessageBus().connect();
connection.subscribe(MyTopic.class, handler);
```

这种连接会注册为消息总线的子节点。当应用关闭并执行 `Disposer.dispose(messageBus)` 时，连接会自动释放。

短生命周期连接：

```java
Disposable panelDisposable = Disposer.newDisposable("panel");

MessageBusConnection connection = app.getMessageBus().connect(panelDisposable);
connection.subscribe(MyTopic.class, handler);

Disposer.dispose(panelDisposable);
```

这种方式适合 UI 面板、弹窗、临时工具窗口等对象。父对象释放时，连接会自动取消订阅。

## 与服务容器的关系

`ServiceContainerImpl` 会为所有实现 `Disposable` 的服务建立一个统一的服务根节点。服务实例创建后会注册到这个根节点下。

关闭服务容器时：

1. 按注册顺序的反向顺序处理已创建服务。
2. 对实现 `Disposable` 的服务调用 `Disposer.dispose(service)`。
3. 对仅实现 `AutoCloseable` 的服务调用 `close()`。
4. 最后释放服务根节点。

如果服务内部注册了子 disposable：

```java
public final class MyService implements Disposable {
    public MyService() {
        Disposer.register(this, childResource);
    }

    @Override
    public void dispose() {
        // release service resources
    }
}
```

服务销毁时，`childResource` 会先于 `MyService.dispose()` 被释放。

## 使用原则

推荐遵守以下规则：

- 用 `Disposer.dispose(obj)` 作为销毁入口。
- 用 `Disposer.register(parent, child)` 表达生命周期归属。
- UI 组件、消息连接、临时资源应绑定到明确的父级 `Disposable`。
- 服务实现 `Disposable` 后，可以把内部资源注册到自己下面。
- 对生命周期分组使用 `Disposer.newDisposable("debug name")`。
- `dispose()` 方法只清理对象自己的资源，不负责摘 Disposer 节点。
- 不要依赖 `equals()` 或 `hashCode()` 影响 Disposer 行为，Disposer 使用对象身份管理节点。

需要避免的写法：

- 直接调用 `child.dispose()` 代替 `Disposer.dispose(child)`。
- 在业务代码里调用 `Disposer.removeNode(obj)`。
- 在父对象已经销毁后继续注册子对象。
- 把长生命周期对象注册到短生命周期父对象下。
- 使用 lambda 作为需要长期跟踪和调试的复杂 `Disposable`。简单测试或一次性资源可以使用 lambda，但生产代码中具名对象更容易排查。

## 简单示例

下面是一个带消息订阅和子资源的典型生命周期写法：

```java
public final class SearchPanel implements Disposable {
    private final Disposable disposable = Disposer.newDisposable("search panel internals");

    public SearchPanel(Application app) {
        Disposer.register(this, disposable);

        MessageBusConnection connection = app.getMessageBus().connect(disposable);
        connection.subscribe(SearchEvents.class, new SearchEvents() {
            @Override
            public void searchFinished(String query) {
                // update UI
            }
        });
    }

    @Override
    public void dispose() {
        // release panel resources
    }
}
```

释放时只需要：

```java
Disposer.dispose(searchPanel);
```

释放顺序是：

```text
MessageBusConnection.dispose()
Disposable{"search panel internals"}.dispose()
SearchPanel.dispose()
```

调用结束后，相关节点会从活动树移除，但 `Disposer.isDisposed(searchPanel)` 仍然可以返回 `true`。

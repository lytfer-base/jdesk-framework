# 消息总线使用指南

本文说明 `jdesk-framework-core` 中消息总线的当前用法。消息总线由 `MessageBus`、`MessageBusConnection`、`MessageBusKey`、
`MessageBusManager` 和 `MessageBusImpl` 组成，是框架内的多级别发布/订阅事件系统。

消息总线用于框架内部组件、应用代码和插件之间传递事件。它适合表达“某件事发生了，请所有关心它的监听器处理”的场景，例如应用启动、关闭确认、保存状态、UI
组件事件通知等。

## 核心概念

消息总线围绕下面几个概念工作：

| 概念              | 说明                                            |
|-----------------|-----------------------------------------------|
| 主题接口            | 一个普通 Java 接口，接口方法就是可发布的事件。                    |
| 订阅者             | 实现主题接口的对象，通过连接注册到消息总线。                        |
| 发布器             | 消息总线为主题接口创建的动态代理，调用它的方法会分发给所有订阅者。             |
| MessageBusKey   | 稳定的 bus 查找 key，用于表达应用级、项目级、服务级或自定义级别。         |
| lifecycle owner | 管理 bus 生命周期的 `Disposable` 对象。key 只负责查找，不负责释放。 |

主题接口不需要继承框架类型。当前实现使用 JDK 动态代理，因此主题类型应当是接口。

```java
public interface ProjectEvents {
    void projectOpened(String projectName);

    boolean beforeProjectClose(String projectName);
}
```

## 获取消息总线

当前框架统一通过 `MessageBusManager` 创建和查找消息总线。`MessageBusManager` 注册为应用级 service，替代早期单一
`MessageBusFactoryImpl` 的创建入口。manager 会保存 `MessageBusKey` 到 `MessageBus` 的引用，避免同一个 key 重复创建
bus；但它不拥有 bus 生命周期。

应用代码通常通过 `Application.getMessageBus()` 获取应用级消息总线。如果当前代码没有直接持有 `Application` 引用，也可以通过
`ApplicationManager.getApplication()` 获取运行中的应用实例：

```java
Application app = ctx.getApplication();
MessageBus bus = app.getMessageBus();

Application globalApp = ApplicationManager.getApplication();
MessageBus sameBus = globalApp.getMessageBus();
```

`JdeskApplication` 会懒创建应用级消息总线。第一次调用 `getMessageBus()` 时，应用会通过 `MessageBusManager` 使用固定的
`MessageBusKey.application()` 获取 `MessageBusImpl` 并缓存在 `JdeskApplication` 内部。应用关闭时，
`JdeskApplication.shutdown()` 会释放该 bus；bus dispose 后会通过内部回调自动从 `MessageBusManager` 缓存中移除。

框架内部也可以通过 `MessageBusManager` 为项目、服务或其他生命周期对象创建独立消息总线：

```java
MessageBusManager manager = app.getService(MessageBusManager.class);
MessageBusKey key = MessageBusKey.project(projectId);
MessageBus projectBus = manager.getOrCreateBus(key, project);
```

发布方如果只知道 key，并不负责创建 bus，可以直接查找已经存在的 bus：

```java
MessageBus projectBus = manager.getBusIfCreated(MessageBusKey.project(projectId));
if (projectBus != null) {
    projectBus.syncPublisher(ProjectEvents.class).projectOpened(projectId);
}
```

非应用级 bus 首次创建时必须传入非空 lifecycle owner。没有 owner 的调用方不应该临时创建 bus，而应该通过
`getBusIfCreated(key)` 查找已存在实例，或者由拥有生命周期的模块在初始化阶段先创建。

`MessageBusManager` 不提供手动 `unregisterBus` API。正常释放路径统一走 `Disposer.dispose(lifecycleOwner)` 或
`Disposer.dispose(bus)`；bus 释放时会自动从 manager 缓存中移除引用。

应用进入 `SHUTTING_DOWN` 或 `DISPOSED` 后，`MessageBusManager` 不能再新建任何消息总线。已经创建且尚未释放的 bus
仍可以被查找和使用；关闭流程会直接使用 `JdeskApplication` 已持有的应用级 bus 发送关闭事件，然后释放它。

## MessageBusKey 与生命周期

`MessageBusKey` 是纯 value object，只包含 `scope` 和 `id`。它不保存 owner 引用，也不会通过对象 identity 参与比较，因此不会因为
key 本身额外持有项目、服务或 UI 对象。

常用 key：

```java
MessageBusKey applicationKey = MessageBusKey.application();
MessageBusKey projectKey = MessageBusKey.project(projectId);
MessageBusKey serviceKey = MessageBusKey.service(MyService.class);
MessageBusKey customKey = MessageBusKey.of("tool-window", toolWindowId);
```

key 的职责是定位同一个 bus；lifecycle owner 的职责是释放这个 bus。两者刻意分开：

- 同一个 `MessageBusKey` 只应对应一个未释放的 bus。
- 非应用级 bus 创建时必须传入 lifecycle owner。
- 同一个 key 已经创建 bus 后，再用不同 lifecycle owner 创建会抛出 `IllegalStateException`。
- lifecycle owner 被释放时，它下面注册的 bus 会被释放，并自动从 manager 中移除。
- `MessageBusKey.application()` 是唯一允许 lifecycle owner 为 `null` 的内置 key；应用级 bus 由 `JdeskApplication` 持有并在
  shutdown 时释放。

`getOrCreateBus(key, lifecycleOwner, parentBus)` 目前只记录预期的父子 bus 关系，为未来消息层级传播保留扩展点；当前实现不会在父子
bus 之间自动传播消息。

## 订阅事件

订阅通过 `MessageBusConnection` 完成：

```java
MessageBusConnection connection = app.getMessageBus().connect();
connection.subscribe(ProjectEvents.class, new ProjectEvents() {
    @Override
    public void projectOpened(String projectName) {
        System.out.println("opened: " + projectName);
    }

    @Override
    public boolean beforeProjectClose(String projectName) {
        return true;
    }
});
```

`subscribe(topic, handler)` 要求 `handler` 实现 `topic` 接口。否则会抛出 `IllegalArgumentException`。

同一个连接可以订阅多个主题。连接释放时，会自动移除通过该连接创建的所有订阅。

## 连接生命周期

消息总线支持两种连接创建方式。

### 跟随消息总线释放

```java
MessageBusConnection connection = app.getMessageBus().connect();
connection.subscribe(ProjectEvents.class, handler);
```

通过 `connect()` 创建的连接会注册为消息总线的子节点。当应用关闭并释放消息总线时，这些连接会一起释放。

这种方式适合应用级、长期存在的监听器。对于 UI 组件或其他短生命周期对象，更推荐绑定到明确的父级 `Disposable`。

### 跟随父级 Disposable 释放

```java
Disposable panelDisposable = Disposer.newDisposable();

MessageBusConnection connection = app.getMessageBus().connect(panelDisposable);
connection.subscribe(ProjectEvents.class, handler);

Disposer.dispose(panelDisposable);
```

通过 `connect(parentDisposable)` 创建的连接会注册到指定父对象下。当父对象释放时，连接也会释放，并移除对应订阅。

这适合 UI 面板、窗口、临时工具对象等生命周期明确的对象，可以避免对象销毁后订阅仍留在消息总线上。

如果 `parentDisposable` 为 `null`，当前实现会退化为 `connect()`。

## 取消订阅

可以用 `unsubscribe(topic, handler)` 移除某个处理器：

```java
connection.unsubscribe(ProjectEvents.class, handler);
```

取消订阅按对象引用匹配处理器，也就是注册时传入哪个对象，取消时也要传入同一个对象。

如果要清理一个连接下的所有订阅，直接释放连接更简单：

```java
connection.dispose();
```

释放后的连接不能再订阅新事件，继续调用 `subscribe()` 会抛出 `IllegalStateException`。

## 同步发布

同步发布器通过 `syncPublisher(topic)` 获取：

```java
app.getMessageBus()
        .syncPublisher(ProjectEvents.class)
        .projectOpened("demo");
```

同步发布的行为：

- 在当前调用线程执行订阅者回调。
- 当前实现按订阅注册顺序依次调用订阅者。
- 某个订阅者抛出异常时，异常会被记录到日志，后续订阅者仍会继续执行。
- 调用方会在所有订阅者执行完成后返回。

如果在 Swing UI 代码中同步发布事件，订阅者也会运行在当前线程。当前线程如果是 EDT，订阅者也会运行在 EDT。订阅者不应在 EDT
上执行耗时工作。

## 异步发布

异步发布器通过 `asyncPublisher(topic)` 获取：

```java
app.getMessageBus()
        .asyncPublisher(ProjectEvents.class)
        .projectOpened("demo");
```

异步发布的行为：

- 调用方不会等待订阅者执行完成。
- 订阅者回调会提交到消息总线持有的后台执行器执行。
- 默认执行器使用有界线程池，线程名前缀为 `MessageBus-Async-`，并随消息总线释放而关闭。
- 异步订阅者抛出异常时，异常会被记录到日志。
- 异步发布的方法返回值不会来自订阅者，而是由消息总线直接返回默认值。

异步回调不在 Swing EDT 上执行。如果异步订阅者需要更新 UI，应使用 frame 层的 `SwingUiDispatcher` 或 `EdtScheduler` 切回 EDT。

```java
app.getMessageBus().connect(parent).subscribe(ProjectEvents.class, new ProjectEvents() {
    @Override
    public void projectOpened(String projectName) {
        app.invokeLater(() -> label.setText(projectName));
    }

    @Override
    public boolean beforeProjectClose(String projectName) {
        return true;
    }
});
```

## 返回值语义

主题方法可以有返回值，但要理解当前实现的聚合规则。

### void 方法

`void` 方法只用于通知：

```java
void projectOpened(String projectName);
```

发布器会调用所有订阅者，不需要处理返回值。

### boolean / Boolean 方法

`boolean` 和 `Boolean` 有特殊聚合语义：

```java
boolean beforeProjectClose(String projectName);
```

同步发布时：

- 默认结果是 `true`。
- 只要任意订阅者返回 `false`，最终结果就是 `false`。
- 订阅者抛出异常时，最终结果也会变成 `false`。
- 没有订阅者时返回 `true`。

这适合“是否允许继续”的场景，例如关闭前确认：

```java
boolean canClose = app.getMessageBus()
        .syncPublisher(ProjectEvents.class)
        .beforeProjectClose("demo");

if (!canClose) {
    return;
}
```

应用关闭流程中的 `ApplicationLifecycleListener.canExitApplication(app)` 就使用了这种语义。任意监听器返回 `false`
，本次关闭会被取消。

### 其他返回类型

非 `void`、非布尔返回类型当前采用“最后一个订阅者返回值覆盖前一个返回值”的规则：

```java
String provideStatusText();
```

同步发布时，消息总线会按订阅顺序调用所有订阅者，最终返回最后一个订阅者的返回值。

没有订阅者时，默认返回值如下：

| 返回类型                     | 默认值            |
|--------------------------|----------------|
| 引用类型                     | `null`         |
| `boolean` / `Boolean`    | `Boolean.TRUE` |
| `char`                   | `'\0'`         |
| `byte` / `short` / `int` | `0`            |
| `long`                   | `0L`           |
| `float`                  | `0f`           |
| `double`                 | `0d`           |
| `void`                   | `null`         |

异步发布时，不会等待订阅者返回结果，因此无论有多少订阅者，发布器都会立即返回对应默认值。

## Object 方法

发布器是动态代理对象。当前实现会单独处理 `toString()`、`hashCode()` 和 `equals()`：

- `toString()` 返回类似 `MessageBusPublisher@12345678` 的调试字符串。
- `hashCode()` 返回代理对象的标识哈希值。
- `equals()` 使用代理对象引用相等判断。

这些方法不会被当作主题事件分发给订阅者。

## 应用生命周期事件

框架内置的应用生命周期事件使用 `ApplicationLifecycleListener`。早期启动事件可以通过 `Application.addLifecycleListener()`
直接监听；应用级 `MessageBus` 创建之后，后续事件也会发布给 bus 订阅者。

订阅示例：

```java
app.getMessageBus().connect().subscribe(ApplicationLifecycleListener.class,
        new ApplicationLifecycleListener() {
            @Override
            public void onApplicationStarted(Application app) {
                System.out.println("started");
            }

            @Override
            public boolean canExitApplication(Application app) {
                return true;
            }
        });
```

当前生命周期事件发布顺序如下：

1. `beforeApplicationLoaded(app)`：`init()` 开始、框架配置加载前发布，仅直接监听器可靠可见。
2. `applicationInitialized(app)`：框架基础设施初始化完成后发布。
3. `beforeUiShown(app)`：插件启动后、顶层 UI 显示前发布。
4. `onApplicationStarted(app)`：应用进入 `RUNNING` 后发布。
5. `appClosing(app)` / `onApplicationClosing(app)`：请求关闭后，正式关闭前发布。
6. `canExitApplication(app)`：关闭前确认，任意监听器返回 `false` 会取消关闭。
7. `onApplicationSaving(app)`：关闭被允许后，用于保存状态。
8. `appWillBeClosed(app)` / `onApplicationWillClose(app)`：进入不可逆关闭阶段前发布。
9. `onApplicationShuttingDown(app)`：应用进入 `SHUTTING_DOWN` 后发布。

MessageBus 订阅者只会在应用级消息总线已经创建且尚未释放时收到生命周期事件。关闭流程不会为了发布事件创建新的消息总线；直接注册到
`Application` 的监听器不受这个限制。

应用进入 `SHUTTING_DOWN` 后，已有应用级 `MessageBus` 会进入只发布、不接收新连接的状态：`connect()` 会抛出
`IllegalStateException`，但已有订阅和 `syncPublisher()` / `asyncPublisher()` 会继续可用，直到服务容器和扩展注册表释放完成后才最终
dispose 这个 bus。

## 完整示例

下面示例演示如何创建、使用、销毁一个项目级 `MessageBus`。真实项目中，`projectOwner` 通常应该是项目对象本身；这里用
`Disposer.newDisposable("project:demo")` 模拟一个项目生命周期根节点。

```java
import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusConnection;
import com.jdesk.core.message.MessageBusKey;
import com.jdesk.core.message.MessageBusManager;

public final class ProjectMessageBusExample {
    public interface ProjectEvents {
        void projectOpened(String projectId);

        boolean beforeProjectClose(String projectId);
    }

    public void run(Application app) {
        MessageBusManager manager = app.getService(MessageBusManager.class);

        String projectId = "demo";
        Disposable projectOwner = Disposer.newDisposable("project:" + projectId);
        MessageBusKey projectKey = MessageBusKey.project(projectId);

        MessageBus projectBus = manager.getOrCreateBus(projectKey, projectOwner);

        try {
            MessageBusConnection connection = projectBus.connect();

            connection.subscribe(ProjectEvents.class, new ProjectEvents() {
                @Override
                public void projectOpened(String projectId) {
                    System.out.println("Project opened: " + projectId);
                }

                @Override
                public boolean beforeProjectClose(String projectId) {
                    System.out.println("Before project close: " + projectId);
                    return true;
                }
            });

            projectBus.syncPublisher(ProjectEvents.class).projectOpened(projectId);

            MessageBus busFromKey = manager.getBusIfCreated(projectKey);
            if (busFromKey != null) {
                boolean canClose = busFromKey
                        .syncPublisher(ProjectEvents.class)
                        .beforeProjectClose(projectId);
                if (!canClose) {
                    System.out.println("Project close cancelled: " + projectId);
                }
            }
        } finally {
            Disposer.dispose(projectOwner);
        }

        if (manager.getBusIfCreated(projectKey) != null) {
            throw new IllegalStateException("Project message bus should be removed after dispose");
        }
    }
}
```

这个例子中的生命周期顺序是：

1. `manager.getOrCreateBus(projectKey, projectOwner)` 创建项目级 bus，并把 bus 注册到 `projectOwner` 的 `Disposable` 树下。
2. `projectBus.connect()` 创建连接，连接跟随 `projectBus` 释放。
3. 发布方可以通过 `projectKey` 和 `manager.getBusIfCreated(projectKey)` 找回已经创建的 bus。
4. `Disposer.dispose(projectOwner)` 释放项目生命周期根节点，进而释放 bus 和连接。
5. bus 释放时触发内部 dispose callback，自动从 `MessageBusManager` 缓存中移除 `projectKey` 对应引用。

## 使用建议

- 用接口定义主题，不要把具体实现类作为主题类型。
- 应用级 bus 优先通过 `Application.getMessageBus()` 获取。
- 项目级、服务级或自定义级别 bus 使用稳定的 `MessageBusKey` 创建和查找。
- 不要把 lifecycle owner 对象编码进 key；key 只表达查找身份，生命周期由 `Disposable` 管理。
- 非应用级 bus 应由拥有生命周期的模块创建；只负责发布消息的调用方优先使用 `getBusIfCreated(key)`。
- UI 组件订阅消息时，优先使用 `connect(parentDisposable)`。
- 生命周期明确的订阅对象，应在生命周期结束时释放连接或父级 `Disposable`。
- 不需要手动从 `MessageBusManager` 注销 bus；释放 lifecycle owner 或 bus 后，manager 缓存会自动清理。
- 同步发布适合需要立即完成、需要读取返回值、需要关闭确认的事件。
- 异步发布适合不需要返回值、可以后台执行的通知类事件。
- 异步订阅者不要直接更新 Swing UI；需要更新 UI 时切回 EDT。
- 布尔返回值适合表达“允许/拒绝”，不要用它承载复杂结果。
- 非布尔返回值如果存在多个订阅者，最终只保留最后一个订阅者的结果；如果需要更明确的聚合规则，应在主题设计中显式建模。
- 订阅者内部异常不会向发布者传播，只会记录日志；需要业务层处理失败时，应在事件协议中显式表达失败结果。

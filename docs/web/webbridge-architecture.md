# WebBridge 架构与接口设计

WebBridge 是 Java 与 WebView 页面之间的异步 JSON 通信机制，位于 `jdesk-framework-frame`。本文只约束框架级 bridge 协议与生命周期；应用的 handler 名称、业务协议和窗口行为由应用自己维护。

## 0. 实现状态

当前已在 `jdesk-framework-frame` 中实现：

- 公共 API：`WebBridgeService`、`WebBridgeSession`、`WebBridgeHandler`、`WebBridgeRequest`、`WebBridgeResponse`、`WebBridgeError`、`WebBridgeErrorCode`、`WebBridgeEvent`、`WebBridgeOptions`。
- 核心能力：JSON codec、bootstrap 脚本、session handler dispatch、Java 主动 `emit` 事件、错误响应、dispose 语义。
- 后端 transport：本仓库的 JCEF 后端用 `CefMessageRouter` 注册的 `window.jdeskQuery` / `window.jdeskQueryCancel` 作为正式 WebBridge query。本仓库之外可以有别的后端（例如商业的 JxBrowser 后端，框架只按类名加载，本仓库不含），它们实现同一组 transport SPI。
- 资源（页面、图片、附件等字节）不走桥，经 frame 层 `WebResourceServer` 的本地回环 HTTP 供应，各后端通用。资源访问尚无按窗口或来源授权的策略，完整边界见 [资源 HTTP 服务](resource-http-server.md)。
- 导航拦截：`WebViewOptions.navigationPolicy(...)`，默认只许主框架留在 WebView 第一篇内容文档的 origin 上；JCEF 后端的 popup 一律拒绝。见 [WebViewService](webview-service.md) 的「导航策略」。

待完善的是资源授权、权限边界和插件命名空间约束；本节列出能力缺口，不代表实施排期。

## 1. 背景

JDesk Framework 在 frame 层提供 `WebViewService`，可以创建嵌入式浏览器视图（本仓库自带 JCEF 后端）。应用的 Web 页面统一通过框架 WebBridge 调用 Java 服务；公共协议属于 frame 层，应用只注册自身 handlers 和事件。

## 2. 设计目标

WebBridge 提供通用异步通信，业务 CRUD 与工作流程由消费方实现。

- 在 `jdesk-framework-frame` 中提供通用 Java/Kotlin 与 TypeScript 异步通讯机制。
- 应用层只依赖 frame 公共 API，不依赖 JCEF 或具体 transport 类型。
- Web 侧统一使用 `window.jdeskBridge.invoke(method, params)`。
- Web 侧通过 `window.jdeskBridge.on(event, handler)` / `off(event, handler)` 接收 Java 主动通知。
- Java 侧统一使用 `WebBridgeSession.register(method, handler)`。
- Java 侧通过 `WebBridgeSession.emit(event, payload)` 主动通知前端，不等待前端返回值。
- 支持多窗口、多 WebView、多 session。
- session 跟随 WebView 或宿主 panel 生命周期释放。
- handler 返回 `CompletionStage`，不能阻塞 EDT。
- 只传输 JSON 小数据。
- 图片、视频、PDF、附件等资源通过资源 URL 加载，见 [资源 HTTP 服务](resource-http-server.md)。
- 页面首屏渲染和 Java 窗口显示不能被 WebBridge 阻塞。

## 3. 非目标

公共 Bridge 的职责边界：

- 不做同步 bridge。
- 不提供 DOM Java API。
- 不做任意 Java 对象注入。
- 不把应用服务、业务对象、插件管理器或框架对象直接暴露给 Web。
- 不通过 JSON bridge 传输大二进制。
- 不用本地 HTTP 服务替代 WebBridge：JSON 消息仍走 bridge，本地 HTTP 只供应资源字节。
- 当前没有完整权限系统或插件 namespace 管控。
- 资源由独立的资源注册表、处理器与回环 HTTP 服务实现，不属于 JSON Bridge 协议。
- 公共 API 与 JCEF 后端都在 `jdesk-framework-frame` 里，没有拆出独立的 `web-api` / `web-jcef` Maven 模块。

## 4. 分层边界

公共 API 与 transport SPI 都位于 `jdesk-framework-frame`，没有独立 Maven 模块。

```text
应用模块
  SomeWebHostPanel
    WebViewService
    WebBridgeService
      WebBridgeSession
        myapp.* handlers

jdesk-framework-frame
  com.jdesk.frame.webview
    JdfWebView
    WebViewService
  com.jdesk.frame.webview.bridge
    WebBridgeService
    WebBridgeSession
    WebBridgeHandler
    WebBridgeRequest
    WebBridgeResponse
    WebBridgeError
    WebBridgeErrorCode
    WebBridgeEvent
    WebBridgeOptions
  com.jdesk.frame.webview.bridge.spi
    WebBridgeTransport
    WebBridgeTransportProvider
    WebBridgeTransportContext
    WebBridgeInbound
```

后端 transport 放在后端的实现包里：

```text
com.jdesk.frame.webview.jcef
  JcefWebBridgeTransport
```

应用层不引用 SPI 与 transport 类型。

## 5. 性能约束

WebBridge 不能拖慢 Java 窗口显示，也不能拖慢 Web 首屏渲染。

硬性约束：

- `WebBridgeService.attach(...)` 不执行 I/O。
- `attach(...)` 不初始化数据库、搜索索引等重服务。
- handler 注册只保存函数引用，不加载业务数据。
- Java 窗口显示不等待 bridge ready。
- Web 页面首屏渲染不等待 bridge ready。
- bootstrap 脚本保持小而固定，目标小于 10KB。
- bootstrap 的安装时机由 transport 决定：JCEF 在 `injectBootstrap()` 时先试注入一次，之后每次主框架 `LOAD_FINISHED` 再注入，所以页面脚本可能早于 bridge 执行，页面要等 `jdesk-bridge-ready`。
- 初始业务数据由页面在 `jdesk-bridge-ready` 后异步请求其已注册的 bootstrap 或查询方法。
- 大资源通过 URL 加载，不走 JSON bridge。

推荐流程：

```text
创建 JFrame
创建空 WebView
attach WebBridgeSession
注册 handler 引用
显示 Java 窗口
加载页面
JCEF：LOAD_FINISHED 后注入 bootstrap
触发 jdesk-bridge-ready
Web 页面异步请求业务数据
```

如果首次打开 WebView 慢，应通过现有预热配置解决：

```properties
jdesk.webview.warmup=view
```

WebBridge 不承担浏览器预热职责。

## 6. Java 公共 API

公共包：

```text
com.jdesk.frame.webview.bridge
```

`WebBridgeService` 由 frame 注册为懒加载应用服务（`app.getService(WebBridgeService.class)`）。

### 6.1 WebBridgeService

```java
public interface WebBridgeService {
    WebBridgeSession attach(JdfWebView webView, WebBridgeOptions options);
}
```

职责：

- 为一个 `JdfWebView` 建立 bridge session。
- 根据当前 WebView 后端创建内部 transport；WebView 实现没有实现 `WebBridgeTransportProvider` 时抛 `IllegalArgumentException`。
- 注入 Web 侧 bootstrap。
- 不执行业务逻辑。

### 6.2 WebBridgeSession

```java
public interface WebBridgeSession extends Disposable {
    void register(String method, WebBridgeHandler handler);

    void unregister(String method);

    boolean hasHandler(String method);

    void emit(String event, JsonNode payload);

    CompletionStage<WebBridgeResponse> dispatch(WebBridgeRequest request);
}
```

职责：

- 管理 method -> handler 映射。
- 提供 Java -> Web 的单向事件通知入口。
- 处理 method not found、handler failure（含 handler 以 null 完成）、dispose 等公共错误。
- session dispose 时清理 handler、释放 transport；在途请求不取消。
- 单次调用在 Java 侧不限时，超时由前端 invoke 层按操作给预算。

### 6.3 WebBridgeHandler

```java
@FunctionalInterface
public interface WebBridgeHandler {
    CompletionStage<WebBridgeResponse> handle(WebBridgeRequest request);
}
```

handler 默认异步。handler 内部如需更新 Swing UI，必须显式切回 EDT。

### 6.4 WebBridgeRequest

```java
public final class WebBridgeRequest {
    private final String id;
    private final String method;
    private final JsonNode params;
    private final String origin;
    private final String url;
}
```

`origin` 和 `url` 由 transport 尽量填充；如果某个后端暂时无法可靠提供，可以为空字符串。它们预留给后续 origin 校验和调试。

### 6.5 WebBridgeResponse

```java
public final class WebBridgeResponse {
    private final String id;
    private final boolean ok;
    private final JsonNode result;
    private final WebBridgeError error;

    public static WebBridgeResponse success(String id, JsonNode result);

    public static WebBridgeResponse failure(String id, WebBridgeError error);
}
```

### 6.6 WebBridgeError

```java
public final class WebBridgeError {
    private final WebBridgeErrorCode code;
    private final String message;
    private final JsonNode details;

    public static WebBridgeError of(WebBridgeErrorCode code, String message);

    public static WebBridgeError of(WebBridgeErrorCode code, Throwable failure);
}
```

错误码是 `WebBridgeErrorCode` 枚举，枚举名会序列化给前端，新增或改名要考虑前端兼容：

```text
INVALID_REQUEST
METHOD_NOT_FOUND
HANDLER_FAILED
BRIDGE_DISPOSED
```

解码失败（JSON 非法，或单条字符串超过两千万字符的读取上限）回 `INVALID_REQUEST`，并**尽力从原文取回请求 id**：回空 id 的话前端那条 Promise 永远不 settle，表现为发送大文本时卡死。日志只记长度、异常类型和 id，不记原文。

handler 抛出 [`LocalizedException`](../../jdesk-framework-core/src/main/java/com/jdesk/core/i18n/LocalizedException.java) 时，`HANDLER_FAILED` 的 `details` 带 `reason`（文案键）与 `params`（占位参数），页面据此按当前语言显示，见 [多语言](../framework/i18n.md)。handler 自己回失败响应时用 `WebBridgeError.of(code, failure)`，保留同样的 `details`。

后续预留：

```text
UNAUTHORIZED
FORBIDDEN_ORIGIN
PAGE_UNLOADED
PAYLOAD_TOO_LARGE
```

### 6.7 WebBridgeEvent

```java
public final class WebBridgeEvent {
    private final String event;
    private final JsonNode payload;
}
```

用于 Java 主动通知前端。事件不带 request id，也不等待前端返回值；如果后续需要 Java 调用前端并等待结果，应单独设计 `invokeFrontend/handle`，不要把它混入事件通知。

### 6.8 WebBridgeOptions

```java
public final class WebBridgeOptions {
    private final String namespace;
    private final boolean injectOnLoad;
}
```

默认值：

```text
namespace = "jdeskBridge"
injectOnLoad = true
```

## 7. TypeScript 公共协议

Web 页面公开 API：

```ts
window.jdeskBridge.invoke(method, params): Promise<unknown>
window.jdeskBridge.on(event, handler): () => void
window.jdeskBridge.off(event, handler): void
```

请求：

```ts
type JdeskBridgeRequest = {
  id: string
  method: string
  params?: unknown
}
```

成功响应：

```ts
type JdeskBridgeSuccess = {
  id: string
  ok: true
  result?: unknown
}
```

失败响应：

```ts
type JdeskBridgeFailure = {
  id: string
  ok: false
  error: {
    code: string
    message: string
    details?: unknown
  }
}
```

完整响应：

```ts
type JdeskBridgeResponse = JdeskBridgeSuccess | JdeskBridgeFailure
```

Java 主动事件：

```ts
type JdeskBridgeEvent = {
  event: string
  payload?: unknown
}
```

页面可以监听 bridge ready：

```ts
window.addEventListener('jdesk-bridge-ready', () => {
  // Bridge is available.
})
```

推荐 helper：

```ts
export function waitForJdeskBridge(): Promise<JdeskBridge> {
  if (window.jdeskBridge) {
    return Promise.resolve(window.jdeskBridge)
  }

  return new Promise((resolve) => {
    window.addEventListener('jdesk-bridge-ready', () => {
      resolve(window.jdeskBridge)
    }, { once: true })
  })
}
```

## 8. JSON 协议格式

请求：

```json
{
  "id": "request-id",
  "method": "demo.saveDraft",
  "params": {
    "draftId": null,
    "title": "Hello",
    "body": "Hello, world"
  }
}
```

成功：

```json
{
  "id": "request-id",
  "ok": true,
  "result": {
    "draft": {
      "draftId": "draft-1",
      "title": "Hello",
      "savedAt": "2026-06-06T04:00:00Z"
    }
  }
}
```

失败：

```json
{
  "id": "request-id",
  "ok": false,
  "error": {
    "code": "METHOD_NOT_FOUND",
    "message": "No handler registered for method: demo.saveDraft",
    "details": {}
  }
}
```

## 9. Bootstrap 脚本

bootstrap 脚本由 frame 注入（`WebBridgeBootstrapScript`），不属于应用的前端 bundle。

职责：

- 创建 `window.jdeskBridge.invoke(...)`。
- 创建 `window.jdeskBridge.on(...)` 和 `window.jdeskBridge.off(...)`。
- 维护 pending promise 表。
- 维护前端事件 listener 表。
- 将请求序列化为 JSON 并交给 native message endpoint。
- 接收 Java 响应并 resolve/reject promise。
- 接收 Java 主动事件并分发给已订阅 handler。
- 触发 `jdesk-bridge-ready` 事件。

脚本可以重复注入：命名空间已经存在时只重发 `jdesk-bridge-ready`。native endpoint 由 transport 以表达式传给 `WebBridgeBootstrapScript.create(options, nativePostExpression)`：JCEF 传 `window.jdeskQuery`，脚本按 MessageRouter 的回调形态调用；其他表达式按 `endpoint(JSON.stringify(request))` 调用。

伪代码：

```ts
window.jdeskBridge = {
  invoke(method, params) {
    const id = nextId()
    const request = { id, method, params }

    return new Promise((resolve, reject) => {
      pending[id] = { resolve, reject }
      nativePostMessage(JSON.stringify(request))
    })
  },

  on(event, handler) {
    listeners[event] = listeners[event] || []
    listeners[event].push(handler)
    return () => window.jdeskBridge.off(event, handler)
  },

  off(event, handler) {
    const handlers = listeners[event]
    if (!handlers) return
    const index = handlers.indexOf(handler)
    if (index >= 0) handlers.splice(index, 1)
  }
}

window.__jdeskBridgeReceive = function(response) {
  const pendingRequest = pending[response.id]
  if (!pendingRequest) {
    return
  }

  delete pending[response.id]

  if (response.ok) {
    pendingRequest.resolve(response.result)
  } else {
    pendingRequest.reject(response.error)
  }
}

window.__jdeskBridgeEmit = function(message) {
  const handlers = listeners[message.event]
  if (!handlers) {
    return
  }
  handlers.slice().forEach((handler) => handler(message.payload))
}

window.dispatchEvent(new CustomEvent('jdesk-bridge-ready'))
```

业务页面不直接调用 native endpoint。

## 10. Transport 设计

SPI 位于 `com.jdesk.frame.webview.bridge.spi`，给 WebView 后端实现，不进入应用公共 API：

```java
public interface WebBridgeTransport extends Disposable {
    void attach(WebBridgeTransportContext context);

    void send(WebBridgeResponse response);

    void sendEvent(WebBridgeEvent event);

    void injectBootstrap();
}
```

```java
@FunctionalInterface
public interface WebBridgeInbound {
    CompletionStage<WebBridgeResponse> receive(WebBridgeRequest request);
}
```

- 后端的 `JdfWebView` 实现 `WebBridgeTransportProvider`，为每次 `attach` 建一条新 transport。
- `WebBridgeTransportContext` 带上 WebView、会话入口（`WebBridgeInbound`）、选项、codec 与 bootstrap 脚本，transport 从这里取协议工具，不直接依赖 `WebBridgeServiceImpl`。

### 10.1 JCEF transport

正式 WebBridge 使用：

```text
window.jdeskQuery
window.jdeskQueryCancel
```

流程：

```text
window.jdeskBridge.invoke(...)
  -> window.jdeskQuery({ request: json })
  -> CefMessageRouter.onQuery(request)        CEF UI 线程：只把请求排进本视图的分发线程就返回
  -> 本视图的分发线程 jdesk-jcef-bridge-N      解码，WebBridgeSession.dispatch(...)
  -> callback.success(responseJson)            任意线程都可以调，CEF 会转回 UI 线程
  -> JS promise resolve/reject
```

- 桥只认主框架：`CefMessageRouter` 在每个 V8 context 都注册 `jdeskQuery`，子框架（包括沙箱 iframe）同样调得到，所以 `onQuery` 对非主框架的 query 直接回失败、不交给会话。
- handler 跑在每个视图自己的分发线程上：同一页面内的请求按到达顺序逐个交给 handler，视图之间互不阻塞。⚠️ 别把 JCEF 的分发挪回 CEF UI 线程：所有 JCEF 窗口共用那一条，一个慢 handler 会拖住全部窗口。阻塞 I/O 仍要 offload 到后台线程，否则会拖住本页后续的请求。
- `JdfWebView.executeJavaScript` 只发不回值，JCEF 后端没有单独的脚本回传通道；`jdeskQuery` 只给 WebBridge 用。

### 10.2 其他后端

本仓库之外的后端实现同一组 SPI：在 `injectBootstrap()` 里安排注入（能在页面脚本执行前注入更好），用 `WebBridgeBootstrapScript` 生成脚本，并遵守第 11 节——只暴露一个接收 JSON 字符串的最小端点。

资源 URL 不是 WebBridge transport 的一部分：页面与资源都从 frame 层 `WebResourceServer` 的回环 HTTP 加载，后端在引擎初始化时接上映射并种入会话 cookie。实际可用范围和待补安全边界以 [资源 HTTP 服务](resource-http-server.md) 为准。

## 11. 不做任意 Java 对象注入

明确禁止：

```text
window.application
window.someService
window.pluginManager
window.java.*
任意业务 service 方法直接暴露给 Web
任意框架对象直接暴露给 Web
```

明确允许：

```text
transport 内部暴露一个最小 native message endpoint
endpoint 只接受 JSON string
endpoint 不暴露业务方法
所有能力都必须经过 WebBridgeSession 的 method handler 注册表
```

这个约束适用于所有后端。

## 12. 生命周期

`WebBridgeSession` 实现 `Disposable`。

推荐释放顺序：

```text
Disposer.dispose(bridgeSession)
Disposer.dispose(webView)
```

session dispose 时：

- 标记 session 已释放。
- 清空 handler 表。
- 释放 transport；在途请求不取消，前端靠 invoke 层的超时收尾。
- 后续请求返回或抛出 `BRIDGE_DISPOSED`。

WebView dispose 时应释放关联 session。宿主 panel 或窗口释放时，应明确释放 session 和 WebView。资源释放的通用做法见 [Disposer 机制与使用指南](../framework/disposer.md)。

页面刷新后：

- bootstrap 重新注入。
- 新请求进入同一个 session。
- 页面刷新前未完成的 promise 随旧页面丢弃；Java 侧 handler 照常跑完，它的回应不再有人接收。

## 13. 应用接入边界

应用页面通过 `WebViewService` 创建 WebView、附加 `WebBridgeSession`、注册本应用的白名单 handler 后再加载依赖 bridge 的页面。先建空 WebView，再 attach、注册 handler，最后加载页面；不要通过 `WebViewOptions.initialUrl` / `initialHtml` 在 WebView 创建阶段自动加载需要 bridge 的页面，否则页面脚本会早于 bridge 安装执行。

具体 handler 名称、保存/关闭协议、业务数据模型与前端 fallback 不属于 frame 级协议，由应用自己维护。

## 14. 验证关注点

以下列出维护协议时的验证范围；实际测试结果以对应变更的验证结果为准。

frame 层测试：

- `WebBridgeService` 注册为 frame 懒加载服务。
- `WebBridgeSession` 支持 register / unregister / hasHandler。
- `dispatch` 能调用已注册 handler。
- 未注册 method 返回 `METHOD_NOT_FOUND`。
- handler 抛异常返回 `HANDLER_FAILED`。
- handler 以 null 完成返回 `HANDLER_FAILED`，带原请求 id。
- session dispose 后拒绝新请求。
- session dispose 会释放 transport。
- bootstrap 脚本包含 `window.jdeskBridge.invoke`。
- bootstrap 脚本包含 `window.jdeskBridge.on/off` 和 `__jdeskBridgeEmit`。
- `WebBridgeSession.emit` 会通过 transport 发送 Java 主动事件。
- session dispose 后忽略迟到事件。
- bootstrap 脚本触发 `jdesk-bridge-ready`。
- JCEF 桥入口只认主框架（`WebBridgeMainFrameGuardTest`）。

应用层测试：

- 每个 handler 的参数校验、持久化结果与结构化错误由所属模块测试覆盖。
- Web host 必须在加载依赖 bridge 的页面前 attach session 并注册 handler。
- 关闭、保存或导航等业务协议只在所属模块的契约测试中断言，不写入 frame bridge 通用测试。
- 宿主释放时必须释放 bridge session 与 WebView。

## 15. 后续演进

应用功能沿公共 Bridge 扩展；框架层尚待完善的方向是：

1. 为已接入的资源处理器补齐授权边界和可审计的访问策略。
2. 设计权限 handler。
3. 设计插件 namespace 和权限边界。
4. 等各后端 transport 都稳定后，再评估是否拆出独立的 web-api / 后端模块。

这些方向不是已批准的实施顺序。新增能力应先确认现有公共协议是否足够，避免在应用中建立第二套私有通信通道。

# 资源 HTTP 服务

WebView 里的页面、静态资源以及应用要交给页面的文件，都由主 JVM 内的 Jetty 以本地回环 HTTP 供应。JSON 消息仍走 [WebBridge](webbridge-architecture.md)，这条链路只供应资源字节。

## 结构

frame 层 `WebResourceServer`（`com.jdesk.frame.webview.resource.http`）在 `127.0.0.1` 的随机端口起一个 Jetty，把 `/<name>/<host>/<path>` 映射回 `WebResourceSchemeRegistry` 里按名字注册的 `WebResourceHandler`。页面看到的 origin 不是这个端口，而是应用指定的固定公共 origin，例如 `http://myapp.localhost`：WebView 后端在引擎初始化时把这个 host 接到回环端口（见「公共 origin 与引擎映射」）。

```text
Chromium 渲染进程 → Chromium 网络服务（磁盘缓存、V8 代码缓存在此生效）
  → 公共 host 被接到 127.0.0.1:<port>（URL 仍是 http://myapp.localhost/…）
  → TCP 127.0.0.1:<port> → Jetty（WebResourceServlet）
      → 鉴权 → 路由 → WebResourceSchemeRegistry.dispatch → 应用 handler
```

应用接入：

```java
WebResourceSchemeRegistry registry = app.getService(WebResourceSchemeRegistry.class);
registry.register("app", appHandler);   // 应用自己的界面
registry.register("file", fileHandler); // app 以外的名字按不可信内容处理，见「鉴权」

WebResourceServer server = new WebResourceServer(registry, "myapp.localhost",
        WebResourceServer.Config.defaults().withPathHandoff("/file/uploads/dropped-files"));
app.getServiceContainer().registerInstance(WebResourceServer.class, server);
server.start();

String pageUrl = server.publicBaseUrl() + "/app/main/index.html";
```

- 服务必须在创建任何 WebView 之前启动，并注册进应用服务容器：后端在引擎初始化时经 `WebResourceServer.find(application)` 取会话，JCEF 的命令行开关又只在 `CefApp` 首次创建时读一次。服务没在跑时后端照常初始化，但不接映射、不种 cookie，页面请求到不了服务。
- `WebResourceSchemeRegistry` 是 frame 注册的应用服务；名字须合 `[a-z][a-z0-9-]*`，按小写比对。
- handler 跑在 Jetty 工作线程上，允许阻塞 I/O，不得触碰 EDT 或 WebView API。服务器等 handler 交回响应最多 60 秒（`Config.handlerTimeout`），超时回 504 并取消那个 future，工作线程放回池里。handler 抛异常、以失败完成或交回 null 都回 500。

## URL 形态

```text
http://<publicHost>/<name>/<host>/<path>[?query]
```

交给 handler 的 `WebResourceRequest.uri()` 是合成的 `<name>://<host>/<path>?<query>`，handler 不需要知道 HTTP 的存在。服务器在分发前按段解码校验：`.`、`..`、反斜杠、编码斜杠、控制字符、坏的百分号转义与中间空段一律 400。交给 handler 的 URI 保留原编码，handler 用 `URI.getPath()` 解一次，别再自己解第二次。根路径、只有一段、名字不合文法或未注册回 404；OPTIONS 回 405。

文件名里的 `%` 编码后是 `%25`，而 Jetty 默认把它判为编码有歧义，在进入 servlet 之前就回 400，所以服务器放行了这一项（`UriCompliance` 的 `AMBIGUOUS_PATH_ENCODING`）。这只在每个路径只被解码一次时才安全，上面「别再自己解第二次」那条因此不能破。

前端只写以 `/` 开头的同源路径（如 `/file/<host>/images/x.png`），不知道端口；Java 经 bridge 下发给页面的资源地址也应是同源相对路径。

## 鉴权

回环端口任何本机进程与用户浏览器里的网页都能打到，所以每个请求都过四道检查（`WebResourceAuth`），任一不过即 403、空 body：

1. `Host` 必须是公共 host（Chromium 送来时不带端口，也容许 `:80`）或 `127.0.0.1:<port>`（curl 与 dev server 代理直打），防 DNS rebinding；
2. cookie `jdesk-resource-session` 存在且与本次启动生成的 token 常量时间相等；
3. 若带 `Origin`，必须等于公共 origin 或 `http://127.0.0.1:<port>`；
4. 若带 `Sec-Fetch-Site`，必须是 `same-origin` 或 `none`；唯一例外是沙箱 iframe（不透明源）的子资源：无 `Origin`、`Sec-Fetch-Mode: no-cors` 的 GET/HEAD 允许 `cross-site`，写请求与 cors / navigate 模式不放。

token 是启动时生成的 32 字节随机数，框架只把它放在内存里，不进 URL、不进日志，也不交给 handler（转交请求前剥掉 `Cookie` 头）。WebView 后端在引擎初始化后、首次导航前把它种成会话 cookie。有的后端种不出 SameSite 属性（JCEF 的 `CefCookie` 没有这个字段，种出来按 Lax 处理），所以服务器在文档类请求（`Sec-Fetch-Dest` 为 `document`、`iframe` 或缺省）的响应里重发同名 cookie，补成 `HttpOnly; Secure; SameSite=None`。

cookie 用 `SameSite=None` 而不是 Strict，正因为不透明源的子资源请求被判跨站、Strict/Lax 都不会带；`None` 必须配 `Secure`，`*.localhost` 与回环地址都是 Chromium 眼里的可信源，照发。cookie host-only 于公共 host，只在本引擎的 profile 里，其他浏览器与本机进程拿不到。

不发任何 CORS 头；响应附 `X-Content-Type-Options: nosniff`、`Referrer-Policy: same-origin`、`Cross-Origin-Resource-Policy: cross-origin`。CORP 只能是 `cross-origin`：沙箱 iframe 里的页面是不透明源，`same-origin` 与 `same-site` 都会让它自己的样式、脚本与图片在服务器回 200 之后被浏览器丢弃。这条链路的边界是会话 token 与不可达的端口，不是 CORP。

注册名 `app` 留给应用自己的界面；其余名下的内容一律按来自用户目录的不可信内容处理，响应再加两道，由 `WebResourceServlet` 统一处理、handler 不用管：

- 可执行类型降级：脚本类型（HTML 规范认作 JavaScript 的那一组）与 `text/css` 改成 `text/plain`（参数照留），`application/wasm` 改成 `application/octet-stream`。配 `nosniff`，页面没法把用户目录里的文件当同源脚本、样式或 wasm 加载。
- 除惰性类型外一律加 `Content-Security-Policy: sandbox`，没给 `Content-Type` 的也加：导航过去得到不透明源，碰不到桥也带不了 cookie。惰性类型按白名单：`image/*`（SVG 除外）、`audio/*`、`video/*`、`font/*`、`application/pdf`、`application/octet-stream`、`application/zip`、`application/json`、`text/plain`、`text/csv`。PDF 必须留在白名单里，浏览器内建 PDF 阅读器要用得上。

token 证明请求来自本应用的引擎，不证明用户身份，也不替代按窗口或来源的授权；访问范围、路径落点与上传目标的校验仍由 handler 负责。

## 公共 origin 与引擎映射

端口每次启动都由系统分配，而 localStorage、HTTP 磁盘缓存与 V8 代码缓存都按 origin 或 URL 键。页面若直接跑在 `http://127.0.0.1:<port>`，页面存在 localStorage 里的状态与哈希静态资源的缓存都会跨启动作废。所以页面地址固定用公共 origin（默认 80，URL 不带端口），由 WebView 后端把它接到真正的端口上。改公共 host 等于换 origin，页面已有的 localStorage 与缓存随之作废。

公共 host 由应用给，框架只接受单标签的 `<label>.localhost`（`WebResourceAuth.requirePublicHost`）。Chromium 对这类 host 不查 DNS、不看 hosts，并按可信源对待，安全上下文与 Secure cookie 都成立。

`WebResourceServer.session()` 给出 `WebResourceSession`：cookie 名与值、`publicBaseUrl`（页面 origin）、`bindBaseUrl`（回环地址）、路径交接地址与交接 token。它是给 WebView 后端接会话用的值对象，给出两种映射写法：

- `hostResolverRules()`：`MAP <publicHost> 127.0.0.1:<port>`，只映射这一个主机名，别的流量照常走各自的路。JCEF 后端用它：在 `onBeforeCommandLineProcessing` 里给 browser 进程追加 `--host-resolver-rules`，然后经 `CefCookieManager` 种 cookie。
- `proxyRules()` 与 `proxyBypassRules()`：`http=127.0.0.1:<port>` 与 `<-loopback>;localhost;127.0.0.1`，给按 profile 设代理的后端用。Chromium 对 `*.localhost` 有「隐含直连」规则，`<-loopback>` 把它取消，后两项再把 dev server 的 localhost 地址放回直连。到达 Jetty 的是代理形态的请求（绝对形式请求行、多一个 `Proxy-Connection` 头），`WebResourceServlet` 按路径路由，不受影响。代价是这个 profile 里任何明文 `http://` 外站请求也会被送到本服务并吃 403，要加载外网内容得另开一个不带这条映射、也没有会话 cookie 的 profile。

## 请求与响应契约

- `WebResourceRequest` 有字节与流两种形态：`body()` 读整份（流形态第一次调用会把流读完），`bodyStream()` 单次可读，`contentLength()` 来自 `Content-Length`，`headers()` 里没有 `Cookie`。服务器按 `Config.maxRequestBodyBytes`（默认 600 MiB）在读 body 前回 413；没报长度的分块上传读过上限时，流抛 `IOException`。
- `WebResourceResponse.ofFile(contentType, path)` 交出文件由服务器流式写出：单段 `Range` 回 206、不可满足 416，多段或非 `bytes` 单位按没有 Range 处理；`ETag` / `Last-Modified` 命中回 304；HEAD 只回头；文件不在回 404。默认 `Cache-Control: no-cache`。
- `byte[]` 形态由服务器补 `Content-Length`，没给 `Cache-Control` 时补 `no-cache`。需要长缓存（例如带内容哈希的静态资源给 `immutable`）时由 handler 自己给。
- 客户端中途断开不算服务端错误（例如 PDF.js 探到 Range 支持后会中止整份请求），写体循环吞掉后按 debug 记一条。

## 上传路径通道

拖进页面的文件要以磁盘路径到达 Java。真 HTTP 上服务器收到的是字节，所以 WebView 后端装了上传改写：POST 若发往路径交接地址、是 multipart 且每个文件段都 file-backed，就改写成 url-encoded 表单，文件段折成字段 `jdesk.uploadFiles`（JSON 数组 `[{"name","path"}]`），字符串字段原样保留；服务器解回 `WebResourceRequest.uploadFiles()`。含内存 Blob 段的 multipart 不改写，按真 multipart 流到服务器。改写判定是后端共用的纯函数 `UploadPathRewrite`；JCEF 后端在 `CefResourceRequestHandler.onBeforeResourceLoad` 里从 `CefPostData` 取文件段的磁盘路径，改写后 `setPostData`，解不出就原样放行。

改写时还附上字段 `jdesk.uploadToken`：服务器启动时另生成的 32 字节交接 token（`WebResourceSession.uploadToken()`），只在服务器与后端的改写之间流转，不给页面、不写日志。服务器只在表单恰好带一份 `jdesk.uploadFiles`、一份 token 且 token 常量时间比对相等时才解出 `uploadFiles`，否则为空表——页面脚本自己 POST 的路径清单因此不算数；页面的字符串字段占用这两个保留名时整份不改写。token 在交给 handler 之前从表单里剥掉。`WebResourceUploadFile` 只带文件名与路径，框架不校验路径在不在、能不能读，那是 handler 的事。

⚠️ **改写只认路径交接地址那一个 URL**，别的地址的 multipart 一律原样带着字节到服务器——将来加真的文件上传口不必担心正文被折成路径清单。地址由应用声明：`WebResourceServer.Config#withPathHandoff(path)`（以 `/` 开头的完整路径）；不声明就没有这条通道。页面来自别的 origin（如 dev server）时地址对不上，这条通道也不生效。

## 开发期

页面若来自 dev server（例如 Vite），同源相对路径会先打到 dev server，要由它把资源路径代理到 `bindBaseUrl()`：改写 `Host` 为回环地址、去掉 `Origin`、附上会话 cookie（`WebResourceServer.session()` 的 cookie 名与值）。鉴权放行 `127.0.0.1:<port>` 这个 Host 就是为此准备的。框架本身只在内存里保存 token，要不要、怎么交给 dev server 由应用决定；上传交接 token 不要交出去。

## 生命周期与配置

`WebResourceServer` 是 `Disposable`，注册进应用服务容器后随容器释放而停机（`Config.stopTimeout`，默认 2 秒）。`start()` 只能调一次，会话 token 与交接 token 每次启动都换。默认配置：线程 2..8、连接空闲 30 秒、并发请求 16、请求体上限 600 MiB、等 handler 60 秒；`WebResourceServer.Config` 可覆盖。连接器固定 1 个 acceptor + 1 个 selector，否则 Jetty 按 CPU 核数取默认值，会从这个小池子里占掉大半。

请求体上限之外还有一道更紧的闸：`application/x-www-form-urlencoded` 整份进内存，超 1 MiB 直接 413。更细的上限（例如单张图片的大小）由 handler 自己卡。

## 尚未完成的边界

- 按窗口、来源或用户上下文的访问授权：框架不提供，token 不能代替这层判断。
- 网络 / 远程访问：绑定地址、鉴权、路由是三个独立部件，将来换绑定策略与登录态即可，路由与 handler 不动。

## 源码入口

- [WebResourceServer](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/http/WebResourceServer.java)、[WebResourceServlet](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/http/WebResourceServlet.java)、[WebResourceAuth](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/http/WebResourceAuth.java)、[WebResourceSession](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/http/WebResourceSession.java)、[FileResponsePlan](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/http/FileResponsePlan.java)：服务器、适配、鉴权、给后端的会话与文件响应。
- [WebResourceSchemeRegistry](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/WebResourceSchemeRegistry.java)、[WebResourceRoute](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/WebResourceRoute.java)、[WebResourceRequest](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/WebResourceRequest.java)、[WebResourceResponse](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/WebResourceResponse.java)：注册表、路由映射与请求响应。
- [UploadPathRewrite](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/UploadPathRewrite.java)、[WebResourceUploadForm](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/resource/WebResourceUploadForm.java)：上传改写判定与路径表单。
- [JcefResourceSessionInstaller](../../jdesk-framework-frame/src/main/java/com/jdesk/frame/webview/jcef/JcefResourceSessionInstaller.java)：JCEF 后端的 host 映射、种 cookie 与上传改写。

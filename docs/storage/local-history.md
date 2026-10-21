# 本机历史（Local History）

框架层的「每次保存都留一版」能力：内容按 SHA-256 存进对象库，版本行与头表放在每个 scope 自己的 `history.sqlite`，[Managed VFS](managed-vfs.md) 的对账 / 通知 / 写入三条路与应用自己的保存路径把内容交进来。库建在 [SQLite Storage](sqlite-storage.md) 上。

## 分层

| 层 | 包 | 内容 |
|---|---|---|
| core | `com.jdesk.core.history` | `LocalHistoryService`、`LocalHistoryStore`、`HistoryKey(kind, id)`、`HistorySource`、`HistoryPolicy`、`HistoryVersion`、`HistoryActivity`、`DeletedEntry`、`HistoryStats`、`HistoryUnavailableReason`、`ContentSource`、`CaptureRequest` / `CaptureResult`；`core.vfs.VfsHistoryKeys`（VFS 文件的 key，kind `file`，id 是 `VfsFileId`） |
| frame | `com.jdesk.frame.history` | `DefaultLocalHistoryService`（按 scope 开关 store、维护调度；`frame-plugins.xml` 注册为应用服务）、`SqliteLocalHistoryStore`、`HistoryObjectStore`、`HistorySchema`、`HistoryRetention`、`NoopHistoryStore` |
| frame VFS | `com.jdesk.frame.vfs.HistoryFeed` | `DefaultManagedVfsService` 三条路的捕获钩子与基线 |

依赖方向 core ← frame ← 应用；frame 对 `HistoryKey.kind` 只当字符串，应用的业务概念不进 core / frame。开哪些 scope、目录放在哪、用什么策略，按 key 种类读当前内容、写回还原以及界面，都由应用决定。

## 目录与文件

一个 scope 一个目录，由 `LocalHistoryService.openStore(scopeId, historyDir, policy)` 打开（目录不存在就建，同一 scope 已开时返回已开的 store）：

```
<historyDir>/
  .gitignore          内容 `*`：整个目录不进 Git
  history.sqlite      descriptor id `jdesk.history.<scopeId>`（scopeId 里 `[A-Za-z0-9._-]` 以外的字符换成 `_`），main 命名空间 v01
  objects/ab/cdef…    对象文件：SHA-256 十六进制前 2 位作子目录，deflate 流，临时文件写完、刷盘后再原子改名（`AtomicFiles`）
```

`history.sqlite` 四张表：`lh_objects`（哈希、原始与存储字节数）、`lh_activities`（活动名、来源、起止时间、参数；参数是开活动时交的一段文本，框架原样存取、不解释）、`lh_versions`（key、路径、对象哈希、换行归一化哈希、大小、时间、来源、活动、标签、封住标记、跳过原因）、`lh_heads`（每个 key 的路径、头版本、内容哈希、删除时间；路径由最近一次落下版本的捕获或带位置的删除标记写下，内容没变的捕获不改它，所以只对已删除的条目可靠：文件回来时内容没变，路径仍停在删除时的位置）。打开缺 `lh_activities.params` 列的旧库时记一条 ERROR（提示先转换再用），只报不改，也不走下面的隔离重建。

库坏了（SQLite 报 corrupt 或 notadb）时把 `history.sqlite` 连同 `-wal`、`-shm` 改名加上 `.broken-<epoch>` 后缀，再重建空库。目录建不出、库暂时打不开（磁盘满、权限这类环境故障，库不隔离）或重建后仍打不开时，`openStore` 不抛异常，换成什么都不记的空 store，`stats().unavailableReason()` 给原因 `HistoryUnavailableReason`（`DIRECTORY_UNAVAILABLE`、`TEMPORARILY_UNAVAILABLE`、`OPEN_FAILED`），技术细节只进日志。界面据此提示历史暂不可用；原因文字由应用按 `code()`（`directoryUnavailable`、`temporarilyUnavailable`、`openFailed`）取文案，框架不给界面文字。

## 捕获

触发点：

- watcher 批次与 `reconcile`：外部改动，来源 EXTERNAL。
- `notifyChanged` 与 `fileWritten`：应用自己写盘并登记的改动，来源 APP。
- `fileOpened` 与 `reconcile` 里的基线：BASELINE。
- 应用直接调 `capture`：自己的保存路径交 APP，显式「存一版」交 SNAPSHOT（可带 `label`）；还原见下文「活动」。

VFS 服务按同一个 scopeId 用时现取 `findOpenStore(scopeId)`：要让某个 catalog scope 进历史，就用这个 scope 的 id 开 store；没开 store 的 scope 零开销。调用方不等捕获结果，捕获失败只记日志、回 `captured = false`，永不拖住写路径。一次对账里的文件用 `captureAll` 整批交，别逐个 `capture`。

捕获线程上（全部 store 共用一条，按提交顺序执行）：读字节 → SHA-256 → 与头版本哈希相同即跳过（`skippedReason = unchanged`，顺手清掉 `deleted_at`）→ 文本另算换行归一化哈希（去掉 `\r` 后的 SHA-256，存 `norm_hash`；两版 `normHash` 相同而内容哈希不同就是只差换行，由调用方比出来）→ 定活动 → 判折叠 → 存对象文件（同内容已有则不重写）→ 一个事务里写对象行、改写头版本或新增版本行、更新头表。

折叠：头版本未封、来源相同、活动相同、来源属于 APP 或 EXTERNAL、距头版本 `captured_at` 小于折叠窗口（`foldWindow`，默认 5 分钟）时改写头版本，否则新起一行。BASELINE / SNAPSHOT / RESTORE 各自独立成行并自动封住。来源不同就不折叠：外部改动到来时另起一行，之前的 APP 头版本不会再被改写；来源切回 APP 时同样另起一行。

封版与删除标记：`seal(key)` 封住头版本，调用方关闭一个文档时调；`closeStore` 时自动 `sealAll`。`seal`、`markDeleted`、`unmarkDeleted`、`purge`、`rekey` 都排在已提交的捕获之后并等它落地（最多等 5 秒，超时记日志，操作仍留在队列里）；只有 VFS 对账喂过来的删除标记走 `markDeletedLater`——同样排队保序，但不等，否则一次删掉整棵子树会把全应用共享的对账线程占住好几秒。删除标记一律**整批交**（`markDeleted` / `markDeletedLater` 的 Collection 或 Map 重载），一批合成少数几个事务（每 500 个 key 一个）：每个事务自开一条连接，按条交时这笔开销会乘上文件数（实测 11.5 ms 一条，1500 个文件约 17 秒）。Map 重载的值是各 key 删除那一刻所在的位置，写进头行路径（`headPath` 读它），已标记的删除时间与位置都不覆盖。VFS 对账发现的缺失由 `HistoryFeed` 带位置交（取快照行最后的路径）；改名、移动时同一个 `VfsFileId` 同时出现在新增与缺失里，不算删除。已删除的文件恢复出来换了 id 时，用 `rekey(from, to)` 把旧 key 的版本与头行挪到新 key 下。

活动：外部改动按空闲 60 秒（`externalIdleGap`）归入同一个外部改动活动（活动名为空串）。`beginActivity(name, source[, params])` 开出显式活动，之后来源为 APP 的捕获都挂在它上面，直到 `endActivity`；没有显式活动时为空。`params` 是一段由调用方解释的文本（如 JSON），经 `HistoryVersion.activityParams()` 原样读回。活动按**提交捕获那一刻**开着的算，执行器积压时捕获晚于 `endActivity` 执行也不会丢。还原由应用完成：开一个来源为 RESTORE 的活动，把那一版的内容经平常的写路径写回，再结束活动；这期间来源为 APP 的捕获记成 RESTORE，独立成行并封住。

关 store 分三步：先 `awaitPending` 等这个 store **自己**排队的捕获与删除标记落完（在 EDT 上调 `closeStore` 只等 1 秒，其他线程等 30 秒），再 `sealAll`，最后关库。少了第一步，删掉一个大文件夹后立刻关 store，队列里上千条删除标记会撞上已关的句柄，那批删除永远进不了历史。到点没等完记一条 `history.pending.left` 汇总；关库之后才轮到的那些是 DEBUG，不再一条一个堆栈。

应用退出（服务 `dispose`）时不逐个 store 等：所有 store 共用一条捕获线程，先整体排空一次、最多等 2 秒，再逐个 `sealAll` 并关库，逐个各等一次会把退出耗时乘上 store 数。这个上限别往长了调：外部启动程序可能给宿主进程的退出设了时限，越线整个进程会被强杀，强杀后连 `sealAll` 都跑不到，主动放行好过被强杀；平常一次保存后退出的排空只要几十毫秒，成百上千条的大批次怎么都等不完，加时间也没用。到点放行只丢最近几版历史，文件本身不经这条路。在 EDT 上关 store 只等 1 秒是同一个理由：退出时卸载界面的那条路就跑在 EDT 上，在那里久等会越过退出时限。

文本判定 = `HistoryPolicy.textExtensions`（应用可在默认值之外加自己的扩展名），兜底看前 8KB 有没有 NUL；它只决定算不算换行归一化哈希。对象上限默认 20 MB（`maxObjectBytes`），超过只写版本行（对象为空，`skippedReason = too-large`）。基线只看扩展名、不嗅探内容：扩展名在 `textExtensions` 里且不超过 64KB（`eagerBaselineMaxBytes`）的文件在 `reconcile` 时给还没有历史的补基线，更大的在 `fileOpened` 时补，其余不补。

## 保留与维护

`maintain()` 在打开 store 1 分钟后跑一次，之后每次跑完隔 6 小时再跑；`HistoryRetention` 按 key 分事务，依次：

1. 稀疏：24 小时内全留；30 天内每小时留最晚一版；之后每天留最晚一版。带标签的版本与头版本不删，但仍占它所在的桶。
2. 已删除的 key 到期（`deleted_at` 加 `deletedRetention`，默认 30 天）整个清掉，带标签的版本也不留，`deletedEntries()` 里随之消失。标签只在文档还在时生效（防第 1 条稀疏、第 4 条总量清理）。
3. 清扫：`lh_objects` 减去 `lh_versions` 仍引用的哈希，删文件与行；顺手清掉强杀留下的临时文件。
4. 总量超上限（`libraryCapBytes`，默认 1 GB，按对象落盘字节算）从最老的非标签、非头版本删起，每删一批（50 条）清扫一次，直到降到上限以下或没有可删的。

维护只在**清扫**这一段与捕获互斥（选孤儿与删文件之间插进一条引用同一对象的捕获就会删掉活对象）；稀疏与到期清除逐 key 各一个事务，不挡住捕获——整段占着写锁的话，两万个 key 要几十秒，这期间全应用的历史捕获、封版和删除标记都停摆。稀疏前先用一条 SQL 筛出真有可能被稀疏的 key。

另有两个即时清理入口：`purgeExpiredDeleted()` 立刻清掉已到期的已删除条目；`purge(key)` 彻底删掉一个 key 的版本与头行，对象留给下次清扫。

## 测试

core：`HistoryPolicyTest`。frame：`HistoryObjectStoreTest`、`SqliteLocalHistoryStoreTest`（折叠、封版、外部活动、改键、删除标记与删除位置）、`HistoryRetentionTest`、`DefaultLocalHistoryServiceTest`（坏库隔离、缺列旧库只报不改、`.gitignore`）、`DefaultManagedVfsServiceHistoryTest`（三条路的捕获与基线、缺失带位置标删除）。

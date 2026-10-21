# Managed VFS

Managed VFS 是 JDesk Framework 为“需要长期引用的真实文件资源”提供的最小文件身份层。它不替代本地文件系统，也不扫描用户所有文件；它只在调用方明确把文件纳入管理时，为该文件分配稳定的 `VfsFileId`。

## 核心概念

**被管理文件**

被管理文件是已经进入框架资源管理体系的文件。它通常需要被文档、附件、最近打开、预览缓存或编辑器长期引用。普通磁盘文件不会自动变成被管理文件；调用方直接调用 `VfsCatalog.registerFile(...)`，或在已选定 root 内扫描、对账时登记，才会获得 `VfsFileId`。

**VfsFileId**

`VfsFileId` 是 catalog 分配的稳定文件身份。它不是路径，也不会因为 catalog 内的 `moveFile(...)` 改变路径而变化。调用层可以把 `VfsFileId` 保存到自己的业务表里，例如文档正文文件、附件文件或封面图引用。

**VfsRoot**

`VfsRoot` 表示一个工作区或模块纳入管理的本地根目录。第一版只支持本地路径。`registerRoot(rootKey, name, path)` 按 `rootKey` 认身份而不是按路径：key 与磁盘位置无关，root 目录整体搬迁后仍解析到同一个 `VfsRootId`，其下全部 `VfsFileId` 与挂在上面的业务元数据随之保留。key 取一个与路径无关的常量（如 `"docs-root"`）。

**VfsCatalog**

`VfsCatalog` 是一个按 scope 打开的文件身份目录。一个 scope 通常对应一个文档库、一个业务工作区或一个模块资源。catalog 是懒加载资源：应用启动时不需要打开所有 catalog，工作区关闭时也应该关闭对应 catalog。

## 分层边界

Core 层定义通用抽象：

- `VfsFileId`
- `VfsRootId`
- `VfsRoot`
- `VfsManagedFile`
- `VfsFileStatus`
- `VfsCatalog`
- `VfsCatalogBackend`
- `VfsScanResult`
- `ScanFilter`
- `NativeFileIdReader`
- `ManagedVfsBridge`
- `ManagedVfsChangeEvent`
- `ManagedVfsChangeListener`
- `ManagedVfsChangePublisher`
- `ManagedVfsService`

Frame 层提供 SQLite 实现：

- `DatabaseVfsCatalogBackend`
- `SqliteVfsCatalog`
- `DefaultManagedVfsService`

应用层决定业务含义：

- 哪些文件要纳入管理
- 每个工作区或模块打开哪个 catalog
- 业务表如何保存 `VfsFileId`
- 数据库文件放在哪里

## SQLite 存储方式

`SqliteVfsCatalog` 使用调用方提供的 `DatabaseHandle`。因此 VFS catalog 表可以和业务表放在同一个 SQLite 数据库文件中。

当前会创建这些表：

```sql
jdesk_vfs_roots
jdesk_vfs_files
```

这两张表归框架所有，但住在宿主模块的库里，因此走独立的 schema 命名空间 `jdesk.vfs`（见 [SQLite Storage](sqlite-storage.md) 的「组件自带的 schema 命名空间」），与宿主 descriptor 的 `main` 版本线互不干扰。当前只有一条 `vfs-snapshot-v01` 建表步骤（见 `VfsCatalogSchema`）。

`jdesk_vfs_files` 含冗余 `parent_path` 列及目录索引，以便直接查询目录子项；不存 `absolute_path`（它是 `root_path` + `relative_path` 的纯派生值，由 `SqliteVfsCatalog#mapFile` 现拼），文件路径查询走 `(root_id, relative_path)`，因此快照可随 root 目录整体迁移。`native_file_id` 保存可用的文件系统原生身份，辅助识别外部移动，不能替代业务引用的 `VfsFileId`。

另有三列服务于「跨目录列全部文件、按名或按时间排序」这类查询，使调用方能纯单表查：

- `file_name`：相对路径末段（不是整条相对路径）。
- `file_name_sort`：文件名的 `Collator` 排序键，存 BLOB。SQLite 对 BLOB 的默认比较就是 memcmp，故用默认 `order by` 即得本地化序（中文按拼音，而非码点）。规则由打开 catalog 时的 locale 决定，见下节。
- `file_created_at`：文件创建时间（epoch 毫秒，来自扫描时的 `BasicFileAttributes.creationTime()`）。`moveFile(...)` 不改它——文件没变，只是换了位置。

这三列没有索引：三个排序字段要三个索引，写放大落在每次扫描上，而单库文件量级下 SQLite 的内存排序足够快。

`read_only` 是用户设的只读标记，新行为 0，由 `VfsCatalog.setReadOnly(...)` 写、`VfsManagedFile.readOnly()` 读；它只记用户意图，不含操作系统的只读属性。标记挂在快照行上，跟着 `VfsFileId` 走：

- 保留：应用内 `moveFile(...)` 的移动与改名；重扫与对账，包括内容或 mtime 变化、判 `MISSING` 后在原路径复活、按原生身份认回的外部移动。
- 不继承：拿到新身份的行从 0 起，例如调用方 `removeFile(...)` 删行后同路径再登记（如从本机历史恢复已删除的文件），或认不回原行的外部移动。
- `registerFile(...)` 遇到同路径已有的行（含 `MISSING`）沿用那一行，标记一并带回；要让新出现的文件从 0 起，调用方登记后自己清。
- `setReadOnly(...)` 返回该行是否存在（任何状态都算）；它不改 `content_version`、不发布事件，调用方需要时现读。

业务模块不要直接修改这些表。业务表只保存 `file_id` 字符串引用，例如：

```sql
create table documents (
  doc_id text primary key,
  title text not null,
  body_file_id text not null
)
```

## 使用示例

```java
DatabaseHandle database = databaseService.open(descriptor);
ManagedVfsService vfsService = application.getService(ManagedVfsService.class);

VfsCatalog catalog = vfsService.openCatalog(
        "myapp-docs",
        DatabaseVfsCatalogBackend.of(database)
);

VfsRootId rootId = catalog.registerRoot("docs-root", "Docs", docsPath);
VfsManagedFile bodyFile = catalog.registerFile(rootId, "notes/note-1.md", false);

String fileIdForBusinessTable = bodyFile.fileId().value();
```

关闭工作区时释放 catalog：

```java
vfsService.unwatch("myapp-docs");
vfsService.closeCatalog("myapp-docs");
database.close();
```

`closeCatalog(...)` 不会关闭数据库，也不隐式撤销监控。已调用 `watch(...)` 的 scope，应先 `unwatch(...)`，再关闭 catalog 和数据库；数据库生命周期仍由调用层控制。

## 文件名排序键与 locale

`openCatalog(...)` 另有一个带 `Locale` 的重载，该 locale 决定这个 catalog 写 `file_name_sort` 用哪套排序规则：

```java
VfsCatalog catalog = vfsService.openCatalog(
        "myapp-docs",
        DatabaseVfsCatalogBackend.of(database),
        Locale.CHINA
);
```

不带 `Locale` 的两参重载等价于在这里传 `Locale.getDefault()`。

调用方必须知道的三件事：

- **同一个库固定传同一个 locale。** 排序键在首次登记、`moveFile(...)` 和识别外部移动后按当时的 collator 计算，已落库的键不会因下次换 locale 打开而重算。同一个库先后以两个 locale 打开，表里就会并存两代排序键，`order by file_name_sort` 会**静默错序且不报错**。
- **要传常量，别传 `Locale.getDefault()`。** 同一 scope 已打开时直接复用已有 catalog（后来的 locale 不生效，第一个赢），但那个 catalog 失效后（例如它依赖的数据库句柄被调用方释放）会按下次传入的 locale 重建——传默认 locale 的话，界面语言一变，重建后的 catalog 就换了一套规则（JVM 默认 locale 跟着界面语言走，见 [多语言](../framework/i18n.md)）。
- 换 JDK 或 CLDR 排序数据变更有同样的效果：重扫**不会**刷新存量行的键（那要求每次扫描重算全表，代价压在常态路径上）。真要换排序规则，做法是清掉快照重扫，而不是原地换 locale。

## 当前能力

- 按 scope 懒加载 catalog。
- 通过 `DatabaseHandle` 把 VFS catalog 表放入 SQLite。
- root 按稳定 `rootKey` 识别，同 key 可更新根目录位置；同一目录不能登记为两个不同 root。
- 文件按 `rootId + relativePath` 去重。
- `moveFile(...)` 保持 `VfsFileId` 不变。目标路径上已标 `MISSING` 的别的行在同一事务里删掉（与 `removeFile(...)` 一样不级联业务元数据），挪过来的文件不接它的身份和只读标记；目标路径被别的 `PRESENT` 行占着时抛 `StorageException`，一行不改。
- 外部移动或改名在原生文件身份可用、候选双向唯一时保留 `VfsFileId`，边界见下节。
- `removeFile(...)` / `removeFiles(...)` 撤销快照登记；批量删除在一个事务内完成，不替调用方删除磁盘文件或级联业务元数据。
- `updateStatus(...)` 更新状态并递增 `contentVersion`。
- `setReadOnly(fileId, readOnly)` 设或清用户只读标记，语义见「SQLite 存储方式」。
- `findFile(fileId)` 和 `findFile(rootId, relativePath)` 查询。
- `findFile(absolutePath)`：按 root 路径前缀把磁盘绝对路径解析为托管文件。
- `refresh(rootId, relativePath, recursive, filter)` / `scanRoot(rootId, filter)`：遍历真实磁盘对账进快照（新增登记 / 内容变更 / 缺失标记），返回 `VfsScanResult` 差异。差异按行在 PRESENT 集合里的进出分类：从 MISSING 复活（例如从回收站还原到原路径）与改名移动后的新路径一样记 `added`，`updated` 只表示一直在且内容变了。
- `listChildren(rootId, dirRelativePath)`：从快照枚举某目录直接子项（目录优先、不读盘）。
- `openCatalog(scopeId, backend, locale)`：按 locale 生成 `file_name_sort` 排序键，见「文件名排序键与 locale」。
- `ManagedVfsBridge`：活动 `VirtualFile` 树与 `VfsManagedFile` 快照互转。
- `ManagedVfsChangePublisher` / `ManagedVfsChangeListener`：把一次 refresh 的 `VfsScanResult` 作为**一个批量事件**经 [MessageBus](../framework/message-bus.md) 投递（空批次不发，per-subscriber 异常隔离）。
- `watch(...)` / `unwatch(...)`：多个 scope 共用一个懒建的目录监控器；按各 scope 的 `ScanFilter` 过滤事件、去抖并对账后发布。去抖有最长等待，持续改动时也会先刷一批；监控意外终止（被监控的文件夹被移走、事件循环出错，监控库在全部 WatchKey 失效后会自己关闭）按失败处理：清掉已监控状态并带退避自动重建。一个 scope 或一个目录的对账失败只丢它自己那一份。
- `syncFromDisk(...)`：对正在监控的 scope 做全树对账，是显式“与磁盘同步”的兜底入口。
- `notifyChanged(...)`：应用已写盘并自行更新快照后，显式通知正在监控的 scope；不能依赖 watcher 再次发现已被自己对账消除的差异。
- `fileWritten(scopeId, fileId)` / `fileOpened(scopeId, fileId)` / `reconcile(scopeId, rootId, filter)`：应用自己写盘后的单文件对账、打开文件时的懒基线、整树对账（对账 + 基线 + publish）。这三条路连同 watcher 批次与 `notifyChanged` 把文件内容交给 [本机历史](local-history.md)（`HistoryFeed`）；没开历史 store 的 scope 跳过历史内容处理。
- 关闭 catalog 后拒绝继续访问。
- `DatabaseDescriptor.location(...)` 支持绝对目录；调用方可以将 catalog 所用 `DatabaseHandle` 放在宿主模块的私有数据库文件中，例如跟着内容目录一起走的元数据库。该能力只决定数据库文件位置，不会替 Managed VFS 自动发现、迁移或管理任意外部数据库。

`syncFromDisk(...)` 兜的是 watcher 漏事件：网络盘收不到变更通知、WatchService 因权限或句柄耗尽挂掉、事件溢出，以及监控重建的空窗期。这些失效都是整棵树级别的，用户也正是因为看不到某个文件才要同步、指认不出是哪个目录，所以只提供整树对账，不提供按目录的粒度。它与 watcher 走同一条发布路径，编辑器冲突检测这类订阅者能一并跟上。

`notifyChanged(...)` 补的是写盘方自己造成的盲区：写盘方按「谁改的谁登记」在写完当场更新快照（`registerFile`、`fileWritten` 等），watcher 随后那趟对账看到快照与磁盘完全一致，三个列表全空，订阅者什么都收不到；`syncFromDisk` 也救不了，它同样只是再对一次账。所以 `notifyChanged` 不再对账，直接把交进来的 id 解析成当前快照行发布出去，解析不到的跳过。典型场景是跨窗口写入：一处借用同一个 catalog，往另一个正开着、正被监控的工作区里写文件。

## 外部变化与文件身份

普通 watcher 批次按受影响目录非递归对账，并逐级登记新目录；不会因根目录一个文件变化而重扫整树。事件溢出、以及事件处理本身出错（都意味着可能漏了事件）时，去抖后对相应 root 递归对账兜底——溢出事件在监控库里取哈希就会抛异常，只能从异常回调认出来。直接调用 `VfsCatalog.refresh(...)` 只更新快照，不发布事件；打开 scope 时需要历史和通知的，使用 `ManagedVfsService.reconcile(...)`。

消失的判定以盘上现状为准。受影响目录已不在盘上时（整个文件夹被移出 root 或删进回收站，监控库只报它自己一条事件），对账从它盘上还在的最近祖先起算，由那一趟把它判成 `MISSING`，并把它下面仍是 `PRESENT` 的行一并标上、一起进 diff，本机历史与其他订阅者据此收到删除。反过来，读不到不算消失：对账起点读属性出 IO 错（网络盘一时不可达）、某个目录打不开、目录枚举中途出错时，相应范围这一轮不判 `MISSING`，留给下一次对账。

外部移动的第二级线索是 `native_file_id`。当前 Windows 实现读取原生文件身份，其他平台返回不可用；同一 root 中，新出现条目可与本次消失或已标 `MISSING` 的行配对。只有非空身份、相同类型且双向唯一时才认作同一文件。配对后沿用 `VfsFileId`，通过旧路径消失与新路径出现让两侧目录更新。

原生身份只在一部分变化里保持不变，按它配对的能力也就止于此：

| 变化 | 原生身份 |
|---|---|
| 改名、同卷内移动 | 不变 |
| 就地覆写（实测记事本、IntelliJ IDEA 的保存都是） | 不变 |
| 替换式保存（先写临时文件再顶替，如 Word）、git checkout 或回滚 | 换成新值 |
| 跨卷移动 | 换成新值 |
| zip 打包再解开、拷离本机再拷回 | 旧值不复存在 |

Windows 实现经 `GetFileInformationByHandleEx(FileIdInfo)` 一次取回卷序列号与 128 位文件 id，两者一起编进身份串：不同卷的文件 id 各自独立编号，不带卷号，另一个卷上碰巧同号的文件就会被认成同一个。文件 id 里必须含 MFT 序列号：NTFS 复用一条 MFT 记录时会把序列号加 1，实测 git 回滚一个文件后记录号不变、只有序列号变了，只比记录号就会把顶替进来的新文件认成原来那个——那是静默的错误关联，比认不出来更难查。打开句柄用零访问权限加全共享模式，只问元数据，被别的进程独占锁定的文件和只读文件也拿得到身份，也不挡别人读写或删除。

其他平台目前恒返回空，第二级线索整体不可用，第一级路径匹配照常工作。将来在 Unix 上接 `BasicFileAttributes.fileKey()`（JDK 在 Unix 上用 device ID 加 inode，在 Windows 上恒为 null）之前，要先在真机上确认被删文件的 inode 会不会被复用：JDK 对此未作规定，inode 传统上会被回收，复用就会把新文件认成旧文件；必要时与文件创建时间一起做双重校验。

同一路径换了身份（Word 保存、git checkout 这类先写临时文件再顶替）时，快照行保留原 `VfsFileId`，`native_file_id` 是否跟着刷新取决于原件是否还在 root 内：完整根对账靠「本趟没见到原身份」判定；局部对账无法看全，由 `NativeFileIdReader.locate` 按旧身份向操作系统回查——已删除或已在 root 之外就刷新，仍在 root 内别处则保留、等那个目录对账时配对，回查不了按仍在处理。这样原件被搬走后旧路径上的顶替文件不会拿走原身份，而日常的原地替换也不会让身份陈旧。

链接不跟也不列：符号链接（文件或目录），以及经 NTFS junction / 卷挂载点转到别处的目录，都不进快照，遍历也不往里走，指向 root 内的同样如此。对账起点本身是链接、或它到 root 之间的某一级是链接时，按「不存在」处理；原来登记过、后来换成链接的路径，下一次对账变 `MISSING`。单文件登记（`registerFile`）、刷新（`refreshFile`）与移动（`moveFile`）同一口径：路径上有链接就记 `MISSING`。打不开的链接（比如「文档」目录里带拒绝权限的 `My Music`）同样按不存在处理，不算「读不到」。监控库的启动遍历与新建目录的补发遍历也跳过链接。root 本身经 junction 访问不受影响：root 从不参与判定。判 junction 看重解析标记是否带「名字替代」位（junction、卷挂载点、符号链接都带），只对带重解析点的目录多查一次（`FindFirstFile`）；不单凭重解析点属性判：同步盘的占位目录、去重文件也带这个属性，但不是链接，照常按普通目录处理（同步盘不专门支持、也不特意跳过）。判据在 core 的 `FileLinks`，应用自己遍历用户文件夹时也用它判链接（见 [可取消的长操作](../framework/cancellation.md)）。已知不判的：WSL 建的 LX 符号链接文件照常列成普通文件（文件不查标记）。

无法采集身份、硬链接导致候选含糊、跨卷移动或文件被替换等情况，不保证保留移动前身份；没有内容哈希兜底。路径仍是第一层匹配依据：外部删除后在原路径重建会复用原快照行；调用方主动删除则应撤销登记，让以后同名新文件取得新身份。应用内移动或改名经 `moveFile(...)` 带着自己的身份到达，目标路径上的 `MISSING` 行随之删掉。目录删除由调用方收集子树后一次调用 `removeFiles(...)`，不逐条提交。

`removeFile` / `removeFiles` 真删行，不留死行：`registerFile` 按路径命中已有行时会按盘上现状把它对账成 `PRESENT`、保住原 `file_id`，死行一旦这样被复活，之后在同一路径上新建的对象就继承了上一个对象的身份，连同宿主库里按这个 id 挂着的全部元数据。这与「外部删掉、又在原路径重建」是两回事：那里保住 `file_id` 正是自愈；调用方主动删除表达的是「这个对象没了」，同名的下一个应当拿到新身份，分界就在删行这一步。删整棵子树也别拿 `removeFile` 逐行删：每行一个事务、整体不原子，半途失败会留下一批指向已不存在路径的孤儿行，而删除这条路没有自愈手段（移动那条路还有原生身份配对能把行认回来）。

## 当前边界

以下能力不由当前 Managed VFS 自动提供：

- 应用启动时自动加载 catalog。
- 跨所有 scope 的单一全局 catalog；全局共享 watcher 不改变 catalog 按 scope 隔离的边界。
- 基于内容哈希的移动识别，以及非 Windows 平台的原生身份适配。
- 通用文件内容缓存；当前 catalog 本身已经是磁盘元数据的持久化快照，本机历史另有内容存储。
- 虚拟文件指针。
- 自动搜索索引和通用编辑器生命周期管理；应用自行订阅变更并编排保存、冲突与关闭。

这些能力后续可以在不改变业务表引用方式的前提下继续扩展。

## 目录监控的实现取舍

所有 scope 共用的目录监控器（`DirectoryWatcherVfsWatcher`）在 root 集变化时整个关掉重建：监控库 `io.methvin:directory-watcher` 不能在运行中增删路径，重建时它的 `registerPaths` 会同步遍历全树。`setRoots` 因此只记下目标、交给监控器自己的后台线程去重建，返回时监控还没生效，连续几次请求合并成「重建到最新目标」——`unwatch` 可能由界面卸载触发、跑在 EDT 上，同步遍历会卡住界面。旧监控已关、新监控还没注册完的那段（约一次全树遍历）里，外部改动收不到；这个空窗有意不补偿：补偿得对所有 root 按溢出的处置递归重扫，而重建随每次 root 增减发生（通常跟着窗口开关），等于把全树扫描放回热路径，漏掉的改动由 `syncFromDisk` 兜底。

事件进去抖队列前先过一道路径闸门（`DefaultManagedVfsService#acceptPath`）：用事件所属 root 的 `ScanFilter` 判条目本身，再逐级判它在 root 之下的祖先——`ScanFilter` 只判条目自身，不管祖先是否被排除；root 本身不判，它碰巧叫 `node_modules` 也照样监控。这道闸门管的是事件量：放在被监控目录里的 SQLite 库与它的 `-wal` 整个会话都在写，不挡在这里，每次写入都会重置去抖，用户真实的文件变更会被持续推迟。

按路径找所属 root 时从路径本身逐级向上精确查（`findRegistration`），不用有序表的 `floorEntry` 加 `startsWith`：Windows 的 `Path` 按字母转大写后的字符比较，分隔符 `\`（0x5C）排在大写 ASCII 字母之后，同时监控 `D:\proj\alpha` 与 `D:\proj\alphabet` 时，`D:\proj\alpha\docs` 的 floorEntry 是 `alphabet`，`startsWith` 判否，`alpha` 的事件就被静默丢弃。

去抖后报来的目录未必是快照里的一行：可能落在被排除的子树里（如 `.git/objects`），也可能是刚新建、还没入册的目录。直接拿它做非递归对账会绕过排除——遍历起点已在子树内部，`SKIP_SUBTREE` 无从生效，`.git` 的子项会被逐个登记；反过来「不在快照里就丢弃」又会让新目录永远发现不了。所以对账目标（`refreshTarget`）取它和它的祖先里最近一条 `PRESENT` 行，root 恒可作目标；判据是 `PRESENT` 而不是「有这一行」：非递归对账只看起点的子项、不碰起点自己那一行，拿 `MISSING` 行当起点，它就永远复活不了（外部删掉目录又建回同名目录时，子文件登记上了、文件夹却不显示），整棵 `a/b/c` 删掉又重建时目标会一路上移好几级。

从目标到报来的目录要逐级对账（`refreshChain`），每一级把下一级登记出来。外部拷入 `docs/folder/sub/x.md` 时几个事件落进同一个去抖批次，受影响目录是 `docs/folder` 与 `docs/folder/sub`，后者解出的目标是 `docs`；只对账目标那一层的话只会登记到 `docs/folder` 为止，`x.md` 漏掉，看上去拖进来的文件夹是空的。逐级走与批次里目录的先后无关（批次本就是无序集合）；也不能改成对目标递归对账，目标可能就是 root，那会退化成全树重扫。

`refreshTarget` 开头把 `acceptPath` 的两道排除判定（目录自身、它的祖先）又做了一遍。生产路径上走不到它们：闸门已把这些事件挡在队列外，监控器交上来的受影响目录又恒为变动路径本身或其父目录，都在闸门判过的范围里；留着是纵深防御，让对账不依赖调用方先过闸门，直接投事件的测试和将来别的监控实现拿到同样的行为。两道判定既不是生产必需，也不是可删的冗余，`DefaultManagedVfsServiceWatchTest` 里两条绕过闸门直接投事件的用例各钉住一道：`staleSnapshotRowForExcludedDirDoesNotLetItsChildrenBack` 传 `.git`，命中自身判定；`excludedDeepDirIsDroppedEvenWhenTheEventBypassesAcceptPath` 传 `.git/objects`，命中祖先判定；删掉任一道，对应那条就变红。

## 路径大小写

`PathCaseSemantics`（core 的 `com.jdesk.core.path`）把「两个路径串算不算同一个名字」收在一处，只用于比较和做键（把规范形另存一列当索引也算）；显示给用户、传给 `Files.*`、建文件或改名一律用原串，否则用户敲的大小写就丢了。当前只有 Windows 实现：不区分大小写，规范形是 `toLowerCase(Locale.ROOT)`，只折叠大小写，分隔符、尾随斜杠、`.` / `..`、Unicode 规范化都留给调用方。

规范形相等不等于文件系统认为同名。Java 的大小写折叠不对称，往小写折与往大写折给出的「相同」不是同一套判定，两个方向都会分叉：

| 两个名字 | 转小写后 | 转大写后 |
|---|---|---|
| `Å`（U+00C5）与 `Å`（U+212B 埃符号） | 相同 | 不同 |
| `straße` 与 `STRAẞE`（ẞ 为 U+1E9E） | 相同 | 不同 |
| `ﬀ`（U+FB00 连字）与 `FF` | 不同 | 相同 |

所以两个不同的磁盘名可能折进同一个键（`WindowsPathCaseSemanticsTest.differentDiskNamesCanCollideIntoOneKey` 钉住），用作 map 键时会静默拿到别人的值；文件系统自己的同名判定（折哪一侧、用哪张表）测试摸不到真磁盘，没有核对。规范形的长度也可能与原串不同：`İ`（U+0130）折成 `i` 加 U+0307，`İstanbul` 从 8 个字符变成 9 个，所以不能拿规范形上的下标或长度回去切原串，要余下那一段就在原串上另算。

大小写敏感是卷的属性，不是平台的属性：Windows 上可以挂区分大小写的网络盘，macOS 也能把 APFS 格式化成区分大小写，按操作系统硬判会判错。加第二个实现时要按路径所在的卷挑实现，而不是照着 Windows 实现按操作系统复制一份；`PathCaseSemantics.current()` 不收参数、看不到卷，调用形态得跟着改，例如改成收路径的工厂。

# SQLite Storage 功能介绍与使用说明

本文说明 `jdesk-framework-frame` 当前提供的 SQLite 存储框架能力、推荐用法和第一版边界。该能力位于 frame 层，目标是让普通应用模块和插件都能使用统一的数据保存、查询、迁移、生命周期和数据库文件管理机制。

## 设计定位

SQLite Storage 不是某个应用的私有工具，而是 frame 层通用服务。

它解决的问题包括：

- 为应用模块或插件创建受控 SQLite 数据库文件。
- 为每个数据库提供独立生命周期，调用方可以主动释放资源。
- 应用关闭时兜底释放未关闭的数据库资源。
- 封装 JDBC 连接、statement、result set 和异常转换。
- 提供受控 `DatabaseSession` / `DatabaseTransaction`，减少裸 `Connection` 泄漏。
- 提供 migration 表、migration log、版本管理和失败回滚。
- 提供轻量 Entity 注解和基础 CRUD DAO。
- 默认应用适合桌面应用的 SQLite 配置：WAL、busy timeout、弱约束策略。

## 当前包结构

公共 API 位于 `jdesk-framework-frame`：

| 包 | 说明 |
|---|---|
| `com.jdesk.frame.storage` | 数据库服务、数据库描述、handle、session、transaction、migration、row wrapper 和异常。 |
| `com.jdesk.frame.storage.dao` | `EntityDao`、`Repository`、`RowMapper`、`TypeConverter` 和 `SqlEntityDao`。 |
| `com.jdesk.frame.storage.entity` | 轻量 Entity 注解和 schema 解析。 |
| `com.jdesk.frame.storage.sqlite` | SQLite 后端实现，应用代码通常不直接依赖。 |

应用代码应优先依赖 `com.jdesk.frame.storage`、`dao` 和 `entity` 包，不直接依赖 `sqlite` 包内实现类。

## 服务注册和加载

`DatabaseService` 已在 `frame-plugins.xml` 中注册为 frame application service：

```xml
<applicationService serviceInterface="com.jdesk.frame.storage.DatabaseService"
                    serviceImplementation="com.jdesk.frame.storage.sqlite.SqliteDatabaseService"/>
```

调用方通过应用服务容器获取：

```java
DatabaseService storage = application.getService(DatabaseService.class);
```

服务本身不是数据库连接。它只负责创建、打开、追踪和释放具体数据库资源。真正代表一个数据库的是 `DatabaseHandle`。

## 核心概念

| 概念 | 含义 |
|---|---|
| `DatabaseService` | frame 层数据库入口，用于打开、查找和关闭数据库资源。 |
| `DatabaseDescriptor` | 描述一个数据库：id、owner、文件名、配置、migration、entity。 |
| `DatabaseOwner` | 数据库归属，当前支持 application owner 和 plugin owner。 |
| `DatabaseOptions` | SQLite 运行配置，例如 WAL、foreign keys、busy timeout、synchronous。 |
| `DatabaseHandle` | 一个具体数据库资源，可读写、事务、创建 DAO，也可主动关闭。 |
| `DatabaseSession` | 一次受控数据库访问上下文，只在 callback 内有效。 |
| `DatabaseTransaction` | 一次受控事务上下文，使用同一个底层连接和事务边界。 |
| `DatabaseMigration` | 显式 schema 迁移步骤。 |
| `EntityDao` | 基于 Entity 注解生成的基础 CRUD DAO。 |
| `RowMapper` | 把一行查询结果映射为 Java 对象。 |

## 数据库文件路径

默认数据库文件由 frame 管理，路径为：

```text
<home>/system/databases/<owner>/<name>.sqlite
```

示例（开发态 home 是 `.root/<appId>/`）：

```text
.root/myapp/system/databases/app/myapp/notes.sqlite
.root/myapp/system/databases/plugin/demo-plugin/plugin-data.sqlite
```

`DatabaseOwner.application("myapp")` 会产生 `app/myapp` 路径段。
`DatabaseOwner.plugin("demo-plugin")` 会产生 `plugin/demo-plugin` 路径段。

`DatabaseDescriptor` 会校验 database id、name 和 owner，避免路径逃逸。

应用模块也可以通过 `DatabaseDescriptor.location(...)` 指定一个绝对目录，数据库文件就放在 `<location>/<name>.sqlite`，不再使用上述默认 system 数据库目录；传相对路径会抛 `StorageException`。

## 打开一个数据库

示例：

```java
DatabaseDescriptor descriptor = DatabaseDescriptor.builder("myapp.notes")
        .owner(DatabaseOwner.application("myapp"))
        .name("notes")
        .options(DatabaseOptions.defaults())
        .migration(DatabaseMigration.of(1, "create-notes", session -> {
            session.update("""
                    create table if not exists notes (
                      id text primary key,
                      title text not null,
                      body_path text not null,
                      updated_at integer not null
                    )
                    """);
        }))
        .build();

DatabaseHandle database = storage.open(descriptor);
```

如果同一个 database id 已经打开，`DatabaseService.open(...)` 会返回现有 handle，调用方不需要重复创建数据库连接。这时后一份 descriptor 里的 location 和 migration 都不会生效；它解析出的文件路径与已开的不同时，`open` 直接抛 `StorageException`。一个库只留一个创建 descriptor 的入口。

报错而不是照旧返回已开的句柄，是因为这种错配原本完全无声：同一个库有两个创建 descriptor 的入口、位置又不同时，谁先跑就决定库落在哪，而先后取决于启动任务和用户操作谁快，两次运行可能不一样。输的一方以为自己开的是指定位置的库，实际拿到的是另一个文件：真正的库没被打开，新加的迁移永远跑不到，数据被分成两半。同路径重开照常静默返回，那是多个调用方共用一个库的正常情形；判路径时也不建目录，被拒绝的位置不会留下空目录。

## 主动释放数据库

**一个 database id 全进程只有一份句柄，框架不做引用计数**：关闭由**属主**负责——按应用约定开出这个库、管着它生命周期的那个模块或会话。其余调用方拿到的是同一个对象，关掉它就是把别人正在用的库关掉。只是要用一下、不想承担关闭责任的，用 `storage.borrow(descriptor)` 拿一份**关不掉的**（`close` / `dispose` 是空操作，注册进自己的 Disposer 父节点也不会误关）。

`DatabaseHandle` 代表一个具体数据库资源。属主不再需要数据库时，应主动关闭：

```java
database.close();
```

也可以通过服务关闭：

```java
storage.close("myapp.notes");
```

应用关闭时，`DatabaseService` 会兜底释放仍然打开的数据库。这个兜底只用于防泄漏，不应替代模块自己的生命周期管理。

关闭的语义：返回时文件已经释放（等两个执行器停下，也等调用方线程上还没读完的同步 `read`；各至多等 5 秒，超时只记 WARN），紧接着删库或把目录挪走才不会失败；还没轮到执行的排队写入会被取消，等在上面的调用方收到 `StorageException`（写确实丢了，但不会静默、也不会永远等下去）。文件在句柄还开着时被外部删掉，下一次 `open` 会丢掉这个句柄重建并重跑迁移，而不是在原路径上静默造一个空库。

关闭时两个执行器先都发出停止、再逐个等待，总耗时取两者中较长的那个而不是相加。超时只记 WARN、不抛：`dispose` 跑在清理路径上（包括应用关闭），在这里抛异常会让排在后面的资源得不到释放，调用方拿到这个失败也做不了什么，不如留一条日志。等待中被中断时恢复中断标记再返回，因为 `dispose` 可能跑在本身正被取消的线程上，吞掉标记那次取消就丢了。

## SQLite 默认配置

默认配置来自 `DatabaseOptions.defaults()`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| WAL | 开启 | 使用 `PRAGMA journal_mode=WAL`，提升读写并发体验。 |
| synchronous | `NORMAL` | 在性能和可靠性之间取桌面应用常用折中。 |
| busy timeout | 5 秒 | 使用 `PRAGMA busy_timeout=5000`，减少短暂锁竞争导致的失败。 |
| foreign keys | 关闭 | 默认弱约束策略，不启用 SQLite 外键检查。 |

可以按数据库覆盖：

```java
DatabaseOptions options = DatabaseOptions.builder()
        .wal(true)
        .synchronous(DatabaseOptions.Synchronous.NORMAL)
        .busyTimeout(Duration.ofSeconds(10))
        .foreignKeys(false)
        .build();
```

框架约定默认不使用外键硬约束。关系字段保存普通 id，异常、孤儿数据和修复由 Repository、诊断工具或业务逻辑处理。

## 读写和事务

### 读取

```java
List<NoteSummary> recent = database.read(session -> session.queryList(
        "select id, title, updated_at from notes order by updated_at desc limit ?",
        row -> new NoteSummary(
                row.getString("id"),
                row.getString("title"),
                row.getLong("updated_at")),
        20
));
```

`DatabaseSession` 只在 callback 内有效，不要保存到字段里，也不要跨线程使用。

### 写入

```java
database.write(session -> {
    session.update("""
            insert into notes(id, title, body_path, updated_at)
            values (?, ?, ?, ?)
            """,
            id, title, bodyPath, System.currentTimeMillis());
    return null;
});
```

每个 `DatabaseHandle` 有一个单写队列，写操作会串行执行，降低 SQLite writer lock 竞争。

### 事务

```java
database.transaction(tx -> {
    tx.update("""
            insert into notes(id, title, body_path, updated_at)
            values (?, ?, ?, ?)
            """,
            id, title, bodyPath, now);

    tx.update("""
            insert into note_tags(id, note_id, tag)
            values (?, ?, ?)
            """,
            tagId, id, "draft");

    return null;
});
```

transaction callback 正常返回时提交；抛出运行时异常或 error 时回滚。提交或回滚本身失败时，挂起的写入一律不落盘，调用方拿到原来的异常（回滚失败挂在它的 suppressed 上）。

当前第一版注意事项：

- `EntityDao.insert/update/deleteById` 自己会通过 `DatabaseHandle.write(...)` 执行。
- 不要在 `write` / `transaction` / 迁移的 callback 里再同步调用同一个 handle 的 `write`、`transaction` 或 `EntityDao` 方法：写线程只有一条，这样嵌套会直接抛 `StorageException`，要用 callback 传进来的 session。
- 事务内需要多语句原子写入时，在同一个 callback 里逐条调用 `tx.update(...)`。一次 `update` 只执行一条语句，一段 SQL 里写了多条会直接抛 `StorageException`（`CREATE TRIGGER` 的语句体算一条）。
- 后续会补事务感知 DAO 或 transaction-scoped DAO。

## 异步 API

如果不希望阻塞当前线程，可以使用异步读写：

```java
CompletableFuture<List<NoteSummary>> future =
        database.readAsync(session -> session.queryList(
                "select id, title, updated_at from notes order by updated_at desc limit ?",
                row -> new NoteSummary(
                        row.getString("id"),
                        row.getString("title"),
                        row.getLong("updated_at")),
                20));
```

SQLite 实现会拒绝在 Swing EDT 上执行同步读操作。UI 代码应使用异步 API 或应用自己的后台任务调度。

## Migration

frame 不只依赖 `PRAGMA user_version`，还会创建自己的 schema 表：

```sql
jdesk_schema_version
jdesk_schema_migration_log
```

执行规则：

- migration 按 `version()` 升序执行；版本号必须为正且不重复，否则开库时直接报错。
- 已执行版本不会重复执行。
- 每个 migration 放在独立事务中。
- 成功后写入 `jdesk_schema_version` 和成功日志。
- 失败时 rollback，并在 rollback 后写入失败日志。
- 最终版本会同步到 `PRAGMA user_version`，方便外部工具查看。

### 组件自带的 schema 命名空间

`jdesk_schema_version` 按 `namespace` 分行。descriptor 声明的 migration 走 `main` 命名空间，这是数据库属主自己的版本线。

框架组件如果把自己的表放进宿主模块的数据库（[Managed VFS](managed-vfs.md) 的快照表就是这样），不能挤进 `main`：那份 descriptor 属于宿主模块，版本号是宿主的，而组件往往在数据库打开很久之后才被创建。这类组件用 `DatabaseHandle.migrate(namespace, migrations)` 走自己的命名空间：

```java
database.migrate("jdesk.vfs", VfsCatalogSchema.migrations());
```

规则：

- 两条版本线各占 `jdesk_schema_version` 一行，各自独立推进，互不跳过。
- `main` 是保留命名空间，组件传它会被拒绝——放行等于让组件的版本号覆盖宿主的，此后宿主自己的 migration 会被静默跳过。
- `PRAGMA user_version` 只镜像 `main`。它只有一个整数，组件写就是覆盖宿主的值。外部工具看 `user_version` 得到的始终是属主版本。

**任何写进数据库的表都要有版本线。** 用 `create table if not exists` 现场建表看起来省事，代价是加一列就让全部存量库开不了：建表语句对已存在的表一个字都不改，旧库一 select 新列就抛裸 SQL 错，而 `user_version` 纹丝不动，既检测不到也无从自愈。更麻烦的是这个失败面测不出来——测试用的临时库全是新建的，一次就建成最新形状。

简单 migration 示例：

```java
DatabaseMigration createNotes = DatabaseMigration.of(1, "create-notes", session -> {
    session.update("""
            create table if not exists notes (
              id text primary key,
              title text not null,
              body_path text not null,
              created_at integer not null,
              updated_at integer not null
            )
            """);
});
```

复杂 schema 变更，例如删除列、改列类型、拆表、合表、数据搬迁，应写显式 migration，不应依赖注解自动推断。

## Entity 注解和基础 DAO

第一版支持 Java record entity。

示例：

```java
@Table(
        value = "notes",
        indexes = {
                @Index(name = "idx_notes_updated_at", columns = {"updated_at"})
        }
)
public record NoteEntity(
        @Id @Column("id") String id,
        @Column("title") String title,
        @Column("body_path") Path bodyPath,
        @Column("created_at") long createdAt,
        @Column("updated_at") long updatedAt,
        @Transient String previewText
) {
}
```

注册 entity：

```java
DatabaseDescriptor descriptor = DatabaseDescriptor.builder("myapp.notes")
        .owner(DatabaseOwner.application("myapp"))
        .name("notes")
        .entity(NoteEntity.class)
        .build();
```

使用 DAO：

```java
EntityDao<NoteEntity, String> notes =
        database.entityDao(NoteEntity.class, String.class);

notes.insert(new NoteEntity(
        "n1",
        "Draft",
        Path.of("notes/n1.md"),
        now,
        now,
        "Draft"));

Optional<NoteEntity> loaded = notes.findById("n1");
```

当前 DAO 能力：

- 启动时创建表。
- 启动时创建索引。
- `insert`
- `findById`
- `update`
- `deleteById`

当前 Entity 注解边界：

- 支持 Java record。
- 每个 entity 必须有 `@Table`。
- 每个持久化 record component 必须有 `@Column`。
- 必须且只能有一个 `@Id`。
- `@Transient` 表示不参与存储。
- `@SoftReference` 当前只记录弱引用意图，不生成外键，不影响 SQL。
- 不做 classpath 自动扫描，调用方需要在 descriptor 里显式注册 entity。

当前内置类型转换：

| Java 类型 | SQLite 存储 |
|---|---|
| `String` | `text` |
| `Path` | `text` |
| `boolean` / `Boolean` | `integer`，0/1 |
| `int` / `Integer` | `integer` |
| `long` / `Long` | `integer` |
| `Instant` | `integer`，epoch milliseconds |
| `LocalDateTime` | `integer`，UTC epoch milliseconds |
| `LocalDate` | `integer`，UTC 当天起点 epoch milliseconds |
| `enum` | `text`，枚举名 |
| `double` / `float` | schema 可识别为 `real`，DAO 转换仍需后续完善 |
| `byte[]` | schema 可识别为 `blob`，DAO 转换仍需后续完善 |

`TypeConverter` 公共接口已经存在，但可插拔 converter registry 还没有接入。复杂类型目前建议由业务层转换为基础类型，或用显式 `RowMapper`。

## DatabaseSession 和 JDBC Connection 的区别

`Connection` 是 JDBC 驱动层对象，直接暴露会带来生命周期、线程和事务边界问题。

`DatabaseSession` 是 frame 暴露给调用层的一次受控访问上下文：

- 生命周期只限 callback。
- 自动关闭 statement 和 result set。
- 统一参数绑定。
- 统一把 `SQLException` 转成 `StorageException`。
- 统一行映射入口。
- 由 `DatabaseHandle` 控制连接创建和释放。

高级场景可以使用：

```java
Connection connection = session.unwrap(Connection.class);
```

这主要用于 migration 或少数 backend-specific 集成。普通 DAO/Repository 代码不建议长期持有裸连接。

## 推荐 Repository 写法

业务模块可以定义自己的 repository，把业务语义留在模块内，把底层 SQL 复杂度交给 frame。

```java
public final class SqlNoteRepository {
    private final DatabaseHandle database;
    private final EntityDao<NoteEntity, String> notes;

    public SqlNoteRepository(DatabaseHandle database) {
        this.database = database;
        this.notes = database.entityDao(NoteEntity.class, String.class);
    }

    public void saveNote(NoteEntity note) {
        notes.findById(note.id()).ifPresentOrElse(
                existing -> notes.update(note),
                () -> notes.insert(note));
    }

    public List<NoteEntity> listRecent(int limit) {
        return database.read(session -> session.queryList(
                "select * from notes order by updated_at desc limit ?",
                row -> new NoteEntity(
                        row.getString("id"),
                        row.getString("title"),
                        row.getPath("body_path"),
                        row.getLong("created_at"),
                        row.getLong("updated_at"),
                        null),
                limit));
    }
}
```

Repository 的目标是让上层调用只看到业务方法，例如 `saveNote`、`listRecent`、`findByTag`，不直接关心连接、事务、参数绑定和基础 CRUD。

## 弱约束和 SoftReference

当前默认不启用 foreign key，也不会生成外键约束。

如果需要表达弱引用，可以使用：

```java
@Table("note_tags")
public record NoteTagEntity(
        @Id @Column("id") String id,
        @SoftReference(table = "notes", column = "id")
        @Column("note_id") String noteId,
        @Column("tag") String tag
) {
}
```

这表示 `note_id` 逻辑上引用 `notes.id`，但数据库不会硬性阻止孤儿数据。后续诊断工具可以基于 `@SoftReference` 扫描和修复异常数据。

## 当前限制和后续计划

当前第一版已经可用于模块级 SQLite 数据保存，但还不是完整 ORM。

已实现：

- 通用 `DatabaseService`。
- 多数据库 handle 管理。
- 模块/插件 owner 路径隔离。
- 独立释放数据库资源。
- 应用关闭兜底释放。
- WAL、busy timeout、默认关闭 foreign key。
- migration version 和 migration log。
- migration 失败 rollback 和错误记录。
- Java record entity 注解。
- 基础 CRUD DAO。
- `RowMapper` 和受控 `DatabaseRow`。
- 同步/异步读写。
- 单数据库写队列。

后续计划：

- transaction-scoped DAO。
- `QuerySpec` / `Page` / `Sort`。
- 可插拔 `TypeConverter` registry。
- 更完整的 `EntityMapper`。
- 安全新增列和 schema 差异报告。
- `@SoftReference` 诊断和修复工具。
- 慢查询日志和 SQL verbose 开关。
- `EXPLAIN QUERY PLAN` 诊断入口。
- 读连接池。
- PreparedStatement 缓存。
- WAL checkpoint 策略。
- 数据库备份/恢复。
- 数据库加密。
- 全文索引集成。

## 接入建议

第一阶段接入推荐顺序：

1. 为模块定义自己的 `DatabaseDescriptor`。
2. 明确 owner，应用模块用 `DatabaseOwner.application(...)`，插件用 `DatabaseOwner.plugin(...)`。
3. 用 migration 创建关键表，复杂变更都走显式 migration。
4. 简单表可以同步定义 record entity 和 `EntityDao`。
5. 在模块自己的 service/repository 中持有 `DatabaseHandle`。
6. 模块 dispose 时主动关闭 handle。
7. UI 线程使用异步 API 或后台任务，不做同步数据库读写。

# 可取消线程上的长操作

后台线程靠 Java 的线程中断取消。关窗时窗口对自己的执行器调 `shutdownNow()`，远程取图用 `cancel(true)`，应用退出时 `ApplicationTask` 取消 IO 任务，这些都会中断正在跑的线程。

JDK 17 有几类读写不理会中断：
- `Files.newInputStream`、`Files.newOutputStream`、`Files.readAllBytes` 拿到的通道被设成不可中断；
- `Files.copy`（两种）、`InputStream.transferTo`、`Files.walkFileTree` 不查中断。

所以长的读写、拷贝、遍历必须按块或逐项做，并在块与块、项与项之间查中断。框架在 `jdesk-framework-core` 的 `com.jdesk.core.io` 里提供统一工具。

## 工具

| 方法 | 用途 |
| --- | --- |
| `Cancellation.checkpoint(doing)` | 当前线程被中断就抛 `InterruptedIOException`，给声明了 `IOException` 的读写、拷贝、遍历 |
| `Cancellation.checkpointUnchecked(doing)` | 同上，抛 `CancellationException`，给不声明 `IOException` 的循环（逐行、逐项、逐目录） |
| `Cancellation.isCancellation(failure)` | 沿 cause 链认取消：中断类异常与 `CancellationException`，`SocketTimeoutException` 不算 |
| `Cancellation.uninterruptibly(work)` | 屏蔽中断跑完一段：期间检查点不触发；写库等结果、`AtomicFiles` 就位与 `DiskMoves` 的退避照样等到底；结束时还原这期间见过的中断 |
| `InterruptibleFiles.copy(in, out)` | 按块拷流，块间查中断 |
| `InterruptibleFiles.copy(source, target, replace)` | 先写目标同目录的临时文件，再改名就位；被中断或失败删临时文件，目标从不留半截；保留源文件的修改时间与「来自网络」标记，不带只读等属性；只拒目录，抛 `SourceIsDirectoryException`（不是裸的 `FileSystemException`，免得被当成文件被占用去重试） |
| `InterruptibleFiles.keepOrigin(source, copy)` | 把源文件的「来自网络」标记（`Zone.Identifier` 流）与修改时间带到副本上；自己用 `copy(in, out)` 拷用户文件时，拷完、就位前调它 |
| `InterruptibleFiles.readAllBytes(file)` | 整份读入，块间查中断 |
| `InterruptibleFiles.walkFileTree(…)` | 遍历时每进一个目录、每访问一个文件前查中断 |
| `FileLinks.isLink(entry, attrs)` | 判遍历到的条目是不是链接：符号链接，或目录联接、卷挂载点；遍历要跳过链接时用它，别单凭 `attrs.isOther()` |

## 规则

1. **可取消线程上的长读写、拷贝、遍历一律用上面的工具。** 不直接用 `Files.copy`、`Files.readAllBytes`、`Files.walkFileTree`、`transferTo`。很短、有上限的读写可以例外，如散文件的 20 MB 读取。
2. **检查点只查、不清中断标记。** 用 `isInterrupted()` 的语义，不要 `Thread.interrupted()`。屏蔽段的入口由 `uninterruptibly` 负责。
3. **「读不出就降级」「失败就写负缓存」「失败记 WARN」的 catch，先用 `isCancellation` 放过取消。**
   - 取消照样往上抛，或安静收尾，只记 DEBUG。
   - 不能把取消吞成「读不出」「解不动」「导入成功但缺图」这类静默的错误结果。
4. **能停就停、停下收干净。**
   - 中途停下时，清掉这次产生的半截：临时文件、半截暂存、这次新建的文件。
   - 已完成的部分按该操作原有的失败语义处理，但不报成普通失败。
5. **成套的改盘 + 改库操作不中途停。** 移动、改名、删除、移动带图、拖入落位、AI 整理之类「失败要挪回」的操作：
   - 开始前查一次中断；
   - 从第一处改盘起包进 `uninterruptibly`，跑完再还原中断标记；
   - 前面的只读判断照常可取消。
6. **桥层最后一道日志认得取消。** 冒到桥层的取消，不论包在 `LocalizedException`、`CompletionException` 还是别的里面，只记 DEBUG。
7. **写库被中断时这次写照样落库。** 调用方以 `StorageException` 走人；在屏蔽段里则等到写完。在写线程上跑的长事务（VFS 整树对账）查写线程自己的中断，关库时整个事务回滚。
8. **自己按块拷用户文件，拷完、就位前调 `keepOrigin`。** 不调就会丢掉「来自网络」标记与修改时间；`copy(source, target, replace)` 已经替你调了。
9. **遍历跳过链接用 `FileLinks.isLink` 判。** 只有符号链接、目录联接、卷挂载点算链接；别的文件夹一律按普通文件夹处理，不为同步盘（OneDrive 之类）另做支持或跳过。

# 全局媒体分页与网络回填交叉流程审计（2026-09-30）

本轮仅修复有明确调用链证据的状态所有权问题；不提交、不推送、不在本机编译或跟踪 Actions。基线为 `7f5328a44`。附件目录 `.codex-remote-attachments/` 未改动。

## 问题与修复

| 问题证据（基线） | 修改后的不变量 |
| --- | --- |
| `FilteredSearchView.maybePrefetchGlobalMedia` 在每次正常邻底预读时增加 `globalMediaEmptyPageAutoLoads`；达到 5 次后即使每页有可见新消息也停止。 | 预读只负责调度；仅 DB 页应用后无新增可见消息身份才增加连续无进展计数。可见身份新增（含 1000 条窗口等量换入）即清零。 |
| `onGlobalMediaSyncRoundFinished` 无条件把 `globalMediaOlderHasMore` 设为 true 并请求 DB；空网络 batch 又调用它，可能形成空网络轮→空 DB 页→预读的回环。 | DB 页/窗口裁剪独占 DB EOF、游标和分页方向。空网络 batch 是终态，不制造 DB 读取。网络成功写入时，存储事务内比较写前/写后实际可查询行 ID；只有新增才设置 dirty，DB EOF 时尝试一次尾游标复读，DB 忙时留待当前页应用后处理。 |
| `requestGlobalMediaDatabasePage` 原先先修改页方向/replace，再以网络 coverage 阻断；DB 回调失败和应用页均清 `globalMediaCoverageWaiting`。 | 本地 DB 读取不受网络 coverage 阻断，且只在真正派发时记录页参数。DB 路径不写网络 waiting；旧 generation/token 回调仍被隔离。空 replace 页的 `hasMore` 也来自 DB 结果。 |

`MessagesStorage.putGlobalMediaSearchMessagesWithCount` 保持原保存事务与回滚语义，额外返回新可查询媒体行数；预览仍使用原布尔回调包装，不改变其显示链。没有增加全局持久化标记，新增的 UI `globalMediaCacheDirty` 只在当前搜索世代内有效，并在 reset 清除。

## 自动验证与边界

运行 `git diff --check` 与六个 Node 测试：`test-global-media-sql.cjs`、`test-global-media-scheduling.cjs`、`test-global-media-reentry.cjs`、`test-global-media-performance.cjs`、`test-global-media-paging.cjs`、`test-global-media-cross-flow.cjs`，均通过。新测试执行生产方法的调度控制流与生产 SQL（SQLite），覆盖连续 6 次有进展预读、稀疏页计数/复位、满窗口等量换入、空网络 batch、EOF dirty 单次复读、DB busy 后补读、旧 generation/token 回调、窗口裁剪＋live 中间插入＋反向 seek 无重复/漏页。旧预读计数和空 batch 回调的负控会使相同断言失败。

这些是 Java 源片段转 JS 的控制流与生产 SQL 的 SQLite 级验证，**不是** Gradle 编译、CI 产物、真机网络或 UI 验证。窗口裁剪＋live＋反向 seek 用真实 SQL 和手写窗口切片组合，未执行生产 `trimGlobalMediaWindow`；负控是对当前生产方法片段注入旧错误，不是运行历史 HEAD。测试中的 Android 组件为 stub；不能据此断言 RecyclerView 滚动、相册跨页、媒体预览、弱网重试或设备性能已通过。DB 尚有下一页时 dirty 被普通分页接管，窗口前/中/后新增行仍依赖既有 live merge/双向 seek 链；本轮未完整验证 live pending overflow 与该接管的全部时序。媒体编辑/删除的缓存失效语义、预览与历史同步并发、真实相册边界、生命周期切换，以及全部 200 页/1.5 秒 live merge 的端到端体验，本轮未改也未证明；如设备复现异常，应以日志/最小复现另开审查，不扩大本次修复。

## 最小设备验收矩阵（后续有 APK 时）

| 场景 | 操作 | 预期观察 |
| --- | --- | --- |
| 连续正常分页 | 全局媒体滚动超过 6 个有新缩略图的缓存页 | 仍自动续页；顺序稳定、不重复，页面不刷新消失。 |
| 稀疏过滤与停止 | 在窄日期范围滚动，穿过空/少量命中页，再到可见增长页 | 有增长后继续自动加载；连续无可见进展到上限时停止且可手动重试。 |
| EOF 与网络回填 | 冷缓存滚至本地末端，让网络返回空页和有新媒体页各一次 | 空页不反复请求；真正落库后尾部继续可读；错误不伪装为本地 EOF。 |
| 双向窗口与 live | 超过 1000 条后上翻/下翻，期间插入中间日期媒体并打开返回预览 | 无缺页/重复、锚点和 3/6 列布局稳定，预览返回位置不跳。 |
| 生命周期 | 网络与 DB 并发时离开/重进页面，旋转或后台恢复 | 旧回调不覆盖新搜索；不无限 loading，不出现旧方向的页。 |

记录每项的账号/媒体量、过滤条件、网络日志、可见页号与屏幕录像。只有在实际设备上观察通过，才能升级相应的设备结论。

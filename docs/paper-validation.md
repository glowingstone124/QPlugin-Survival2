# 本地 Paper 实测记录

2026-10-28，在 `hot-update` 工作区执行；未提交或发布到生产环境。

## 环境与范围

- Paper **26.2 build 134**，与本项目 Paperweight 开发包一致；服务器完整插件为 `paper-modular-01` 构建。
- Amazon Corretto **25.0.1**，`-Xms1G -Xmx2G`；Apple M3 Max，14 核、36 GiB 内存，macOS arm64。
- 独立超平坦世界，视距和模拟距离均为 2；游戏、RCON、QAPI 桩端口仅监听 `127.0.0.1`。
- 加载完整生存服插件及 `QHotProbe` 测试工具；外部 QAPI 请求只进入本机桩服务。
- 测试目录为 `build/paper-validation/`，所有世界、私有 RCON 配置和故障包都在忽略的构建目录中。

服务端来自 [Paper 官方构建信息](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/134)，
下载的 `paper-26.2-134.jar` SHA-256 为 `3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b`。

测试玩家由插件自身的 NMS 假人管理器创建，使用真实 CraftPlayer、玩家 PDC、Bukkit 事件注册表及调度器。
测试工具通过 Bukkit 注册的监听器触发玩家事件并捕获公告内容；没有接入网络客户端。
因此这些结果验证服务端模块生命周期，不代表 Velocity → Paper → QMod 联机链路或多人容量测试。
没有使用 `/reload` 或卸载 JavaPlugin。

## 更新与回滚

从真实 `SponsorPrefixModule` 源码编译 v1/v2 两版，显示名分别改为 `[V1]` / `[V2]`；
同时加载真实 `daily-login` 模块。测试版额外注册逐 tick 计数与命令事件计数，用于观察旧回调是否停止。
另一个候选在 `healthCheck()` 抛出异常。所有应用操作通过实际 `scripts/deploy-hot.py` 和宿主文件请求回执完成。

验证结果：

| 场景 | 结果 |
| --- | --- |
| v1 → v2 在线更新 | 显示名实际改变，旧版 tick 计数停止，新版计数继续 |
| `/qhot rollback` | 恢复 v1 代码，active/previous 互换 |
| 候选健康检查失败 | 返回 `ROLLED_BACK`，旧模块重新注册，持久化指针不变 |
| 将 `state.json` 临时替换为目录，令原子写入失败 | 返回 `ROLLED_BACK`，旧业务继续工作；测试结束恢复原状态文件 |
| 连续交替更新 10 次 | 注册数量保持稳定，回执中的发布与已经刷新的状态文件一致 |
| 主动 GC 后检查 WeakReference | 退休 ClassLoader 已回收，仅当前两个模块的加载器仍存活 |
| 篡改 JAR、替换宿主指纹 | 分别拒绝及返回 `RESTART_REQUIRED`，active 不变 |
| 每日公告 | 同一天的 PDC 标记跨更新、回滚保留，不重复发送 |
| 公告配置热加载 | 新配置生效；非法 JSON 保留上一配置；disabled 不消耗当日标记 |
| 停服并重新启动同一宿主 | 恢复 active/previous；经 Paper 保存/Bukkit 加载的玩家每日公告标记继续有效 |

第一次真实连续发布暴露出状态心跳竞态：成功回执已经写出，而 `status.json` 仍指向前一版本，
下一次部署因此收到 `CONFLICT`。现已改为先完成状态文件刷新，再发出确认回执；连续发布回归通过。
资源检查等待初始异步文件投递完成后采样，避免把尚未执行的一次性主线程回调当作常驻任务。
假人管理器本身只创建新实体，不加载磁盘玩家数据，且停服时提前移除假人。
因此跨重启的玩家标记测试在停服前执行 `save-all`，测试工具在重建 CraftPlayer 后调用 Bukkit `loadData()`，
走真实的玩家数据保存/读取路径；没有为这次验证修改生产假人行为。

完整 Gradle 构建、打包及 **65 项 JVM 测试**通过，部署客户端 **8 项 Python 测试**通过。
真实服的预期异常会在日志中留下健康检查或状态写入错误栈；这两种情况均验证了旧版本继续运行。

## 性能

在同一 Paper 进程中比较直接 `RegisteredListener` 与受管理事件回调。两者都执行同一 `AtomicLong` 计数，
交替执行顺序，预热后每轮 100,000 次、120 轮，重复三组。

| 回调方式 | 三组平均耗时 |
| --- | --- |
| 直接 Bukkit 注册回调 | 6.94–7.23 ns/次 |
| 受管理模块回调 | 12.67–13.23 ns/次 |
| 额外开销 | **5.70–6.00 ns/次** |

这是轻量同步事件的分发微基准，包含类型检查、代际检查及线程检查；没有反映业务自身耗时或网络延迟。
例如按此微基准，10,000 次回调额外约 0.06 ms；不据此推算玩家容量。

空服使用 ABBA 顺序采样：真实前缀/公告模块 → 空业务模块 → 空业务模块 → 真实模块。
每个窗口 30 秒，切换与主动 GC 放在采样窗口之外；常驻宿主、其他 QPlugin 功能及世界保持相同。
MSPT 来自 Paper `ServerTickEndEvent.getTickDuration()`，每窗约 600 个 tick。

| 业务模块 | 平均 MSPT | p99 MSPT | 进程 CPU（100% = 一核） |
| --- | --- | --- | --- |
| 前缀 + 每日公告，第一窗 | 1.977 ms | 4.723 ms | 12.10% |
| 空业务，第二窗 | 1.682 ms | 3.226 ms | 8.14% |
| 空业务，第三窗 | 1.653 ms | 3.162 ms | 8.03% |
| 前缀 + 每日公告，第四窗 | 1.619 ms | 3.057 ms | 9.28% |

业务窗口间的波动比模块与空业务之间的 MSPT 差异更大，不能把表中全部 CPU 差额归给模块层。
没有观测到持续的 tick 性能回退，但四个空服窗口不足以确定多人服的损耗上限。

宿主 `QPlugin-HotUpdate` 后台线程消耗约 **0.18–0.20% 单核 CPU**，包含每秒心跳和请求轮询；
这个线程在两个对照条件下都存在，因此上述 A/B 不测量宿主本身的全部开销。
真实模块比空业务多两个受管理文件轮询任务，采样时进程线程数均为 65。
后期窗口 GC 后堆占用为约 273–274 MiB；初始窗口约 283 MiB。短样本与后台分配不足以精确估算模块驻留内存，
旧加载器回收和稳定注册数提供了本次测试未持续积累版本资源的证据。
已确认更新的端到端等待通常约 0.9–1.1 秒，主要受每秒邮箱轮询影响，不等同于主线程停顿时长。
另一个连续切换 10 次的观察窗口共 204 个 tick，最大 tick 为 8.217 ms、p99 为 7.232 ms；
它包含服务器全部 tick 工作，是本地观察值，不能单独归因于切换代码。

## 复现

测试工具独立于生产包。`packageHotRelease` 只包含三个完整插件、两个业务模块及 manifest，不包含 QHotProbe 或故障模块。

```sh
./gradlew build packageHotRelease :test-support:paper-probe:jar -PreleaseId=<unique-local-id>
```

本地现有测试目录已经包含世界、服务端 JAR、完整插件、QHotProbe 和配置。
`validation.json` 含 `ports: {game, rcon, api}`、`jar` 和随机 `rcon_password`，仅本机用户可读，禁止提交。
`config.yml` 的 `API_ENDPOINT` 指向该 api 端口，测试 Secret 为占位值，QMod 无密码登录关闭。
服务器端口配置必须与该文件一致，保留 `server-ip=127.0.0.1`。
空目录首次测试还需提供 `newyear.txt`；现有雪团活动代码在启用阶段直接读取这个文件。

两个终端分别启动本机 API 桩及 Paper（将 JDK 路径按本机修改）：

```sh
python3 scripts/paper_validation.py --server-dir build/paper-validation --serve-api
```

```sh
cd build/paper-validation
/path/to/jdk25/bin/java -Xms1G -Xmx2G -jar paper-26.2-134.jar --nogui
```

等待完整插件 ready，再从仓库目录运行：

```sh
python3 scripts/paper_fixtures.py --server-dir build/paper-validation \
  --java-home /path/to/jdk25 --label paper-recheck-002
python3 scripts/paper_exercise.py --server-dir build/paper-validation \
  --fixtures build/paper-validation/fixtures/paper-recheck-002/fixtures.json
python3 scripts/paper_performance.py --server-dir build/paper-validation \
  --fixtures build/paper-validation/fixtures/paper-recheck-002/fixtures.json --seconds 30
python3 scripts/paper_validation.py --server-dir build/paper-validation stop
```

故障包仅用于此隔离目录。`paper_performance.py` 退出前恢复真实内置业务模块；
测试完成后应停止 Paper 和 API 桩。需要重测宿主代码改动时，先正常停服、替换完整 JAR 再启动，并生成新的故障包。

原始证据保存在测试目录的 `integration.json`、`performance.json`、`restart-validation.json`、`switch-ticks.json` 和 `logs/` 中。

## 2026-10-30：双后端 Orin 接入回归

Orin 的部署目标配置为生存服 `/opt/server/qo2`、创造服 `/opt/server/qo_creative`，各有独立 Pipeline。
检查实际插件描述文件后发现，创造服的 Bukkit 内部名称仍为 `QuantumPlugin`，而发布清单按
`QuantumPlugin-Creative` 区分构建。因此现在单独传入逻辑宿主标识，保留原 `plugins/QuantumPlugin` 数据目录
和 `quantumplugin` 玩家 PDC 命名空间；不能把创造服的数据目录按 JAR 文件名推导。

在 `build/paper-creative-validation-20261030/` 启动另一台隔离 Paper 26.2 build 134，
加载完整创造服插件、连接本机 QAPI 桩，实际验证：

- 状态宿主标识为 `QuantumPlugin-Creative`，数据目录仍为 `plugins/QuantumPlugin/hot`。
- 顺序应用 `local-orin-targets-20261030-01`、`local-orin-targets-20261030-02` 均返回 `ACTIVE`。
- 两版内置模块不同，三个完整宿主的代码指纹保持一致。
- 传入预期宿主 `QuantumPlugin` 时直接拒绝，active 不变。
- `/qhot rollback` 正确恢复第一版。

原始结果为该目录的 `creative-validation.json` 和日志；测试服及 API 桩均已停止。
这次 Gradle 构建与 **66 项 JVM 测试**、部署客户端 **9 项 Python 测试**通过。

另使用现有 `Orin-all.jar` 的真实执行器验证两个目标 Pipeline。
手动触发和 push 在现有 Orin 中不分配 jobId，因此 action 在这种情况下只使用持久存储中的产物；
cluster 触发继续登记可下载产物。
实际执行器检查覆盖手动构建、两个后端独立部署、错误路径拒绝、输入不能覆盖目标、未知 Pipeline 拒绝及 cluster 产物登记。
构建和末端部署使用桩，不代表线上 Orin 已安装这些配置。
复现程序位于 `test-support/orin/`，运行记录为 `build/orin-targets-validation/runner.log`。

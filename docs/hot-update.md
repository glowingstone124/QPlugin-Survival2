# QPlugin 业务模块热更新

当前有五个模块；一个发布以事务方式切换所有模块：

| 模块 | 业务 | 后端 |
| --- | --- | --- |
| `sponsor-prefix` | 赞助者、Master、ADMIN 前缀 | 生存 / 创造 |
| `daily-login` | 每日首次登录公告 | 生存 / 创造 |
| `combat-feedback` | 弓箭命中和伤害提示、`damageindicator` 指令及补全 | 生存 / 创造 |
| `text-displays` | `summontext` 文字展示指令与朝向计算 | 生存 |
| `seasonal-events` | 烟花、团子、爆竹及对应命令、事件与粒子任务 | 生存 |

发布包同时携带五个模块，各模块按宿主标识决定是否注册业务；Chambers 不启用上述业务。
主插件作为常驻宿主，保留登录限制、QMod 游戏票据和 NMS 功能。
模块通过独立 ClassLoader 加载，更新过程中无需卸载 JavaPlugin 或重启世界。

这是项目自己控制的业务模块协议。当前 API 2 支持受管理的同步 Bukkit 事件、命令/补全、主线程定时回调和文本文件后台读取。
完整插件 JAR、宿主 API、公共依赖或宿主中的 NMS 实现发生变化时，部署程序会返回 `RESTART_REQUIRED`。

## 构建和首次安装

使用 JDK 25 和项目 Gradle Wrapper：

```sh
./gradlew --no-daemon build packageHotRelease -PreleaseId=<commit>
kotlin scripts/deploy-hot.main.kts --self-test
```

`build/releases/<commit>/` 和同名 ZIP 包含：

- `manifest.json`：发布 ID、API 版本、模块版本、文件长度、SHA-256 和宿主代码指纹。
- `sponsor-prefix.jar`：可在线替换的业务模块，不包含宿主 API 副本。
- `daily-login.jar`：每日登录公告，保留玩家已有的每日发送标记。
- `combat-feedback.jar`、`text-displays.jar`、`seasonal-events.jar`：本轮拆出的业务模块。
- `QuantumPlugin.jar` / `QuantumPlugin-Creative.jar` / `QuantumPlugin-Chambers.jar`：各服完整插件，供首次安装及需要重启的升级使用。

同一发布 ID 的目录和 ZIP 不允许覆盖为不同内容。工作区有未提交修改时，应使用新的本地测试 ID，
例如 `local-test-001`，不要将它冒充为已提交的 Git SHA。

第一次需停止对应后端，备份并替换该后端的原插件 JAR（不要同时留下两个版本），然后启动。
代理可继续运行；如需保留玩家连接，可先将玩家转到大厅。首次安装必须重启才能建立常驻宿主。

后续目录相对于插件自己的数据目录，例如生存服：

```text
plugins/QuantumPlugin/hot/
  releases/<releaseId>/manifest.json
  releases/<releaseId>/sponsor-prefix.jar
  releases/<releaseId>/daily-login.jar
  releases/<releaseId>/combat-feedback.jar
  releases/<releaseId>/text-displays.jar
  releases/<releaseId>/seasonal-events.jar
  data/sponsor-prefix/
  data/daily-login/
  state.json
  status.json
  requests/<requestUuid>.json
  results/<requestUuid>.json
```

首次启动会安装与当前宿主指纹绑定的内置模块。重启会恢复上次成功的发布及 previous 指针；
若该发布已缺失或宿主已变更，则回到当前插件自带的兼容版本并记录日志。
宿主启动失败时保留核心插件登录功能，前缀和每日公告使用旧实现（前缀定时器在插件停用时清理）；
此时需修复问题并重启后才能热更新。Chambers 与原有行为一致，默认不注册名称前缀或每日公告处理器。

每日公告继续读取服务端工作目录的 `daily-login-ad.json`，字段为 `enabled`、`timezone`、`template`、`url`。
文件缺失时使用内置默认值；可从 `common/src/main/resources/daily-login-ad.json` 复制示例。
配置在后台每 100 tick（正常 TPS 下约 5 秒）读取，最多 64 KiB，业务回调在主线程更新。
非法 JSON 或时区保留上一次有效配置，删除或清空文件恢复默认值。
生存服和创造服原 Bukkit 插件名都为 `QuantumPlugin`，玩家的 `quantumplugin:daily_login_ad_last_shown` PDC 标记保持原命名；
模块替换或回滚不会清除标记，因此同一天不会重复发送，也不会撤销已发送的公告。

伤害提示沿用玩家的 `di` scoreboard tag。文字展示实体保留在世界中，替换命令实现不会删除已创建的文字。
活动模块保持原 2026 新年活动日期和 `quantumplugin` PDC key；不会因拆分重新开放已结束的活动。
`newyear.txt` 每 100 tick 后台刷新，最多 64 KiB，缺失或空文件使用“新年快乐！”作为祝福语。
团子的粒子尾迹改用单个宿主管理的逐 tick 任务，落地/死亡时移出队列；热更关闭旧队列。
热更不转移短暂的粒子尾迹和待提前引爆队列；已有烟花继续按实体自身 fuse 运行，爆竹/烟花的保护标记由新模块识别。

从 API 1 升级到 API 2 时，需要一次完整宿主更新与后端重启。Orin 的 `qplugin-ci` 会根据宿主指纹自动选择该路径；
首次升级前需通过 `ci/orin/install-actions.sh` 同步新版 KTS action。新版客户端同时识别 API 1、2，
两者不一致时要求安装宿主，不向旧 API 宿主提交新模块请求。后续仅改上述模块实现可以继续在线更新。

## 在线应用与回滚

将完整发布目录放入上述 `releases/<releaseId>/` 后，可用：

```text
/qhot status
/qhot apply <releaseId>
/qhot rollback
```

权限 `quantum.hotupdate` 默认 op。也可以在服务器控制台执行；游戏内仍需先满足原有登录限制。
回滚会重新加载 previous 对应的不可变模块包，成功后 active / previous 互换。

部署脚本可以直接接收 Gradle 生成的 ZIP：

```sh
kotlin scripts/deploy-hot.main.kts \
  --release build/releases/<commit>.zip \
  --hot-dir /srv/minecraft/plugins/QuantumPlugin/hot \
  --host-name QuantumPlugin \
  --timeout 60
```

脚本验证归档、宿主指纹和校验和，再将发布目录原子移入 `releases/`，提交本机文件请求并等待插件回执。
只在插件完成主线程切换、健康检查、状态持久化和状态文件刷新后返回成功。没有回执会超时并打印请求 ID；
此时先查询对应 `results/<requestId>.json` 和 `/qhot status`，不要盲目重试或手工改 active 指针。
请求绑定服务器进程 instanceId、原 active 版本和创建时间，旧进程遗留请求不能在新进程中直接执行。
每次只允许一个发布进入切换过程；成功回执丢失时相同不可变版本可返回 `ALREADY_ACTIVE`。

退出码：`0` 表示确认成功；`20` 表示需要后端重启（或新模块已生效但旧资源清理失败）；`1` 表示其他失败。
脚本不会自行停服、覆盖运行中的完整插件或修改 Velocity 路由。

已配置的两个后端分别是生存服 `/opt/server/qo2`、创造服 `/opt/server/qo_creative`。
两边的数据目录都是各自的 `plugins/QuantumPlugin/hot`；创造服的发布宿主标识单独设为
`QuantumPlugin-Creative`，以选择发布清单中的正确指纹，不修改 Bukkit 插件名称或已有数据目录。
Orin 的两个独立 Pipeline 会传入预期宿主标识，路径指向另一种后端时在暂存发布前直接拒绝。

## 切换边界

1. 后台校验发布清单、宿主代码指纹、模块文件摘要及模块描述文件；加载新的独立 ClassLoader。
2. 调用 `prepare(context)`，只允许读取配置、构造状态和登记回调声明，尚不向 Bukkit 注册。
3. 在服务器主线程暂停旧模块回调，取消其任务并解除其监听器。
4. 注册新模块回调，执行 `healthCheck()`，然后原子写入 `state.json`。这些步骤在下一游戏 tick 前完成。
5. 成功后关闭旧模块和旧加载器；失败时撤销新模块、恢复旧模块注册和旧 active 状态。

回调包装器带有每次激活的 epoch。已经排队的旧文件读取结果和旧定时回调，即使在恢复旧模块后抵达，
也不能调用旧 epoch 的业务代码。文件 I/O 是宿主实现，模块业务回调均在主线程执行，切换时没有并行的模块回调需要强制中断。
回调清理失败会阻止后续热更新并提示重启，避免持续积累旧资源。

回滚保护模块入口和注册状态；它不能自动撤销已经写入世界、数据库或外部 API 的变化。
当前没有通用的跨版本业务状态迁移协议。登录会话、未完成的游戏票据验证和 guest 限制继续由常驻层持有。

## 开发模块

模块项目仅 `compileOnly` 依赖 `:hot-api` 和宿主已经提供的 Bukkit / Adventure API。
Gradle 的 `hotModules` 列表同时生成内置模块索引，宿主从索引安装默认发布；新增模块不需要再修改 Java 加载器中的模块名称列表。
模块 JAR 提供 `META-INF/qplugin-module.properties`：

```properties
id=sponsor-prefix
version=<unique-version>
apiVersion=2
entrypoint=vip.qoriginal.modules.sponsors.SponsorPrefixModule
```

实现 `HotModule.prepare(ModuleContext)`，通过 `context.listen`、`command`、`repeatSync`、`watchTextFile` 登记回调。
`context.command(name, executor, completer)` 只能绑定当前宿主 `plugin.yml` 已声明的命令，权限和别名仍由 Bukkit 检查；
暂停时还原原始处理器，恢复时重新绑定，新旧命令与补全回调同样受 epoch 保护。重复的命令归属会使激活失败并回滚。
在线更新时 `prepare` 在后台准备线程执行（首次启动可在 onEnable 中执行），不得操作世界或玩家；
`healthCheck` 和已激活的回调在主线程执行。异步 Bukkit 事件需先增加专门的宿主适配器，不能直接交给当前 `listen` API。
`close` 对已激活模块在主线程调用，准备失败时可能在准备线程调用；应可安全清理部分初始化状态。

禁止业务模块自行创建 Timer、线程、协程、执行器、直接注册 Bukkit 回调，或把业务对象存入宿主静态字段。
新增能力应先扩展宿主受管理的 API；修改这个 API 或宿主依赖时需要重新安装完整插件并重启。
模块是受信任的服务器代码，ClassLoader 用于版本隔离，不是权限沙箱。
发布目录只应允许受信任的部署账号写入；SHA-256 用于完整性校验，发布者身份依赖 Orin 的认证和部署账号权限。

宿主指纹按排序后的 JAR 条目内容计算，忽略 ZIP 时间戳/顺序和 `hot-modules/` 下的内置模块。
因此修改业务实现或模块版本可以保持宿主兼容；修改宿主类、公共依赖或插件声明则要求重启。

## 验证范围

自动测试使用真实编译的两版模块 JAR，覆盖代码替换、旧回调失效、健康检查回滚、磁盘写入失败回滚、
旧资源清理失败、宿主指纹、不可变发布、摘要/软链接/路径检查、重启恢复和文件请求回执。
名称前缀模块测试保持赞助者和角色行为；每日公告测试覆盖跨版本标记、配置刷新、时区、停用和可点击链接；
部署脚本测试使用隔离本机目录和模拟宿主回执。
另已在本地完整 Paper 生存服上验证代码替换、多模块回滚、资源回收及性能，详见 [验证记录](paper-validation.md)。
本地测试使用真实 CraftPlayer 和 Bukkit 事件，但没有网络客户端；生产联机和多玩家负载仍需独立验证。

设计边界参照 [Paper Lifecycle API](https://docs.papermc.io/paper/dev/lifecycle/) 和
[Paper 插件更新指引](https://docs.papermc.io/paper/reference/commands/#reload-alias-spigot-reload)。

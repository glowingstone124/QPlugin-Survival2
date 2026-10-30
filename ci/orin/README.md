# 接入 Orin

QPlugin 的构建、测试、产物存储和部署均由现有 Orin 执行。
这些 action 使用现有 Orin 的 `executeSh`、`publishArtifact`、`remote().trigger()`、`await()` 和 `downloadArtifact()`。
无需修改 Orin 核心，也没有新增公网管理端口。Bukkit 端通过插件数据目录中的文件请求接收部署。

现有节点为 `10.10.0.1`（SSH 端口 66），Orin 工作目录为 `/opt/server/orin`，由 MCSManager 管理。
如果部署账号需要免密检查和重启 Orin，由管理员执行一次：

```sh
sudo bash /opt/server/orin/setup-orin-sudo.sh glowingstone
```

脚本来源为 [setup-orin-sudo.sh](setup-orin-sudo.sh)，安装 root 所有的固定目标管理器和两条命令规则：
`sudo -n /usr/local/sbin/qplugin-orin-admin status`、`sudo -n /usr/local/sbin/qplugin-orin-admin restart`。
管理器只匹配该工作目录中的 `Orin-all.jar` Java 进程，有子进程时拒绝重启；空闲时发送 SIGTERM，
由 MCSManager 按已配置的守护策略重新启动。执行脚本本身不会重启服务。

## 后端目标

| Pipeline | 服务端目录 | 热更新目录 | 发布宿主标识 |
| --- | --- | --- | --- |
| `qplugin-deploy-survival` | `/opt/server/qo2` | `/opt/server/qo2/plugins/QuantumPlugin/hot` | `QuantumPlugin` |
| `qplugin-deploy-creative` | `/opt/server/qo_creative` | `/opt/server/qo_creative/plugins/QuantumPlugin/hot` | `QuantumPlugin-Creative` |

两种完整插件在 Bukkit 中的内部名称都保留为 `QuantumPlugin`，所以创造服也使用 `plugins/QuantumPlugin`，
不能按 JAR 文件名推导成 `plugins/QuantumPlugin-Creative`。
每日公告的既有 PDC 命名空间两边都保留为 `quantumplugin`。

路径集中在 [targets.properties](actions/qplugin-deploy/targets.properties)，由 Pipeline 名称选择。
不再使用进程级 `QPLUGIN_HOT_DIR`，避免同一个 Orin 实例的两个任务落到同一后端。
手动触发请求只接收构建 revision，不能通过输入覆盖目标路径或宿主类型。
部署节点必须实际能访问这些目录；若分属不同服务器，在相应 Orin 节点安装对应 Pipeline。

## 安装 action

从受信任、已审核的 QPlugin 工作区复制到 Orin 目录（以下路径按部署环境修改）：

```sh
cp -R ci/orin/actions/qplugin-build /opt/orin/actions/
cp -R ci/orin/actions/qplugin-deploy /opt/orin/actions/
cp ci/orin/actions/qplugin-build/build.sh /opt/orin/actions/qplugin-deploy/
cp scripts/deploy-hot.py /opt/orin/actions/qplugin-deploy/
```

将 [pipelines.example.toml](pipelines.example.toml) 的 Pipeline 段合并进现有 `config.toml`，
使用各自的真实 manualToken；Runner 超时建议至少 2400 秒。不要用此片段覆盖完整 Orin 配置。
两个目标 Pipeline 可分别安装或一起安装，不会覆盖其他项目的 Pipeline。
这些目录首次安装并启动新宿主后，应分别存在 `hot/status.json`，部署 action 在构建前检查它是否存在。

Orin 进程环境变量：

| 变量 | 用途 |
| --- | --- |
| `QPLUGIN_REPOSITORY` | 构建节点信任的 Git 仓库地址；本项目为 `https://github.com/glowingstone124/QPlugin-Survival2.git` |
| `QPLUGIN_RELEASE_STORE` | 绝对路径，不可变产物长期存储目录，例如 `/opt/orin/releases/qplugin` |
| `QPLUGIN_DEFAULT_REVISION` | 默认构建分支，缺省 `main`；分支试验可设为 `hot-update` |
| `QPLUGIN_DOWNLOADS` | 部署节点的绝对下载目录，例如 `/opt/orin/downloads/qplugin` |
| `QPLUGIN_BUILD_NODE` | 可选，现有 Orin Remote 节点名；省略时就在当前实例构建并部署 |
| `QPLUGIN_HTTP_PROXY` | 可选，QPlugin 构建子进程专用 HTTP 代理；同时配置 Git 与 Java/Gradle 下载 |

构建节点安装 JDK 25、Git、Python 3，并能访问构建依赖。部署节点安装 Python 3，运行账号需能写入该后端的热更新目录。
可将 [orin.env.example](orin.env.example) 中的值加入当前 Orin 服务环境；示例使用 `hot-update` 分支。
也可直接将该文件复制到 Orin 工作目录的 `qplugin.env`，使用不带引号的 `KEY=value` 格式；
action 会读取这个文件，已设置的进程环境变量优先。MCSManager 启动参数无需为此修改。
仓库中的新实现需先提交推送，远端构建不会读取本地未提交修改。
如果配置 `QPLUGIN_BUILD_NODE`，它必须指向另一个 Orin 实例；单工作队列等待自身的远程任务会阻塞。
单实例模式在同一个 action 内依次构建和部署，不发起对自身的 remote trigger。

## 执行过程

`qplugin-build` 在独立临时 checkout 中抓取所选分支/提交，运行 Wrapper 构建与测试，再生成按实际 Git SHA 命名的 ZIP。
产物先复制到 `QPLUGIN_RELEASE_STORE/<sha>.zip`，通过原子且不覆盖的创建保留历史内容，再交给 `publishArtifact`。
临时构建目录清理后，Orin 的历史 job 下载仍指向稳定文件。
现有 Orin 仅为 cluster 触发分配 jobId，因此 action 只在存在 jobId 时登记下载产物；
manual/github.push 触发仍将 ZIP 保存在同一个持久目录，并记录实际 SHA 和产物路径。

`qplugin-deploy-survival` / `qplugin-deploy-creative` 共用 `qplugin-deploy` action，
可以远程触发构建节点并下载产物，也可以在当前节点完成构建；随后执行部署客户端。
客户端同时检查目标目录和预期宿主标识，传错后端在创建发布或请求之前失败。
只有 Bukkit 端确认 active 版本、健康检查及状态持久化成功，action 才成功。
宿主代码不兼容或清理失败时 action 返回失败，提示安排后端重启；不自动停服或操作 Velocity。

示例 Pipeline 默认只开放认证的手动/集群触发，方便先在测试服验证。
确认后可在部署 Pipeline 增加 `github.push` 触发，并设置正确的仓库名；原有 GitHub HMAC 校验继续由 Orin 执行。
当前 Orin KTS 上下文没有 push 的 head SHA/分支字段，自动触发时构建抓取到的默认分支，并以实际抓取的 SHA 标识。
需要精确部署某个提交时，通过认证手动触发的 `inputs.revision` 指定完整 SHA。

例如分别向 `/trigger/qplugin-deploy-survival` 或 `/trigger/qplugin-deploy-creative` 提交：

```json
{"token":"该 Pipeline 自己的 manualToken","inputs":{"revision":"完整提交 SHA"}}
```

两边发布同一版时传入相同 SHA；每个后端独立确认或回滚，没有跨服务器的事务回滚。
如果已经通过 `qplugin-build` 构建完成，可传入 `"release_commit":"完整提交 SHA"`，
直接使用持久目录中该提交的不可变 ZIP；部署前会验证包内 releaseId，两个后端无需重复编译。
首次安装或需要重启的宿主升级，可以显式提交 `"install_host":"true"`：

```json
{"token":"该 Pipeline 自己的 manualToken","inputs":{"revision":"完整提交 SHA","install_host":"true"}}
```

这个选项仅允许 manual/cluster 触发。安装器按绑定目录定位唯一 Docker 容器，备份旧 JAR 和
`plugins/QuantumPlugin` 到后端的 `backups/qplugin/`，再将新 JAR 暂存到 Paper 的更新目录。
它向该容器唯一的 Java 进程发送 SIGTERM，等待正常结束，再用现有容器配置恢复服务。
超过 150 秒仍未结束时会失败并保留暂存包，不发送 SIGKILL。
宿主就绪后继续执行热更新客户端，确认发布清单、指纹和模块回执。
其他插件已有待应用更新时会拒绝重启，避免顺带应用未经检查的插件版本。
首次安装时，生存服使用发布包的 `QuantumPlugin.jar`，创造服使用 `QuantumPlugin-Creative.jar`。
停止对应后端、备份旧 JAR 与插件数据目录，再替换完整 JAR 并启动；后续兼容模块可以在线更新。

首次安装宿主仍需一次后端重启，详见 [热更新部署文档](../../docs/hot-update.md)。

本地已验证真实 Orin 执行器的两个目标路由和无 jobId 触发，复现方法见
[执行器集成检查](../../test-support/orin/README.md)。创造服的真实 Paper 应用/回滚结果见
[本地验证记录](../../docs/paper-validation.md)。这些检查没有修改线上 Orin。

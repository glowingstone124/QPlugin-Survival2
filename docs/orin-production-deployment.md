# QPlugin / Orin 正式服部署手册

本文描述当前纯 KTS 部署链路。生产 action 不依赖 Python：Orin 直接解释 `main.kts`，构建 checkout
仅使用 Bash、Git、JDK 25 和 Gradle Wrapper。

## 当前拓扑

| 项目 | 生存服 | 创造服 |
| --- | --- | --- |
| 服务端目录 | `/opt/server/qo2` | `/opt/server/qo_creative` |
| 游戏端口 | `25565` | `26667` |
| 热更新目录 | `plugins/QuantumPlugin/hot` | `plugins/QuantumPlugin/hot` |
| 发布宿主标识 | `QuantumPlugin` | `QuantumPlugin-Creative` |
| Pipeline | `qplugin-deploy-survival` | `qplugin-deploy-creative` |

Orin 位于 `10.10.0.1:66` 的 `/opt/server/orin`，由 MCSManager 管理。
`main` 推送由 GitHub webhook 触发 `qplugin-ci`：构建一次，先创造服、后生存服。

## 安装或升级 action

在审核过的 QPlugin checkout 中执行：

```bash
cd /opt/server/orin/source/qplugin
git fetch origin main
git checkout --detach origin/main
bash ci/orin/install-actions.sh /opt/server/orin
```

安装器会：

- 备份当前四个 QPlugin action；
- 安装 `qplugin-build`、`qplugin-deploy`、`qplugin-status`、`qplugin-ci`；
- 将同一份 `qplugin-deploy/main.kts` 安装为 `qplugin-ci/main.kts`，防止自动与手动路径漂移；
- 同步 `setup-orin-sudo.sh` 与固定目标的 `qplugin-orin-admin.sh`，但不自动修改 sudoers；
- 仅复制受控 `build.sh`，不会安装 Python 解释器或 Python 脚本。

action 文件每次任务都会重新读取，更新 action 本身不要求重启 Orin。修改 `config.toml`、Webhook 或
Runner 设置后，使用 MCSManager 正常重启 Orin。

## Orin 配置

`/opt/server/orin/qplugin.env` 示例：

```properties
QPLUGIN_REPOSITORY=https://github.com/glowingstone124/QPlugin-Survival2.git
QPLUGIN_DEFAULT_REVISION=main
QPLUGIN_JAVA_HOME=/usr/lib/jvm/jdk-25.0.3+9
QPLUGIN_RELEASE_STORE=/opt/server/orin/releases/qplugin
QPLUGIN_DOWNLOADS=/opt/server/orin/downloads/qplugin
QPLUGIN_HTTP_PROXY=http://127.0.0.1:7890
```

要求：

- `QPLUGIN_JAVA_HOME/bin/java` 和 `bin/javac` 均存在；
- release store 和 downloads 为绝对路径；
- 构建代理只允许无凭据、无 path/query 的 `http`/`https` URL；
- 单节点不要设置 `QPLUGIN_BUILD_NODE`，否则单工作队列会等待自己；
- `qplugin.env`、`qplugin-control.json` 权限保持 `600`，不得提交到 Git。

目标路径位于 `actions/qplugin-deploy/targets.properties`：

```properties
qplugin-deploy-survival.hotDir=/opt/server/qo2/plugins/QuantumPlugin/hot
qplugin-deploy-creative.hotDir=/opt/server/qo_creative/plugins/QuantumPlugin/hot
```

状态 action 的只读服务端路径位于 `actions/qplugin-status/targets.properties`。

## 日常自动更新

1. 在本地运行构建和 KTS 部署自检：

   ```bash
   ./gradlew --no-daemon build packageHotRelease -PreleaseId=local-validation
   kotlin scripts/deploy-hot.main.kts --self-test
   kotlin scripts/orin-trigger.main.kts --self-test
   ```

2. 精确提交所需文件并推送 `main`。
3. GitHub webhook 向 `/webhook/github` 发送 push；Orin 校验 HMAC、完整仓库名、
   `refs/heads/main`、删除标记和完整 `after` SHA。
4. `qplugin-ci` 在隔离 checkout 构建，并把不可变 ZIP 保存到
   `/opt/server/orin/releases/qplugin/<commit>.zip`。
5. 创造服和生存服分别确认 `activeRelease`、宿主指纹、健康状态和请求回执后，流水线才写入
   `qplugin-ci-last-success.json`。

成功记录应满足：

```json
{
  "commit": "完整提交 SHA",
  "status": "SUCCESS",
  "targets": {
    "creative": "hot-updated",
    "survival": "hot-updated"
  }
}
```

宿主/API/公共依赖指纹改变时，目标会进入正常宿主升级：备份旧 JAR 和数据目录、把新 JAR 原子暂存到
Paper update 目录、向经过容器/PID/start-token 三重确认的 Java 进程发送 SIGTERM，并等待 MCSManager
恢复服务。超过 150 秒仍未退出时任务失败且不发送 SIGKILL。

## 手动触发与恢复

管理员工作站需有 Kotlin 2.3+。触发器只从私有控制文件读取令牌，不把令牌放入参数或输出。
可以通过安全通道把控制文件临时放到管理员工作站，并用 SSH 端口转发访问 Orin：

```bash
ssh -N -L 19913:127.0.0.1:9913 -p 66 glowingstone@10.10.0.1
```

临时控制文件中的 URL 设为 `http://127.0.0.1:19913`，然后：

```bash
kotlin scripts/orin-trigger.main.kts --config /secure/qplugin-control.json \
  --pipeline qplugin-status

kotlin scripts/orin-trigger.main.kts --config /secure/qplugin-control.json \
  --pipeline qplugin-build --revision <完整提交 SHA>

kotlin scripts/orin-trigger.main.kts --config /secure/qplugin-control.json \
  --pipeline qplugin-deploy-creative --release-commit <完整提交 SHA>

kotlin scripts/orin-trigger.main.kts --config /secure/qplugin-control.json \
  --pipeline qplugin-deploy-survival --release-commit <完整提交 SHA>
```

需要完整宿主升级时，只在维护窗口显式增加 `--install-host`。该选项仅允许 manual/cluster 触发。
客户端等待超时会打印 `UNCONFIRMED` 和原 jobId；此时查询同一 job，不能直接重提。

## 验证

```bash
ssh -p 66 glowingstone@10.10.0.1
cat /opt/server/orin/qplugin-ci-last-success.json
ss -ltn | awk '$4 ~ /:(25565|26667)$/ {print $4}'
```

后端还应满足：

- `status.json.running=true`、`phase=ready`、`restartRequired=false`；
- `activeRelease` 等于发布 SHA；
- `hostName` 与表格一致；
- 五个业务模块都在 `modules` 中；
- `plugins/update` 不残留本次 QPlugin JAR；
- `/login`、QMod 验证和业务命令正常。

Orin action 的隔离验证使用真实 `PipelineService` 与 KTS 引擎：

```bash
# 具体编译命令见 test-support/orin/README.md
# OrinTargetsSmokeKt: build/manual/cluster/status/后端路由/回执
# OrinCiSmokeKt: GitHub SHA 过滤/创造服先行/失败阻断生存服
```

## 回滚

兼容模块回滚在对应游戏服控制台执行：

```text
qhot status
qhot rollback
qhot status
```

完整宿主回滚使用 `/opt/server/qo2/backups/qplugin/` 或
`/opt/server/qo_creative/backups/qplugin/` 中对应升级的备份：正常停止后端、恢复旧 JAR 与
`plugins/QuantumPlugin`、移走 update 目录中的候选，再由 MCSManager 启动。不要覆盖世界目录。

## 安全边界

- ZIP 限制条目数和解压总量，拒绝路径穿越、重复条目、目录、加密条目和 Unix symlink；
- manifest、文件大小和 SHA-256 在暂存前后都验证；
- release ID 不可变，同 ID 不同内容直接失败；
- 请求绑定 instanceId、expectedActive 和唯一 requestId；
- 只有匹配的结果回执才算成功；
- 完整宿主升级固定绑定两个服务端目录，不接受输入路径覆盖；
- 不发送 SIGKILL，不修改 Docker/MCSManager 配置，不修改 Velocity 路由。

## 清理

可以删除可再生的 `qplugin-build-last.log`、`qplugin-ci-last.log` 和 downloads 中已确认不用的临时下载。
不要把下列内容当作临时文件：

- `config.toml`、`qplugin.env`、`qplugin-control.json`；
- `qplugin-ci-last-success.json`；
- `releases/qplugin/` 不可变发布；
- `backups/` 和两个后端的 `backups/qplugin/` 回滚材料。

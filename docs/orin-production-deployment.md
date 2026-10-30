# QPlugin 正式服部署手册

> 日常更新改用 [main 推送自动部署](orin-auto-deployment.md)。以下保留人工部署与恢复步骤，
> “当前交接状态”是启用自动部署前的历史记录，不代表服务器当前状态。

适用节点：`10.10.0.1`，SSH 端口 `66`，用户 `glowingstone`。记录日期：2026-10-30。
部署顺序：**推送修复后的源码 → 更新 Orin action 与构建 JDK → 构建一次 → 安装创造服 → 验证 → 安装生存服**。

## 1. 当前交接状态

已经完成：

- `/opt/server/orin` 中已安装 QPlugin 的 build、deploy、status action，以及四条独立 Pipeline。
- 原有 `qapi-github-build` 配置保留；Runner 超时从 900 秒调整为 2400 秒。
- Orin 已由你在 MCSManager 中手动启动，最近只读检查返回 HTTP 200。
- QPlugin 构建代理已配置为 `http://127.0.0.1:7890`，实际验证可以读取 GitHub 的 `hot-update` 分支。
- `075cf01` 的 Wrapper 修复已经推送，远端已经成功读取 Wrapper 并进入 Gradle 配置阶段。
- 只读核对确认服务器 `/usr/lib/jvm/jdk-25.0.3+9` 包含完整编译器，版本为 `javac 25.0.3`。

尚未完成：

- 两个游戏服仍使用旧插件，尚未安装热更新宿主；本次没有替换插件、没有重启游戏服。
- 远端完整构建尚未成功：最新失败点为 `No Java compiler found`，Orin 进程继承的是 Java 22 JRE。
- 本次新增构建专用 JDK 选择，需推送并同步 build/deploy action，然后在 `qplugin.env` 设置 `QPLUGIN_JAVA_HOME` 后重试。
- `40960c3`、`6254385` 缺少受 Git 跟踪的 Wrapper JAR，仍不能作为本次部署版本。
- GitHub push 自动发布尚未启用；当前 Pipeline 接受 manual/cluster 触发。

已有配置备份：

```text
/opt/server/orin/backups/qplugin-20261030-1793342855/config.toml
/opt/server/orin/backups/qplugin-actions-1793343361/
/opt/server/orin/backups/qplugin-proxy-1793344174/
```

最后一次人数检查为生存服 3 人、创造服 0 人；这是交接时的快照，安装前应重新检查。

## 2. 目标对应关系

| 项目 | 生存服 | 创造服 |
| --- | --- | --- |
| 服务端目录 | `/opt/server/qo2` | `/opt/server/qo_creative` |
| Docker 容器名 | `minecraft_server` | `minecraft_server_creative` |
| 对外游戏端口 | `25565` | `26667` |
| 当前插件文件名 | `QuantumPlugin-1.14.5.5.1.jar` | `QuantumPlugin-Creative-1.14.5.5.1.jar` |
| 发布包内完整插件 | `QuantumPlugin.jar` | `QuantumPlugin-Creative.jar` |
| 发布宿主标识 | `QuantumPlugin` | `QuantumPlugin-Creative` |
| 数据目录 | `plugins/QuantumPlugin` | `plugins/QuantumPlugin` |
| 热更新目录 | `plugins/QuantumPlugin/hot` | `plugins/QuantumPlugin/hot` |
| 部署 Pipeline | `qplugin-deploy-survival` | `qplugin-deploy-creative` |

创造服的 Bukkit 内部名称也保留为 `QuantumPlugin`，因此数据目录和已有玩家 PDC 命名空间不改名。
两边都使用 `quantumplugin:daily_login_ad_last_shown`，更新不会清空每日公告标记。

## 3. 先在本地推送修复后的代码

在 Mac 的项目目录执行：

```bash
cd /Users/glowingstone/QuantumOriginal/QPlugin-Survival2
git switch hot-update
git status --short
```

确认 Wrapper JAR 已被跟踪：

```bash
git ls-files --error-unmatch gradle/wrapper/gradle-wrapper.jar
shasum -a 256 gradle/wrapper/gradle-wrapper.jar
```

`075cf01` 已包含的 Gradle 9.7.1 Wrapper JAR 校验值为：

```text
7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d
```

该修复同时将 `.gitignore` 的 Wrapper 例外放到 `*.jar` 之后，防止干净 checkout 再次缺失文件；
`gradle-wrapper.properties` 也已配置官方发行包 SHA-256。

如果本次 JDK 选择修复尚未提交，先提交这些文件，再推送：

```bash
git add .gitignore gradle/wrapper/gradle-wrapper.jar \
  gradle/wrapper/gradle-wrapper.properties gradlew gradlew.bat \
  scripts/orin-trigger.py scripts/test_orin_trigger.py \
  docs/orin-production-deployment.md README.md ci/orin/README.md \
  ci/orin/actions/qplugin-deploy/install-host.py \
  ci/orin/actions/qplugin-build/build.sh ci/orin/actions/qplugin-build/main.kts \
  ci/orin/actions/qplugin-deploy/main.kts ci/orin/orin.env.example \
  scripts/test_orin_build_jdk.py test-support/orin/OrinTargetsSmoke.kt
git diff --cached --stat
git commit -m "Select a full JDK for QPlugin Orin builds"
git push origin hot-update
git rev-parse HEAD
```

若本地修复已提交，直接 `git push origin hot-update`，再记录 `git rev-parse HEAD` 输出。
后续构建和两个服部署统一使用这个**完整提交 SHA**；不要继续使用前面失败的旧 SHA。

## 4. 登录服务器并取得同一份源码

```bash
ssh -p 66 glowingstone@10.10.0.1
```

下列命令均在服务器执行。把变量填写为第 3 步记录的完整 SHA：

```bash
QPLUGIN_COMMIT='填写刚推送的40位完整SHA'
QPLUGIN_SOURCE=/opt/server/orin/source/qplugin
QPLUGIN_ORIN=/opt/server/orin
```

准备专用部署 checkout：

```bash
mkdir -p "$QPLUGIN_SOURCE"
if [ ! -d "$QPLUGIN_SOURCE/.git" ]; then
  git -C "$QPLUGIN_SOURCE" init
  git -C "$QPLUGIN_SOURCE" remote add origin \
    https://github.com/glowingstone124/QPlugin-Survival2.git
fi
git -C "$QPLUGIN_SOURCE" -c http.proxy=http://127.0.0.1:7890 \
  fetch --depth=1 origin "$QPLUGIN_COMMIT"
git -C "$QPLUGIN_SOURCE" checkout --detach FETCH_HEAD
git -C "$QPLUGIN_SOURCE" rev-parse HEAD
git -C "$QPLUGIN_SOURCE" ls-files --error-unmatch gradle/wrapper/gradle-wrapper.jar
```

两次 SHA 应一致，最后一条必须能列出 Wrapper JAR。

## 5. 备份并同步 Orin action

先等已有 QPlugin 任务结束；不要覆盖正在执行的构建脚本。

```bash
sudo -n /usr/local/sbin/qplugin-orin-admin status
curl --fail http://127.0.0.1:9913/
```

健康检查应返回 `Orin running`。管理器输出的 `children` 非空时，说明有子任务，需要等待。

备份当前 QPlugin 配置和 action：

```bash
QPLUGIN_ORIN_BACKUP="$QPLUGIN_ORIN/backups/manual-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$QPLUGIN_ORIN_BACKUP"
cp "$QPLUGIN_ORIN/config.toml" "$QPLUGIN_ORIN_BACKUP/"
cp "$QPLUGIN_ORIN/qplugin.env" "$QPLUGIN_ORIN_BACKUP/"
cp "$QPLUGIN_ORIN/qplugin-control.json" "$QPLUGIN_ORIN_BACKUP/"
for QPLUGIN_ACTION in qplugin-build qplugin-deploy qplugin-status; do
  cp -R "$QPLUGIN_ORIN/actions/$QPLUGIN_ACTION" "$QPLUGIN_ORIN_BACKUP/"
done
chmod -R go-rwx "$QPLUGIN_ORIN_BACKUP"
```

同步最新 action 与 Python 部署器：

```bash
for QPLUGIN_ACTION in qplugin-build qplugin-deploy qplugin-status; do
  cp -R "$QPLUGIN_SOURCE/ci/orin/actions/$QPLUGIN_ACTION" "$QPLUGIN_ORIN/actions/"
done
cp "$QPLUGIN_SOURCE/ci/orin/actions/qplugin-build/build.sh" \
  "$QPLUGIN_ORIN/actions/qplugin-deploy/build.sh"
cp "$QPLUGIN_SOURCE/scripts/deploy-hot.py" \
  "$QPLUGIN_ORIN/actions/qplugin-deploy/deploy-hot.py"
```

当前机器已有四条 Pipeline，**不要再追加同名 `[[Pipeline]]`**，也不要用示例覆盖完整 `config.toml`。
action 和 `qplugin.env` 每次执行时读取，单独更新它们不需要重启 Orin。
只有修改 `config.toml` 的 Pipeline、Runner 或 Webhook 设置时，才需要重新启动 Orin。

### 5.1 检查构建配置

`/opt/server/orin/qplugin.env` 应为：

```properties
QPLUGIN_REPOSITORY=https://github.com/glowingstone124/QPlugin-Survival2.git
QPLUGIN_DEFAULT_REVISION=hot-update
QPLUGIN_JAVA_HOME=/usr/lib/jvm/jdk-25.0.3+9
QPLUGIN_RELEASE_STORE=/opt/server/orin/releases/qplugin
QPLUGIN_DOWNLOADS=/opt/server/orin/downloads/qplugin
QPLUGIN_HTTP_PROXY=http://127.0.0.1:7890
```

本机为单实例构建部署，`QPLUGIN_BUILD_NODE` 留空或不配置。
代理仅传给 QPlugin 构建子进程，Git 与 Java/Gradle 下载都会使用；不改动其他项目的环境。

确认完整 JDK 的两个可执行文件均存在：

```bash
test -x /usr/lib/jvm/jdk-25.0.3+9/bin/java
test -x /usr/lib/jvm/jdk-25.0.3+9/bin/javac
/usr/lib/jvm/jdk-25.0.3+9/bin/java -version
/usr/lib/jvm/jdk-25.0.3+9/bin/javac -version
```

最新 build.sh 会在 checkout 之前检查完整 JDK，并给构建子进程设置 `JAVA_HOME`、`PATH` 和
`-Dorg.gradle.java.home`。这会覆盖 Orin 继承的 Java 22 JRE，无需修改 MCSManager 中 Orin 的启动 JRE。
只添加配置行还不够，第 5 步必须同步 build.sh 及 build/deploy 两个 main.kts；否则旧 action 不会传递该设置。
Orin 的 KTS 调试日志仍可能显示 Java 22 JRE，这不代表 Gradle 使用同一个 JVM；
在构建日志中确认 `QPlugin build JAVA_HOME` 和 java/javac 的版本输出即可。
配置优先级参照 [Gradle 构建环境说明](https://docs.gradle.org/current/userguide/build_environment.html)。

`actions/qplugin-deploy/targets.properties` 应为：

```properties
qplugin-deploy-survival.hotDir=/opt/server/qo2/plugins/QuantumPlugin/hot
qplugin-deploy-creative.hotDir=/opt/server/qo_creative/plugins/QuantumPlugin/hot
```

### 5.2 令牌与触发客户端

现有 `/opt/server/orin/qplugin-control.json` 保存本机端口和各 Pipeline 令牌，权限应为 `600`。
保留它，不要用仓库示例覆盖，也不要提交到 Git。

```bash
chmod 600 "$QPLUGIN_ORIN/qplugin-control.json" "$QPLUGIN_ORIN/qplugin.env"
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" --help
```

下文客户端从该本地文件读取令牌，不会把令牌放进命令行参数或打印出来。
当前节点未配置集群级 `nodeToken`；如果以后启用，应在控制文件中用 `clusterToken` 保存对应的已有值，
供 job 状态查询使用。

## 6. 先构建一次，等待确认成功

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --config "$QPLUGIN_ORIN/qplugin-control.json" \
  --pipeline qplugin-build \
  --revision "$QPLUGIN_COMMIT"
```

客户端会先打印 jobId，再等待 `QUEUED → RUNNING → SUCCESS`。只有最终 `SUCCESS` 才算构建完成。
首次构建需要下载 Gradle 和依赖；该步骤不重启游戏服。

失败时查看：

```bash
tail -n 120 "$QPLUGIN_ORIN/qplugin-build-last.log"
```

完整异常也可在 MCSManager 的 Orin 控制台查看，应关注最后的 `Caused by` 和命令 stderr。
若终端断开或客户端等待超时，使用已经打印的 jobId 查询：

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --config "$QPLUGIN_ORIN/qplugin-control.json" \
  --status '填写已有jobId'
```

不要因为客户端断开就再次提交安装请求；Orin 中原任务可能仍在运行。

### 6.1 确认产物

成功后应存在：

```bash
QPLUGIN_ZIP="$QPLUGIN_ORIN/releases/qplugin/$QPLUGIN_COMMIT.zip"
sudo ls -lh "$QPLUGIN_ZIP"
sudo python3 - "$QPLUGIN_ZIP" "$QPLUGIN_COMMIT" <<'PY'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    manifest = json.loads(archive.read('manifest.json'))
    assert manifest['releaseId'] == sys.argv[2]
    print('releaseId:', manifest['releaseId'])
    print('hosts:', ', '.join(manifest['hosts']))
    print('modules:', ', '.join(m['id'] for m in manifest['modules']))
    print('files:', ', '.join(archive.namelist()))
PY
```

应包含三个完整插件、`sponsor-prefix.jar`、`daily-login.jar` 和 `manifest.json`。
不要安装本地 `paper-validation/fixtures/` 中的 v1/v2/故障测试包，也不要把 `QHotProbe` 放入正式服。

这里的 `sudo` 由你本人正常认证。当前免密规则只授权 Orin 管理器，不授予任意 Docker 或文件管理命令。

## 7. 首次安装：先创造服，再生存服

这一步会短暂重启对应游戏服。安装器已经过本地边界测试，但尚未在这两个正式容器执行，
因此先在人数较少的创造服验证实际启动，再处理生存服。

### 7.1 更新当前状态

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --config "$QPLUGIN_ORIN/qplugin-control.json" \
  --pipeline qplugin-status
cat "$QPLUGIN_ORIN/qplugin-status.json"
```

检查 online、容器角色和绑定目录，按你的维护时间执行后续步骤。

### 7.2 安装创造服宿主

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --config "$QPLUGIN_ORIN/qplugin-control.json" \
  --pipeline qplugin-deploy-creative \
  --release-commit "$QPLUGIN_COMMIT" \
  --install-host
```

`--release-commit` 使用第 6 步构建的同一 ZIP，不重复编译。
`--install-host` 显式允许首次完整插件安装和重启；缺少这个选项时，不存在的宿主不会被自动安装。

安装器依次执行：

1. 校验发布清单、文件摘要、创造服宿主类型，以及容器的 `/opt/server/qo_creative` 挂载。
2. 备份旧 JAR 和 `plugins/QuantumPlugin` 到 `/opt/server/qo_creative/backups/qplugin/<SHA>-<时间>/`。
3. 将新完整 JAR 暂存到 Paper 的 `plugins/update` 中，保留当前运行中的 JAR。
4. 向该容器唯一的 Java 进程发送 SIGTERM，等待保存、退出，再恢复原容器配置。
5. 确认启动后的 JAR 摘要、`hostCodeSha256`、`hostName` 和 ready 状态，再提交模块应用请求并等待回执。

正常停止超过 150 秒时会报告失败，不发送 SIGKILL。其他插件已有待更新 JAR 时也会拒绝重启。
更新目录的行为参照 [Paper 更新指南](https://docs.papermc.io/paper/updating/)。

### 7.3 验证创造服

```bash
python3 - <<'PY'
import json
from pathlib import Path
p = Path('/opt/server/qo_creative/plugins/QuantumPlugin/hot/status.json')
s = json.loads(p.read_text())
for name in ['hostName', 'running', 'phase', 'activeRelease', 'previousRelease', 'restartRequired', 'modules']:
    print(name, s.get(name))
assert s['hostName'] == 'QuantumPlugin-Creative'
assert s['running'] and s['phase'] == 'ready' and not s['restartRequired']
PY
```

还应确认：

- `activeRelease` 等于 `$QPLUGIN_COMMIT`，两个模块均在列表中。
- 创造服可以连接，`/login` 正常；前缀和每日公告行为符合配置。
- 游戏服控制台 `/qhot status` 正常，启动日志没有 QPlugin 启用异常或随即禁用。
- 已记录本次 `backups/qplugin/` 下的备份目录。

若任一项失败，先处理创造服，不继续更新生存服。

### 7.4 安装生存服宿主

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --config "$QPLUGIN_ORIN/qplugin-control.json" \
  --pipeline qplugin-deploy-survival \
  --release-commit "$QPLUGIN_COMMIT" \
  --install-host
```

验证方法相同，读取 `/opt/server/qo2/plugins/QuantumPlugin/hot/status.json`，
预期 `hostName` 为 `QuantumPlugin`，`activeRelease` 为相同 SHA。
备份位于 `/opt/server/qo2/backups/qplugin/<SHA>-<时间>/`。

## 8. 以后如何日常更新

每次改动提交并推送 `hot-update`，记录新 SHA。重复第 4、6 步，构建一次后部署两个目标：

```bash
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --pipeline qplugin-deploy-creative --release-commit "$QPLUGIN_COMMIT"
python3 "$QPLUGIN_SOURCE/scripts/orin-trigger.py" \
  --pipeline qplugin-deploy-survival --release-commit "$QPLUGIN_COMMIT"
```

默认控制文件即 `/opt/server/orin/qplugin-control.json`，所以可以省略 `--config`。
以上没有 `--install-host`，只应用兼容模块，不会自行停止游戏服。

- 只改 `sponsor-prefix`、`daily-login` 实现：通常可在线更新。
- 改宿主、登录、QMod 桥接、NMS、公共依赖或插件声明：指纹变化会返回 `RESTART_REQUIRED`，
  需要维护窗口中显式使用 `--install-host`。
- 只改每日公告内容：直接编辑后端工作目录的 `daily-login-ad.json`，正常 TPS 下约 5 秒刷新。

## 9. 回滚

### 9.1 模块回滚

在对应游戏服控制台执行：

```text
qhot status
qhot rollback
qhot status
```

游戏内使用 `/qhot rollback`，需要相应权限并已正常登录。
成功后 active/previous 互换，旧模块重新加载；不会清空玩家已有的每日公告标记。

也可以对已保留的兼容发布运行部署 Pipeline，`--release-commit` 指向旧 SHA。
回滚不撤销已发送消息、世界数据、数据库或外部 API 写入。

### 9.2 完整插件回滚

首次安装或宿主升级失败时，需要管理员操作对应容器。以下以创造服为例：

```bash
sudo docker stop -t -1 minecraft_server_creative
```

`-t -1` 会等待正常退出，不因超时自动强制结束，参见
[Docker stop 说明](https://docs.docker.com/reference/cli/docker/container/stop/)。
确认容器已停止后，从这次安装的备份目录恢复：

- 备份中的 `QuantumPlugin-Creative-1.14.5.5.1.jar` → 当前 `plugins/` 下同名 JAR。
- 将新 `plugins/QuantumPlugin` 目录移到旁边保留，再恢复备份中的 `QuantumPlugin` 目录。
- 如果备份中没有该数据目录，说明安装前不存在；保留移走的新目录，不将其当作旧配置恢复。
- 将 `plugins/update/` 中本次仍未应用的 QPlugin 候选 JAR 移到旁边保留，避免下次启动重新安装它。
- `plugins/` 中只保留一个内部名称为 `QuantumPlugin` 的主插件 JAR。

然后：

```bash
sudo docker start minecraft_server_creative
```

生存服对应容器 `minecraft_server` 和 `/opt/server/qo2`。
这里需要你本人输入 sudo 密码或使用已有 Docker 管理权限，不需要额外扩大免密规则。
这次升级没有世界格式迁移步骤，不要为插件回滚直接覆盖整个世界目录。

### 9.3 撤销 Orin 接入配置

若需要恢复原 QAPI3-only 配置，先另存当前配置，再恢复最初备份：

```bash
cp /opt/server/orin/config.toml \
  /opt/server/orin/backups/config-before-qplugin-rollback.toml
cp /opt/server/orin/backups/qplugin-20261030-1793342855/config.toml \
  /opt/server/orin/config.toml
```

在 MCSManager 中重启 Orin。未引用的 QPlugin action 可以保留；恢复 CI 配置不会自动回滚游戏服插件。

## 10. 已知问题与排查

| 现象 | 处理 |
| --- | --- |
| `curl 28`、GitHub 443 超时、`SSL_read ... unexpected eof` | 检查本机 7890 代理和 `qplugin.env` 的 `QPLUGIN_HTTP_PROXY`；Git 与 Java 都需要代理 |
| `Unable to access jarfile .../gradle-wrapper.jar` | 使用包含 Wrapper 修复的新提交；先确认 `git ls-files` 能列出该 JAR |
| `No Java compiler found` | 设置 `QPLUGIN_JAVA_HOME=/usr/lib/jvm/jdk-25.0.3+9`，同步最新 build.sh 与两个 main.kts，再触发构建；项目 toolchain 不能代替启动 Gradle 所需的编译器 |
| `status.json` 不存在 | 尚未安装宿主，首次使用 `--install-host` |
| `Wrong backend` | 检查 Pipeline 与 targets.properties；创造服数据目录仍是 `plugins/QuantumPlugin` |
| `RESTART_REQUIRED` | 宿主不兼容，安排完整插件安装和重启 |
| `ACTIVE_RESTART_REQUIRED` | 新模块可能已生效但旧资源清理失败；先看实际 active，再安排后端重启，避免盲目重试 |
| Java 已退出、容器 wrapper 仍运行 | 安装器会报告失败；管理员确认完成保存后，正常停止/启动该容器，再检查已暂存的候选 JAR |
| 同 SHA 产物不同、不可变发布冲突 | 保留原产物并排查构建差异；发布新提交，不覆盖旧 ZIP |
| 客户端超时 | 用原 jobId 查询，并检查游戏服 status/results；不把超时等同于失败 |
| Orin 重启后旧 jobId 404 | job 注册表在内存中，先检查持久 ZIP 和后端实际状态 |
| 发送 SIGTERM 后 Orin 没回来 | 本机已实测未自动恢复，需要在 MCSManager 手动启动 |

`qplugin-orin-admin restart` 当前只负责向匹配的 Orin JVM 发送 SIGTERM，并不执行启动命令。
修改 Orin 服务配置后，优先使用 MCSManager 的正常重启流程；不要假定守护选项已经启用。

## 11. GitHub push 自动触发单独配置

当前部署可以完整通过 Orin 手动/cluster 执行，但不等于已经开启 GitHub push 自动发布。

要启用，需要在 [QPlugin 仓库 Webhooks 设置](https://github.com/glowingstone124/QPlugin-Survival2/settings/hooks) 中：

1. 复用 QAPI3 现有 Orin Webhook 的**公网入口**，路径使用 `/webhook/github`；GitHub 无法访问 `10.10.0.1`。
2. Content type 选择 `application/json`，事件选择 push。
3. Secret 使用当前 Orin `config.toml` 中 `[Webhook].githubSecret` 的既有值；不要更换原值导致 QAPI3 验签失效。
4. 在两个目标部署 Pipeline 的 triggers 中加入 `github.push`，保留 `repository = "QPlugin-Survival2"`。
5. 修改 `config.toml` 后在 MCSManager 重启 Orin，检查 GitHub Recent Deliveries 和实际部署版本。

当前 Orin 上下文没有 push 的分支和 head SHA，也没有分支过滤：
开启后，仓库任意分支的 push 都可能触发构建 `QPLUGIN_DEFAULT_REVISION` 指定分支的最新状态。
若要求“只部署 hot-update 的该次提交”，应先扩展 Orin 的 ref/head SHA 传递和分支过滤；
在此之前，正式部署使用本文的固定 SHA 流程。

## 12. 最终验收

- Orin 原有 QAPI3 流水线配置仍在。
- 指定 SHA 的构建任务为 `SUCCESS`，对应 ZIP 已保留。
- 创造服、生存服均可连接，完整插件没有启用异常。
- 两边 `status.json` 的 hostName 正确，running 为 true，phase 为 ready，restartRequired 为 false。
- 两边 activeRelease 均为指定 SHA，模块包含 sponsor-prefix 和 daily-login。
- 已记录两个后端的备份路径，并知道模块回滚与完整插件回滚的区别。

本地 Paper 验证细节见 [验证记录](paper-validation.md)，模块 API 和边界见 [热更新说明](hot-update.md)。

# main 推送自动部署

日常更新只需提交并推送 `main`：

```bash
git switch main
git pull --ff-only origin main
# 修改代码并完成本地检查后
git add <本次修改的文件>
git commit -m "描述本次修改"
git push origin main
```

GitHub push → 已有 Orin 的 `qplugin-ci` → 构建、测试、打包一次 → 创造服 → 生存服。
无需手工运行 `orin-trigger.py`，无需每次复制插件 JAR。
GitHub Actions 继续提供独立构建报告；生产部署由 Orin 执行。

只接受 `glowingstone124/QPlugin-Survival2` 的 `refs/heads/main`，忽略其他分支、标签和分支删除。
使用 Webhook 的 `after` 完整 SHA 构建，排队期间新提交不会改变已排队任务的版本。
一次推送包含多条提交时，部署该次推送的最后一个提交。

模块代码变化且宿主指纹兼容时在线热更新。宿主代码或依赖变化时，自动备份该后端、通过
Paper update-folder 暂存完整 JAR、正常保存退出，再启动该后端容器。MCSManager 重建容器时
按固定挂载目录重新定位，不会操作其他实例。此类更新会短暂中断对应后端；Velocity 不重启。
创造服确认失败时停止流水线，生存服不会继续更新。模块激活失败由宿主事务回滚。
两个后端依次确认，不构成跨服务器原子事务；生存服失败时创造服可能已更新，应分别查看状态。

## 一次性安装与维护

生产 Orin：SSH `glowingstone@10.10.0.1:66`，工作目录 `/opt/server/orin`，MCSManager 管理。
GitHub Webhook 使用 `https://build.qoriginal.vip/webhook/github`，JSON、仅 push、启用 SSL 校验；
Secret 与 Orin 的 `Webhook.githubSecret` 一致，不提交到仓库。

Orin 需支持 KTS `context.github.ref/after/deleted/fullName`；缺少字段时升级 Orin，不能回退为
构建分支最新提交。在可信源码目录安装 action：

```bash
bash ci/orin/install-actions.sh /opt/server/orin
```

将 `ci/orin/pipelines.example.toml` 中 `qplugin-ci` 一节合并到已有 `config.toml`，设置私密
manualToken，保留其他项目配置，不重复添加同名 Pipeline。`qplugin.env` 配置：

```properties
QPLUGIN_REPOSITORY=https://github.com/glowingstone124/QPlugin-Survival2.git
QPLUGIN_DEFAULT_REVISION=main
QPLUGIN_RELEASE_STORE=/opt/server/orin/releases/qplugin
QPLUGIN_DOWNLOADS=/opt/server/orin/downloads/qplugin
QPLUGIN_JAVA_HOME=/usr/lib/jvm/jdk-25.0.3+9
QPLUGIN_HTTP_PROXY=http://127.0.0.1:7890
```

创造服 `/opt/server/qo_creative`、生存服 `/opt/server/qo2`，两者插件数据目录均保留
`plugins/QuantumPlugin`。`qplugin-ci/targets.properties` 固定后端路径，触发请求不能覆盖它。
action/env 按次读取；更新 Orin JAR 或 config.toml 后，通过 MCSManager 正常重启 Orin。
等待正在运行的 Pipeline 结束后再修改 action 或重启。

## 查看结果与恢复

```bash
cat /opt/server/orin/qplugin-ci-last-success.json
tail -100 /opt/server/orin/qplugin-ci-last.log
```

以本次 SHA 的 `QPlugin CI SUCCESS` 日志和两个后端的新鲜 `hot/status.json` 为准。
`last-success.json` 只表示最近一次成功，后续失败不会把它改写为成功。
GitHub Webhook 202 仅表示已接收，不表示部署完成。
不可变包保存在 `releases/qplugin/<SHA>.zip`，完整宿主备份在各后端 `backups/qplugin/`。

日常回退使用 `git revert <有问题的提交>` 后推送 `main`，Orin 自动构建并部署回退后的代码。
紧急模块回退可在对应后端控制台执行 `qhot rollback`，随后仍应修复或回退 `main`。
宿主启动失败时，按[人工恢复手册](orin-production-deployment.md)恢复备份。

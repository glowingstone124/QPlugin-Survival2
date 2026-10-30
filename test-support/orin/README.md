# Orin action 集成检查

使用现有 Orin 的胖 JAR，运行真实 `PipelineService`、KTS 上下文暴露及 Shell 执行器。
构建脚本和末端部署程序使用隔离桩，目标路径改为构建目录；不连接线上服务端。

覆盖 manual 无 jobId、cluster 有产物登记、两个后端的路径与宿主选择、忽略输入中的目标覆盖、
错误后端拒绝及未知 Pipeline 拒绝。实际模块激活另由 Paper 验证，见 `docs/paper-validation.md`。

从 QPlugin 仓库目录执行（JDK 和 Orin JAR 路径按本机修改）：

```sh
ORIN_JAR=/path/to/Orin-all.jar
JDK25=/path/to/jdk25
mkdir -p build/orin-smoke
"$JDK25/bin/java" -cp "$ORIN_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$ORIN_JAR" \
  -d build/orin-smoke/smoke.jar test-support/orin/OrinTargetsSmoke.kt
QPLUGIN_DOWNLOADS="$PWD/build/orin-smoke/downloads" QPLUGIN_BUILD_NODE= \
  "$JDK25/bin/java" -cp "build/orin-smoke/smoke.jar:$ORIN_JAR" \
  OrinTargetsSmokeKt "$PWD/build/orin-smoke/work" "$PWD/ci/orin/actions"
```

两项故障注入会出现预期的失败日志；只有程序最终返回 0 并打印所有 `PASS` 才表示验证成功。

`OrinCiSmoke.kt` 另验证 `qplugin-ci` 的 GitHub 精确 SHA、分支/仓库/删除过滤、构建一次、
创造服先行、热模块与完整宿主模式选择，以及首台失败后停止。使用支持新 GitHub 上下文的 Orin JAR，
同样编译该文件，再运行 `OrinCiSmokeKt <隔离目录> "$PWD/ci/orin/actions/qplugin-ci"`。

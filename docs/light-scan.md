# 世界光源与空间亮度扫描

生存服管理命令，玩家和控制台均可使用。权限 `quantum.lightscan`，默认仅 OP。

```text
/lightscan scan world
/lightscan scan world -1000 -1000 1000 1000
/lightscan scan world -1000 -1000 1000 1000 60 100
/lightscan status
/lightscan cancel
```

参数为 `世界 [x1 z1 x2 z2 [minY maxY]]`。矩形的两个角可交换；所有边界包含端点，省略 Y 时扫描世界的完整建筑高度。世界必须已加载，可用 Tab 补全世界名称。一次只运行一个任务。

## 扫描口径

统计方块**当前状态自身的发光强度大于 0**的光源，包括火把、岩浆、火、海晶灯，以及亮着的红石灯、熔炉、蜡烛等；熄灭的方块不计入。不把受邻近光源照亮的空气/普通方块当作光源，不统计天空光、实体或客户端动态光源。

另外逐方块导出传播后的空间亮度，包含空气、实体方块以及零亮度位置，区分 `block_light`（方块光照）与 `sky_light`（天空光照）。`sky_light` 是存储的原始天空光，未应用昼夜/天气的衰减；`max_stored_light` 只是两个存储层的最大值，不代表夜间的实际亮度，也不能直接作为怪物生成判定。

通过 [BlockData.getLightEmission()](https://jd.papermc.io/paper/1.21.10/org/bukkit/block/data/BlockData.html#getLightEmission()) 判断光源，不维护易遗漏的材质名单。这里的 `emission_sum` 是光源强度的统计和，并不是光照传播模拟或实际照明覆盖率。

全世界扫描枚举该维度 Anvil `region/*.mca` 的位置表，并合并任务开始时已加载但可能尚未保存的区块；指定范围时过滤同一候选集合。支持主世界、下界 `DIM-1/region`、末地 `DIM1/region` 的标准目录及世界根目录 `region` 布局。没有磁盘区域文件且未加载的区块不在扫描集合中。文件头损坏会让任务失败，避免把漏扫当成成功。

每次只请求一个区块，使用 Paper 异步加载接口且 `generate=false`，不生成新地形。每个区块在主线程取得[只读快照](https://jd.papermc.io/paper/26.1.2/org/bukkit/ChunkSnapshot.html)，扫描和磁盘写入在专用后台线程完成，最多保留一个区块的快照和 CSV 缓冲。加载请求至少间隔一个服务器 tick。扫描临时加载的区块取得快照后请求安全卸载，由服务器决定实际卸载时机；已有玩家或插件需求的区块不强制卸载。

加载仍有服务器开销，大世界建议先扫描小范围评估速度，再在低负载时运行。区块逐个取得快照，扫描期间发生的变化可能体现于后面的区块；这不是同一时刻的全世界快照，开始枚举后新增的区块也不保证包含。

线程边界：主线程只负责命令及世界信息读取、已加载区块坐标捕获、发起区块加载、取得快照和安全卸载请求、消息通知。区域文件枚举、范围过滤、逐方块读取快照、光源判定、亮度汇总、CSV/JSON 序列化、Gzip 压缩和文件写入全部由专用 `QO-light-scan` 后台线程执行。区块加载回调只交接快照，不执行扫描。扫描入口、快照处理和元数据写入均校验后台线程身份，误放进主线程时直接拒绝执行；主线程不等待任务完成。

## 导出文件

结果位于 `plugins/QuantumPlugin/light-scans/<UTC时间>-<随机ID>/`（以实际插件数据目录为准）。命令完成消息和 `status` 会显示完整路径。

| 文件 | 用途 |
| --- | --- |
| `sources.csv` | 每个光源的世界、XYZ、区块坐标、材质、完整方块状态、`emission`、所在位置的 `block_light`/`sky_light`、区块快照世界时间 |
| `lighting.csv.gz` | Gzip 压缩的逐方块空间亮度 CSV：世界、XYZ、区块坐标、材质、源发光强度、传播后的方块光、原始天空光、两层最大值和快照时间；包含空气和零亮度位置 |
| `chunks.csv` | 每个成功扫描区块的中心 XZ、实际扫描范围、扫描方块数、光源数、强度和、最大强度、快照时间，以及两类空间亮度的和、最大值和各级亮度数量（`block_light_0`–`15`、`sky_light_0`–`15`）；包含零光源区块 |
| `materials.csv` | 按材质汇总的光源数量与强度和 |
| `metadata.json` | 数据版本、世界 UUID、起止 UTC 时间、范围、任务状态、候选/成功/跳过区块数、光源总数、统计口径、错误信息 |

CSV 使用 UTF-8，文本字段以双引号转义，能保留含逗号的方块状态。坐标以方块为单位，亮度为 0–15。`capture_full_time` 是游戏世界 tick 时间，用于辨认分批快照，并非 UTC 时间。

空间亮度 CSV 一行对应一个方块位置。384 格高度的完整区块有 98,304 行，因此全世界导出可能很大；建议先用矩形范围和 Y 范围扫描。Excel 使用前解压 `lighting.csv.gz`；pandas 可直接读取压缩文件，大文件应使用 `chunksize` 分批读取。

热力图可直接取 `chunks.csv` 的 `center_x`、`center_z`、`source_count`；细粒度图可将 `sources.csv` 的 XZ 按任意网格聚合，也可用 Y 字段筛选楼层。范围边缘区块的扫描面积可能小于 16×16，比较密度时用实际范围计算面积，或按 `scanned_blocks` 归一化。未出现于 `chunks.csv` 的格子应显示为缺失数据，而不是 0。

空间亮度热力图可用 `block_light_sum / scanned_blocks` 作区块平均方块光，用 `block_light_0 / scanned_blocks` 作零方块光比例。两者均包含固体方块内部；如果关心通道或玩家活动空间，可分批读取 `lighting.csv.gz`，按材质（如空气）及高度筛选后再聚合。

例如生成供绘图库读取的区块二维矩阵（需要 pandas）：

```python
import pandas as pd

chunks = pd.read_csv("chunks.csv")
matrix = chunks.pivot(index="chunk_z", columns="chunk_x", values="source_count")
matrix = matrix.reindex(
    index=range(chunks.chunk_z.min(), chunks.chunk_z.max() + 1),
    columns=range(chunks.chunk_x.min(), chunks.chunk_x.max() + 1),
)
matrix.to_csv("heatmap-grid.csv")  # 缺失格子保留为空，不填零

# 小范围的指定高度亮度热力图
lighting = pd.read_csv("lighting.csv.gz")
layer = lighting[lighting.y == 64]
layer.pivot(index="z", columns="x", values="block_light").to_csv("light-y64.csv")
```

## 完整性与取消

`metadata.json` 的 `status` 为 `running`、`completed`、`cancelled` 或 `failed`。仅当 `status=completed` 且没有跳过区块时，`complete=true`，表示完成了本次枚举的候选集合。

取消或停用插件时保留已完成区块，未完成的当前区块不会提交到 CSV。不存在/未完全生成而无法加载的候选区块会跳过并计数；加载报错、60 秒超时或写入失败会终止任务。统计前应查看元数据，避免把部分导出当作完整世界数据。进程异常退出时可能保留 `running` 状态或写到一半的 CSV，此类导出不可视为完整。

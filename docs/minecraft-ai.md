# Minecraft 恋恋对话

共享模块 `ChatSync` 处理启用 LLM 的服务器聊天入口。玩家输入 `@恋恋 <内容>` 后调用 QAPI 的 `POST /qo/asking/v1/chat/completions/minecraft?model=fast`。

- 请求固定为 `model: fast`、`reasoning_effort: none`、`stream: false`。玩家不能通过聊天内容选择模型或提升思考强度，不使用关键词分类。
- 节点令牌通过 `Authorization: Bearer` 发送，Minecraft 玩家名由 QAPI 映射到绑定的 QO/QQ 身份；位置和血量在服务器主线程读取。
- 每次请求携带独立 `X-Request-ID`，后端按该身份处理幂等、并发和 RPM。
- 每周 Units 与 QQ、Kotshi 等入口共用；已绑定账号默认 120 Units/周，周一北京时间 00:00 重置。免费额度不足后使用 Paid Credits，允许一次请求跨两个额度池结算。
- 成功回复广播到当前服务器；已结算的 `quota.charged_units` 只提示发起玩家。插件不自行预估或扣除 Units。
- 周额度不足、短时限流、认证失败、重复请求和服务错误只反馈给发起玩家，不作为恋恋回复广播。失败退款由 QAPI 处理。

此适配共用于生存、创造和 Chambers 插件，是否启用 LLM 仍由各插件的 `ChatSync.configure` 配置决定。

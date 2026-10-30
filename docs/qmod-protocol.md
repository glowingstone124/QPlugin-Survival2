# QMod ↔ QPlugin v1

QMod 的安装探测与免密码游戏登录使用 Play 阶段自定义 payload。
传输方式对应 [Fabric Networking](https://docs.fabricmc.net/develop/networking) 和
[Paper Plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/)。

## 通道与编码

- 服务端 → 客户端：`qmod:hello`。
- 客户端 → 服务端：`qmod:reply`。
- payload 是 **原始 UTF-8 JSON**，最长 4096 字节，没有 Java `writeUTF` 或 Minecraft VarInt 字符串长度前缀。
- 每条消息有 `version: 1`、`op`、`nonce`。nonce 是 32 字节 CSPRNG 的无填充 Base64URL，43 字符。
- 未安装 QMod 的客户端无需响应，正常使用密码登录。安装探测只是能力声明，不是反作弊认证。

## 探测

1. 玩家进入服务器，QPlugin 生成新 nonce，在第 1、20、60 tick 发送同一探测（通道注册完成时也可立即补发）：

```json
{"version":1,"op":"probe","nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
```

2. QMod 响应（真实实现使用随机 nonce；上述值只用于协议示例）：

```json
{"version":1,"op":"hello","nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","modVersion":"1.0.0","autoLogin":false}
```

QPlugin 可用 `QModBridge.hasQMod(player)` / `modVersion(player)` 查询本次连接是否响应过探测及其声明版本。
管理员可用 `/qmodstatus [player]` 查询后端检测结果，权限为 `quantum.qmodstatus`（默认 op）。
该状态在玩家退出时清除。QMod 的登录状态恢复或设置改变时，可以再次报告 `autoLogin`；
每个连接 nonce 只允许进入一次免密验证。重复 probe 会再次收到 hello，以处理首包丢失；
相同 nonce 不会续期或重复申请票据。不同后端的新 nonce 立即替换旧会话，移除了旧实现 5 秒内忽略新探测的限制。
异步授权只允许向原连接、原 nonce 回传，快速切服、断线重连和重复包不能复用旧票据。

## 免密码登录

1. QPlugin 收到 `autoLogin: true`，并且服务器开关已开启、玩家仍有 `guest` 登录限制时，
   用现有 `API_SECRET`（QAPI 的 `Role.SERVER` 节点 Token）调用：
   `POST /qo/auth/game/challenge`，请求头 `Token: <server token>`。
   JSON：`username`、规范小写 `playerUuid`、`nonce`。
2. QAPI 返回 `ok`、`nodeId`、`challenge: GC-...`、`expiresIn: 60`。
3. QPlugin 在 `qmod:hello` 发送 `op: challenge`，携带上述字段及原来的用户名、UUID、nonce。
4. QMod 要求本地免密开关开启、当前连接为本地信任的主机、QO 会话有效，且包内游戏名与 UUID
   与当前玩家一致。随后仅向固定 QAPI 地址调用：`POST /qo/auth/game/approve`，
   `Authorization: Bearer <QMod session token>`；JSON：`username`、`playerUuid`、`nonce`、`nodeId`、`challenge`。
5. QAPI 要求会话的 `client_id=qmod`，账号未冻结且与游戏用户名相符；QQ 会话解析当前绑定账号。
   消费 GC 挑战，返回 `ticket: GP-...`（30 秒、单次使用）。
6. QMod 在 `qmod:reply` 发送 `op: proof`、原 nonce、原 challenge 和 ticket。
7. QPlugin 用服务端 Token 调用 `POST /qo/auth/game/redeem`，JSON 为上述连接字段与 ticket。
   QAPI 按调用节点绑定校验，原子消费 GP 凭据，并再次确认账号存在且未冻结。
8. QPlugin 在主线程确认仍是原 Player 对象、同一次连接、玩家在线且仍待登录后，移除 `guest`，
   执行与密码登录相同的成功通知、登录记录和后续钩子。密码登录、IP 自动登录和 QMod 登录共用一次性主线程领取。

游戏的 GC / GP 使用 `auth:game:v1:` Redis 命名空间，和网页 SSO 的 ST 票据独立。
GC 不能作为 GP 使用；GC 和 GP 都不能被 `/qo/auth/serviceValidate` 兑换为网页会话。
长效 QMod Token 不发送给 Minecraft 服务器。游戏验证不额外产生 IP 自动登录授权。
游戏名、UUID、服务器节点、nonce、challenge 都绑定到 GP 凭据；旧连接、重复包、超时、冻结、
API 失败或关闭开关均不会解除 `guest`，玩家继续使用 `/login`。
QMod 切换设置不会退出已经完成的游戏登录。此开关仅控制 QMod 免密，现有的同 IP 短期自动登录机制保持不变。

## 开关与上线

先部署 QAPI3 新接口，再更新服务端 QPlugin 和客户端 QMod。
两个开关均默认关闭：

```yaml
# QPlugin 工作目录的 config.yml；节点身份继续使用原有 API_SECRET。
QMOD_PASSWORDLESS_LOGIN_ENABLED: true
```

QMod：设置 → **使用 QMod 免密码登录游戏**。
该值原子保存到 Minecraft 配置目录下的 `qmod/client.properties`，重启保留。
默认只信任 `qoriginal.vip` 和 `*.qoriginal.vip`。本地测试或其他部署可显式配置：

```properties
passwordlessLogin=true
trustedHosts=qoriginal.vip,*.qoriginal.vip,127.0.0.1
```

主机按精确名称或带点的子域后缀匹配；服务器发来的包不能修改信任列表或指定授权 URL。
服务器与 QAPI 的线上通信使用 HTTPS，节点 Token 沿用现有服务器凭据管理方式。
Velocity 继续执行原有地域、白名单及账号关系检查；免密流程在后端 Play 阶段工作。

## Velocity 中转与查询

同时部署更新后的 QPlugin-Velocity。它显式注册 `qmod:hello` / `qmod:reply`，
在 `PluginMessageEvent` 中先设为 handled，再只允许以下方向的有效消息转发原始字节：

- 当前 `ServerConnection` → 对应 `Player`：probe / challenge / status。
- `Player` → 其当前 `ServerConnection`：hello / proof / decline。

中转检查版本、4096 字节上限、nonce、包方向、玩家对象和当前后端连接。
客户端回复必须匹配该后端先前发出的 probe；切服后旧后端、旧 nonce 及跨玩家包会被丢弃。
Velocity 不签发或兑换游戏票据，不使用代理 Token 代替后端节点 Token。

管理员可用 `/qmodproxy [player]` 查询代理看到的模组响应及版本，权限为 `qplugin.qmodstatus`。
代码可通过 `QPluginVelocity.qmodRelay.hasQMod(player)` / `modVersion(player)` 查询。
切服及断线会清理对应检测状态。新后端需重新探测，检测结果不会跨后端继承。
“未收到响应”可能是未安装模组、插件版本不匹配或中转链路未通，不代表能够证明客户端未安装。

## 登录结果与回退

扩展消息保持 `version: 1`，旧实现可忽略不认识的 op 或字段：

```json
{"version":1,"op":"status","nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","status":"password_required","reason":"server_disabled"}
```

`status` 为 detected / authenticating / authenticated / password_required。
`reason` 为 server_disabled / client_disabled / approval_failed / verification_failed / timeout / unsupported_account，
客户端仅显示本地翻译的已知原因，不展示服务端任意文本。
浏览器会话无效、账号不匹配、未信任主机或关闭客户端开关时不能申请有效证明。
授权失败时客户端发送 `op: decline`，后端立即回退 `/login`，无需等待整个超时窗口。
密码、IP 自动登录或 QMod 登录完成后共用主线程一次性完成逻辑，并回传 authenticated、使未完成的免密请求失效。
如果密码/IP 登录早于首个 hello，后端仍继续探测安装状态，收到 hello 后回传已登录结果。
每个探测窗口持续 60 秒，API 失败、超时与格式错误均不能解除 guest 限制。

联机排查顺序：确认 QO 客户端账号有效 → 开启客户端免密 → 确认连接地址在 trustedHosts →
开启后端 `QMOD_PASSWORDLESS_LOGIN_ENABLED` → 分别查询 `/qmodproxy` 和 `/qmodstatus` →
在 QMod 设置页查看状态；仍失败可继续使用 `/login`。

## 验证

- QAPI：账号、客户端类型、冻结、过期、节点/nonce/UUID/用户名绑定、并发单次兑换、跨协议隔离。
- QMod：原始 JSON 编码、包长与版本、主机伪装、开关持久化、重复探测、快速切服、过期/身份不符的挑战、一次性审批和原生控件界面。
- QPlugin：线性状态转换、密码登录取消、重复兑换与畸形 hello / proof。
- Velocity：真实 `PluginMessageEvent` API 下的完整包往返、反向伪造、跨玩家和旧后端包拦截、切服/断线清理。
- 本地完整构建和 API 事件测试不能替代真实 Velocity → Paper → QMod 联机测试或线上部署验证。

# HTTP API、OpenAPI 与 Schema 演进

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-08
>
> 事实来源：对应模块源码、测试与构建定义

本文说明 `zyblw-agent` 如何把内部耐久状态与对外 HTTP 协议分离，以及业务后端应该怎样安全接入和升级。当前基线是
`/api/v1`、OpenAPI `1.1.0`。项目尚未正式发布，因此不保留无版本旧路径，也不为草案协议制造历史负担。

## 1. 为什么单独建立 contract package

`zyblw-agent-zio-http` 的 `com.zyblw.agent.http.contract` package 包含：

- v1 请求、响应和公共事件 DTO；
- 每个 DTO 的 `JsonCodec` 与 `zio.schema.Schema`；
- ZIO HTTP `Endpoint` 路径、输入、输出、状态码和说明；
- 从 Endpoint 机械生成的 OpenAPI；
- 首次发布基线的契约测试。

它不会引入 PostgreSQL、Provider 或 OTLP。首次公开版本把 contract、routes 与 host 放在一个 ZIO HTTP artifact 内，
避免使用方为同一传输栈选择三个必须同版本的小 artifact。只做跨语言客户端时应直接消费发布的 OpenAPI，而不是依赖
Scala Runtime 类型；如果未来出现明确的 JVM contract-only 消费需求，再通过 ADR 拆出独立 artifact。

同一 artifact 的 `http` package 负责把授权后的内部领域对象投影成公共 DTO。

```text
AgentState / AgentEvent / RunCommandRecord
                  │
                  │  显式、脱敏、版本化投影
                  ▼
 RunView / RunEventView / CommandView
                  │
                  ├── JSON / SSE
                  └── Endpoint → OpenAPI
```

内部 `AgentState`、`PersistedAgentEvent`、工具参数、工具结果、Provider 原文、隐藏推理、lease token 和认证上下文都不属于
HTTP 公共协议。内部恢复模型可以继续演进，不能再通过自动 JSON 派生意外泄漏到客户端。

## 2. 稳定入口

稳定控制面位于 `/api/v1`：

| 方法与路径 | 语义 |
|---|---|
| `POST /api/v1/agents/{agentId}/runs` | 携带 `Idempotency-Key`，耐久提交异步 Start |
| `GET /api/v1/runs/{runId}` | 查询低敏 Run 投影、最终输出、用量与待审批摘要 |
| `DELETE /api/v1/runs/{runId}` | 耐久提交取消命令 |
| `POST /api/v1/runs/{runId}/approval` | 批准或拒绝等待中的动作 |
| `POST /api/v1/runs/{runId}/recover` | 从最近耐久边界恢复 |
| `POST /api/v1/runs/{runId}/retry` | 以业务 `requestId` 显式重试 |
| `GET /api/v1/commands/{commandId}` | 查询异步命令状态 |
| `POST /api/v1/commands/{commandId}/retry` | 人工重试 DeadLetter 命令 |
| `GET /api/v1/runs/{runId}/commands` | 查询 Run 的低敏命令列表 |
| `GET /api/v1/runs/{runId}/inspection` | 查询低敏 Timeline、usage 与机械一致性诊断 |
| `GET /api/v1/runs/{runId}/events` | 按 `Last-Event-ID` 读取耐久公共事件页 |
| `GET /api/v1/runs/{runId}/events/stream` | 跨 Worker 可恢复 SSE |
| `GET /api/v1/openapi.json` | 当前稳定控制面的 OpenAPI JSON |

所有 v1 Agent/Memory 响应携带 `X-Zyblw-Agent-Api-Version: 1`。该响应头用于网关和客户端诊断，真正的不兼容边界仍由 URL
主版本承担。`/health/live` 和 `/health/ready` 是部署探针，不属于业务 API 版本。

v1 在进入 Runtime 前限制：agentId 128 字符、threadId 256 字符、input 65,536 字符、幂等键/requestId 256 字符、人工原因
2,048 字符。
Agent 控制面 Adapter 使用 ZStream 最多读取 256 KiB UTF-8 JSON，并以 `take(max + 1)` 在继续缓冲前识别溢出；字符限制按
Unicode code point 计算。生产网关和 ZIO HTTP Server 仍应设置更早的连接级请求体上限。这些边界不能代替
ContextEngine 的 token 预算，它们解决的是不同问题。

Memory 用户治理路由也使用 `/api/v1/memory/...`，但 DTO 暂时标记为 Beta，尚未进入稳定 OpenAPI 承诺。业务接入可以试用，
正式发布 SDK 前应先将其迁入 contract 模块并建立独立契约门禁。

### 2.1 `/api/v1/admin/**` 是有意划在稳定承诺之外的管理子面

`0.5.0` 引入的管理子面共享 `/api/v1` 前缀和同一套认证解析，但**不属于 `AgentHttpContract` 的稳定 OpenAPI 承诺**，也不出现在
`GET /api/v1/openapi.json` 的稳定基线里。它的定位是运维控制台与内部工具，允许按 minor 演进：

- 每项管理能力都是宿主注入的 `Option`。未装配的能力不挂载任何路由，`GET /api/v1/admin/capabilities` 必须把它报告为
  不可用——客户端应先读能力再决定渲染什么，而不是硬编码路径；
- 管理 scope 与业务归属规则是两套判定。管理端点绝不复用“租户/用户拥有该 Run”这条业务规则来放行；
- 需要长期集成稳定性的能力不要建在这里。它属于业务 `/api/v1`，并且要进入 contract package 和契约门禁。

因此第 4 节的 v1 兼容规则约束业务主线；管理子面的破坏性调整记入 `CHANGELOG.md` 与升级指南，不触发 `/api/v2`。详见
[管理控制台](admin-console.md)。

## 3. 公共事件不是内部 Event Store JSON

`RunEventView` 是扁平、向前兼容的事件信封：

- `eventId/runId/sequence/eventType/atEpochMilli` 是固定基础字段；
- 状态、工具进度、审批、Context 用量和最终输出使用可选子视图；
- 客户端必须忽略未知 `eventType` 与未知字段；
- 工具事件只公开 `callId/toolName/batchIndex`，绝不公开 arguments 或 result；
- Context 只公开预算统计和 rot 信号码，不公开 Memory、RAG 文本或 Prompt；
- 失败只公开 typed category 与经过安全边界裁剪的消息。

`eventType` 使用穷尽式显式映射，而不是 Scala `productPrefix`。因此内部类名重构不会无意改变 v1；新增领域事件时编译器会
要求维护者决定公共名称和脱敏投影。

SSE 的 `id` 是 Event Store 单调 `sequence`，`data` 是 `RunEventView` JSON。断线重连时，客户端把最后确认的 `id` 放入
`Last-Event-ID`。游标超过权威最后序号会在创建流之前返回 400；流建立后的数据库故障会发送低敏 `stream_error` 并结束，
客户端稍后从最后成功序号重连。

Inspector 使用相同的 `Last-Event-ID` 游标读取一页 Timeline，但不会把内部事件正文复制到聚合视图。`consistent`
只表示当前可验证范围没有 Error 级结构诊断；只有 `completeHistory=true` 才代表服务端本次检查覆盖了从创建到当前状态的
完整历史。详细语义见 [Run Inspector](run-inspection.md)。

## 4. 兼容与不兼容变更

同一 `/api/v1` 内允许：

- 新增 endpoint；
- 给响应增加可选字段；
- 增加新的 `eventType`；
- 放宽输入约束或增加新的可选请求字段；
- 增加错误诊断信息，但不得泄漏敏感数据。

下列行为必须创建 `/api/v2`，不能只修改 `1.0.x`：

- 删除、改名或改变既有字段类型；
- 把可选字段改为必填；
- 收窄已经接受的枚举值或输入范围；
- 改变 `202 Accepted` 为“已经执行完成”的语义；
- 改变 SSE `id` 的 sequence 含义；
- 让同名事件改变业务含义；
- 把已公开的低敏字段改为高敏正文。

数据库 `schema_version`、事件 payload 版本、Workflow 版本与 HTTP `/v1` 是四个不同的演进轴。升级数据库 migration 不应迫使
客户端升级；改变 HTTP DTO 也不应直接改变 Event Store 的恢复编码。

## 5. 认证与授权边界

OpenAPI 不声明固定 Bearer/JWT 方案，因为框架会被嵌入不同宿主。`AgentRequestContextResolver` 必须从已经验签的 claim、
服务端 session 或可信网关属性构造 `RunContext`，不能信任正文中的 user/tenant/scopes。读取 Run、事件和 SSE 时都会在读取
正文前执行 tenant/user 归属校验。

生产宿主可以在自己的 OpenAPI 聚合层追加安全方案，但不得因此把身份字段加入 `CreateRunRequest`。

## 6. 发布门禁

每次修改 HTTP 公共代码时至少检查：

1. `AgentHttpContractSpec`：必需路径、OpenAPI 版本、稳定 DTO 字段和内部类型泄漏；
2. `AgentHttpProjectionSpec`：工具参数、结果、消息历史和高敏正文不进入公共 JSON；
3. `AgentHttpApiSpec`：实际 Routes 与契约路径一致、版本头、旧路径 404、SSE 恢复与授权；
4. 对比发布的 OpenAPI 基线；有意 breaking change 必须新建 v2，不能直接覆盖 v1 基线；
5. 生成客户端执行 compile + smoke，并以旧客户端对新服务执行向后兼容测试；
6. 对错误、SSE 和日志运行敏感字段扫描。

当前仓库已经具备前 3 类机械测试。发布流水线中的 OpenAPI 文件归档、生成客户端矩阵和 JVM 二进制兼容检查仍是下一步，
在它们落地前不能宣称公共 SDK 已达到正式 GA 稳定性。

## 7. 业务调用示例

```bash
curl -i \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: ui-01J-STABLE-ID' \
  -d '{"threadId":"learning-thread-1","input":"请检索并说明原文依据"}' \
  http://localhost:8080/api/v1/agents/learning-agent/runs
```

收到 202 后保存 `commandId/runId`，再查询命令或订阅事件。不得把 HTTP 连接保持时间当成 Run 生命周期，也不得在网络重试时
生成新的 `Idempotency-Key`。

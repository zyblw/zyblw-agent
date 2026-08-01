# 长期记忆用户治理、事务审计与 Retention Worker

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

更新时间：2026-07-15。

## 1. 本轮产品与安全结论

长期记忆治理是生产 Agent 的信任基础，但不应该演变成未经验证的“用户健康画像平台”。本阶段采用“缩小范围做”：

- 做：用户查看自己的记忆、纠正、删除单条、删除全部、低敏审计、过期清理；
- 不做：从症状、疾病、方剂、处方或剂量自动生成长期画像；
- 不做：让模型直接删除、修改用户记忆，或让 HTTP 请求自报 tenant/user；
- 不做：复杂的记忆推荐 UI、管理后台和跨用户洞察；这些必须在真实产品数据证明价值后再投资。

成功标准不是“保存了多少记忆”，而是：所有用户变更均经过授权并留下不可变审计；并发修改不会覆盖；删除后迟到
worker 不能复活旧值；保留期任务可取消、可限流、可跨进程并行；审计和遥测不含正文、查询词或认证敏感字段。

## 2. 为什么分成三个接口

```text
MemoryLifecycle ──> MemoryStore.compareAndSet
                         │
业务用户 API ──> MemoryGovernanceService
                         │ authorize + validate
                         ▼
               MemoryGovernanceRepository
                         │ mutation + audit 同事务
                         ▼
              PostgreSQL agent_memories
                         + agent_memory_audit

MemoryRetentionWorker ──> MemoryStore.purgeExpired
                          SKIP LOCKED + bounded batches
```

- `MemoryStore` 是框架内部的通用读取/CAS/tombstone SPI，供 Context、提炼器和 retention 使用；
- `MemoryGovernanceService` 是业务入口，集中做身份归属、分页上限、纠正字段投影和审计；
- `MemoryGovernanceRepository` 是必须原子审计的写边界。PostgreSQL 实现让纠正/删除与审计在同一事务提交；
- `MemoryRetentionWorker` 负责 ZIO Fiber 生命周期、退避和批次上限，跨 Worker 正确性来自数据库行锁，而非本地锁。

业务代码不能把 `MemoryStore.put` 暴露成“修改我的记忆”API。`put` 是受控导入能力，会绕过用户治理审计。

## 3. 授权模型

`RunContext` 必须由 JWT/session 验签后的认证中间件构造，不能从请求 JSON 或模型消息读取。

| 目标 | 普通用户 | 显式权限 |
|---|---|---|
| 自己的 `MemoryScope.User(tenant,user)` | 可读、可纠正、可删除 | tenantId 与 userId 必须同时匹配 |
| 其他用户/租户 User scope | 默认拒绝 | `agent:memory:read:admin` / `agent:memory:manage:admin` |
| 同租户 Tenant scope | 默认拒绝 | `agent:memory:tenant:read` / `agent:memory:tenant:manage` |
| Session scope | 默认拒绝 | admin scope |

Session 默认拒绝普通用户，是因为当前 Memory 模块没有“session 属于哪个用户”的权威仓储。未来若业务需要 Session 治理，
应注入 `SessionOwnershipRepository` 后再放行，不能相信 URL 中的 sessionId。

匿名 admin 同样拒绝：审计必须至少拥有 tenantId 或 userId，不能留下无法追责的管理员记录。

## 4. 业务接入示例

### 4.0 已提供的 ZIO HTTP 路由

`MemoryHttpApi` 可以与 `AgentHttpApi.routes` 合并，所有身份仍由同一个 `AgentRequestContextResolver` 提供：

| 方法与路径 | 用途 | 正常状态 |
|---|---|---|
| `POST /api/v1/memory/list` | 有界列出自己的 User scope | 200 |
| `POST /api/v1/memory/search` | 搜索自己的记忆 | 200 |
| `GET /api/v1/memory/{key}` | 精确读取 | 200 / 不存在 404 |
| `PUT /api/v1/memory/{key}` | expectedVersion CAS 纠正 | 200 / 冲突 409 |
| `DELETE /api/v1/memory/{key}` | 幂等删除单条 | 200，affectedCount 0/1 |
| `DELETE /api/v1/memory` | 删除自己的全部长期记忆 | 200 |

这些路径已采用 v1 URL 和版本响应头，但 Memory DTO 当前仍是 Beta 子契约，尚未进入稳定 OpenAPI。升级边界见
[HTTP API、OpenAPI 与 Schema 演进](http-api-versioning.md)。

Memory 路由与 Agent 控制面共享 `HttpRequestBody`：每个 JSON 正文最多读取 256 KiB，并严格拒绝畸形 UTF-8。该上限是防止
无界缓冲的传输边界，不意味着业务应把大文档或 Artifact 存入 Memory；大对象应进入专用对象存储并只保存受权引用。

tenantId、userId 和 scope 不出现在任何请求 DTO 中，HTTP Adapter 只能从可信认证上下文推导
`MemoryScope.User(tenant,user)`；领域服务会再次授权。管理员/租户级治理暂不通过这组“我的记忆”路由暴露，避免普通 API
因可选 scope 参数变成越权入口。

```scala
val httpLayer = ZLayer.make[MemoryHttpApi](
  memoryGovernance,
  authenticatedRequestContextResolver,
  MemoryHttpApi.layer
)

val allRoutes = agentHttpApi.routes ++ memoryHttpApi.routes
```

HTTP 只返回稳定 DTO，不暴露 sourceRunId。CAS 冲突映射为 409，但正文保持脱敏；客户端应重新 GET 后让用户确认。

### 4.1 ZLayer 装配

```scala
import com.zyblw.agent.memory.*
import com.zyblw.agent.persistence.postgres.*
import zio.*

val governancePolicy = ZLayer.succeed(MemoryGovernancePolicy(
  maxValueCharacters = 4000,
  requireEpisodicExpiry = true,
  allowModelInferredSensitive = false
))

val memoryGovernance = ZLayer.make[MemoryGovernanceService](
  dataSourceLayer,
  PostgresMemoryStore.governanceLayer,
  governancePolicy,
  MemoryGovernanceService.layer
)
```

`governanceLayer` 同时提供 `MemoryStore` 与 `MemoryGovernanceRepository`，确保服务使用的是同一个 PostgreSQL Adapter。

### 4.2 查看与搜索

```scala
val actor = RunContext(
  tenantId = Some(authenticatedTenantId),
  userId = Some(authenticatedUserId),
  scopes = authenticatedScopes
)
val own = MemoryScope.User(TenantId(authenticatedTenantId), UserId(authenticatedUserId))

service.list(actor, own, limit = 50)
service.search(actor, own, query = "经典学习偏好", limit = 20)
service.get(actor, own, key = "learning.preferred_classic")
```

最大 `limit` 是 200。搜索 query 只进入 SQL 参数，不进入审计、错误 diagnostic 或日志。

### 4.3 CAS 纠正

```scala
service.correct(
  actor,
  own,
  MemoryCorrection(
    key = "learning.preferred_classic",
    expectedVersion = pageItem.version,
    value = Json.Str("黄帝内经"),
    importance = 0.9,
    kind = MemoryKind.Preference,
    sensitivity = MemorySensitivity.Personal,
    expiresAtEpochMilli = None
  )
)
```

客户端必须回传最后读取的 `version`。并发纠正、自动提炼或删除已经推进版本时，服务返回
`MemoryVersionConflict`，前端应重新加载并让用户确认，不能静默重试覆盖。

用户不能提交 `evidence/confidence/extractorVersion/sourceRunId/createdAt`：服务固定写成 `UserStated`、置信度 1.0、
`user-correction-v1`，并保留原始创建时间。

### 4.4 删除

```scala
service.delete(actor, own, "learning.preferred_classic") // 返回 0 或 1
service.deleteScope(actor, own)                           // 返回本次删除数量
```

删除转换为 tombstone，清空 `value_json/search_text` 并递增版本。重复删除返回 0 但仍记录一次 affectedCount=0 的用户动作，
便于区分“用户确实再次请求删除”和“系统没有收到请求”。

## 5. 审计数据最小化

`agent_memory_audit` 只保存：

- auditId、动作、发生时间；
- actor tenant/user 或固定 system 名称；
- target scope 的结构化 ID；
- 原始 memory key 的 SHA-256，不保存 key；
- expected/resulting version、affectedCount 和稳定 reasonCode。

明确不保存：记忆 JSON、搜索词、原始 memory key、prompt、工具结果、认证 token、scopes、attributes、Provider 原文。

读取操作在成功读取后写审计；若审计写失败，服务不会把已读取正文返回调用方。纠正、单条删除和 scope 删除则在同一
PostgreSQL 事务内写数据与审计，任一失败全部回滚。

## 6. Retention Worker

```scala
val retention = ZLayer.make[MemoryRetentionWorker](
  PostgresMemoryStore.layer,
  MemoryRetentionObserver.logging,
  MemoryRetentionWorker.layer(MemoryRetentionConfig(
    batchSize = 500,
    maxBatchesPerCycle = 20,
    interval = 5.minutes,
    retryInitialDelay = 250.millis,
    maxRetries = 5
  ))
)

ZIO.scoped {
  ZIO.serviceWithZIO[MemoryRetentionWorker](_.startScoped) *> ZIO.never
}.provide(retention)
```

行为契约：

1. 一轮冻结一次 cutoff，所有批次使用相同过期判定时间；
2. 单批由 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取，多实例不会互相等待同一行；
3. 返回不足 batchSize 立即结束；持续满批在 `maxBatchesPerCycle` 后主动让出连接池；
4. 只对 `retryable=true` 的 StoreError 使用指数退避、抖动和有限重试；
5. 单轮失败被低敏观测后等待 interval，不热循环，也不让清理故障杀死主 WorkerHost；
6. `forkScoped` 把 Fiber 绑定宿主 Scope，关闭时会中断 sleep/SQL 并等待资源 finalizer。

`MemoryRetentionObserver` 是 core 内 `memory` package 的小型 SPI，避免内存生命周期代码反向依赖具体观测 exporter。
生产宿主可以把 report 映射为 OTel
Metric/Span；接口没有正文参数，因此不会意外把 Memory 内容送到 Langfuse。

## 7. 数据库与发布

0.3 fresh baseline `V001__zyblw_agent_0_3_baseline.sql` 已包含 `agent_memory_audit`。0.2 开发库必须重建；不能忽略
Flyway checksum、修改 schema history 或假装原地升级。

数据库唯一事实源见[数据库迁移契约](database-migrations.md)。

## 8. 测试与尚未完成的边界

已覆盖：

- 自有 User scope、跨租户、Session、匿名管理员授权矩阵；
- 用户纠正字段投影、CAS 冲突、低敏审计；
- 单条/scope 删除幂等和 affectedCount；
- query、正文、原 key、scopes、attributes 不进入审计；
- retention 批次上限、固定 cutoff、瞬时/永久错误、Scope 中断；
- PostgreSQL 审计约束失败时变更回滚，以及成功纠正+审计同事务。

真实 PostgreSQL 测试需显式设置 `RUN_POSTGRES_INTEGRATION=1`。ZIO HTTP “我的记忆”端点已完成直接 Routes 契约测试；
仍未完成的是前端治理页面、管理员/租户专用治理 API、生产数据保留期评审、审计表归档策略、连接池饱和和跨主机数据库
切换演练。

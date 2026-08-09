# Embedding 缓存、租户配额与生产调用边界

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 为什么需要独立治理门面

Embedding 同时用于在线 query、离线知识索引和未来 Memory 提炼。只在 HTTP Provider 外面随手加一个 Map 会产生三个问题：

1. 不同租户可能通过正文 hash 共享缓存，形成数据存在性旁路；
2. 并发 Worker 先检查再累加配额，会共同越过限额；
3. 网络重试重复计费，或相同 requestId 被错误绑定到不同正文。

框架因此保留原始 `EmbeddingService` 作为 Provider SPI，并增加 `embedScoped` 生产入口。`KnowledgeIndexer` 与
`DefaultRetriever` 已统一走该入口；`GovernedEmbeddingService` 的裸 `embed/embedDetailed` 会明确失败，防止装配了治理门面后
又无意绕过 tenant scope。

## 2. 可信请求上下文

```scala
val context = EmbeddingRequestContext(
  tenantId = TenantId("tenant-a"),
  purpose = EmbeddingPurpose.Query,
  requestId = "search-request-20260715-001"
)

service.embedScoped(context, Chunk("黄帝内经 阴阳"))
```

- `tenantId` 必须来自认证或业务任务，不来自模型；
- `purpose` 区分 Query、Indexing、Memory，便于后续成本和配额分层；
- `requestId` 是配额预留幂等键，网络重试必须复用；同 ID 不同正文会被拒绝。

知识索引使用 `documentId + ingestionId` 构造稳定请求 ID；在线 Retriever 可在 `RetrievalScope.requestId` 显式传入业务请求
ID，缺失时框架生成随机 UUID。

## 3. 缓存键与去重

缓存键由下列字段组成：

```text
tenantId + purpose + provider + model + dimension + cacheKeyVersion + SHA-256(exact UTF-8 text)
```

正文不会写入键。tenantId 与 purpose 都是键的一部分，因此相同文字不会在不同租户、`Query` / `Indexing` / `Memory`
用途间共享。后者尤其重要：带 query/document instruction 的模型会为同一正文产出不同向量。改变模型、维度、用途指令或
文本预处理算法时必须改变相应契约字段，旧向量不会被误用。

一次请求中的重复正文先按键去重，Provider 只接收首次出现的文本；结果再按原始位置重组，所以输出数量与顺序保持不变。
只有 cache miss 会进入 Provider 和配额预留。Provider 返回数量或维度漂移会整体失败，不写入不完整缓存。

缓存默认 TTL 为 7 天。`CacheFailureMode.FailOpen` 只允许缓存故障降级为直接调用 Provider；配额 Store 永远 fail-closed。
安全或成本门禁不能因为缓存不可用而被跳过。

## 4. 配额语义

`EmbeddingQuotaPolicy` 当前提供窗口内三个调用前可确定的硬上限：

- Provider request 次数；
- 去重后的文本条数；
- Unicode code point 字符数。

字符数不是伪造的 token usage。真实 token 仍以 `EmbeddingBatchResult.usage` 为准，并进入成本观测；字符配额的作用是在付费
HTTP 调用前提供确定性上界。

`EmbeddingQuotaStore.reserve` 必须原子完成“读取当前窗口—检查三个上限—保存 requestId/hash—累加”。同
tenant+requestId+requestHash 重试返回既有预留，不重复扣减；同 requestId 不同 hash 返回冲突。

## 5. ZLayer 装配

单进程测试和开发可使用：

```scala
val governed = ZLayer.make[EmbeddingService](
  rawEmbeddingProviderLayer,
  EmbeddingCacheStore.inMemory,
  EmbeddingQuotaStore.inMemory,
  GovernedEmbeddingService.layer(
    quotaPolicy = EmbeddingQuotaPolicy(
      window = 1.day,
      maxRequests = 10_000,
      maxTexts = 100_000,
      maxCharacters = 100_000_000
    )
  )
)
```

若 ZLayer 图同时需要“原始 Provider”和“治理后的 EmbeddingService”，宿主应通过小型包装类型区分两者，避免同类型服务在
环境中相互遮蔽。

多 Worker 生产部署使用宿主的同一个 `DataSource`：

```scala
val governed = ZLayer.make[EmbeddingService](
  rawEmbeddingProviderLayer,
  PostgresAgentPersistence.embeddingGovernance,
  GovernedEmbeddingService.layer(
    quotaPolicy = EmbeddingQuotaPolicy(window = 1.day, maxRequests = 10_000)
  )
)
```

`PostgresEmbeddingCacheStore` 使用 `REAL[]` 而不是 pgvector，因为缓存只做完整键等值读取，并且同表需要容纳不同
Provider 的不同维度。`PostgresEmbeddingQuotaStore` 则用窗口行 `FOR UPDATE` 串行化同租户同窗口的检查和累加。
Provider HTTP 调用永远发生在这两个数据库短事务之外。

## 6. PostgreSQL 原子性与清理

V001/V003 演进后包含三张治理表：

| 表 | 作用 | 关键不变量 |
|---|---|---|
| `agent_embedding_cache` | 精确向量缓存 | 完整键含 tenant/purpose/model/dimension/version/hash，数组长度必须等于 dimension；升级前行标为 `legacy` 并安全失效 |
| `agent_embedding_quota_windows` | 窗口内三项确定性用量 | `(tenant, window_millis, window_start)` 是锁和计数单位 |
| `agent_embedding_quota_reservations` | requestId/hash 幂等账本 | `(tenant, request_id)` 唯一，与窗口计数同事务提交 |

配额 reserve 的事务顺序为：确保窗口存在、锁定窗口、读取/插入幂等记录、检查硬上限、累加计数。超限会回滚刚插入
的 reservation；相同 ID/hash 重试不累加，不同 hash 明确失败。窗口长度属于复合键，因此一分钟策略和一天策略不会
错误共享计数。

两个 Store 都提供有界清理：`purgeExpired(now, limit)` 和 `purgeWindows(endedBefore, limit)` 使用稳定顺序与
`FOR UPDATE SKIP LOCKED`，可由多个维护 Worker 并行领取。删除 quota window 会通过外键级联释放 reservation，避免
requestId 永久占用。缓存读取不更新 last-access，防止热点 key 带来写放大。

## 7. 已验证能力与剩余生产边界

`EmbeddingCacheStore.inMemory` 与 `EmbeddingQuotaStore.inMemory` 是并发正确的参考实现，只适合测试和单实例。多 Worker
部署应使用已经提供的 PostgreSQL Adapter。真实 PostgreSQL 16 Testcontainers 已验证：Flyway SQL、批量 REAL[]
编解码、跨 Store 实例缓存命中、tenant 隔离、并发 Worker 只有一个通过硬配额、幂等冲突不计费、窗口清理级联释放
requestId。执行命令见 [testing.md](testing.md)。

仍未完成 Redis Adapter、命中率/节省费用专项指标、真实生产数据容量基准、跨数据库故障切换和长时间 soak。配额只限制
调用前可知的请求/文本/字符数，不能替代 Provider 返回后的 token/cost 预算；两者都需要保留。

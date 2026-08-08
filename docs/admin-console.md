# 管理 API 与运维控制台

> 状态：当前
> 事实来源：`modules/agent-core/src/main/scala/com/zyblw/agent/admin/`、
> `modules/agent-zio-http/.../AdminHttpApi.scala`、`modules/agent-dashboard/`、
> `agent-postgres` 的 `V002__zyblw_agent_admin_surface.sql`

框架提供一个可选的管理 API 子面（`/api/v1/admin/**`）与一个消费它的浏览器端控制台
（`modules/agent-dashboard`）。设计动机与权衡见
[ADR 0017](architecture/0017-agent-dashboard-architecture.md)；本文说明如何装配与使用。

管理面**默认不存在**。宿主不装配管理路由时，控制台无法工作，`/api/v1/admin/**` 全部返回 404。这是有意的：
管理面能读取跨租户聚合并改变部署行为。

## 1. 装配

管理路由是一组普通的 `Routes`，与业务路由用 `++` 合并即可。每项能力都是 `Option`：只装配你真正需要的，
未提供的能力不挂载路由，控制台会据此隐藏对应页签。

```scala
import com.zyblw.agent.admin.*
import com.zyblw.agent.http.*
import zio.*

val adminApi = new AdminHttpApi(
  AdminCapabilities(
    runs = Some(runDirectory),
    runEvents = Some(runEventAdmin),
    config = Some(runtimeSettings),
    ops = Some(opsAdmin),
    knowledge = Some(knowledgeAdmin),
    evals = Some(evalTrends),
    models = Some(modelAdmin),
    observability = observabilityLinks
  ),
  contexts = requestContexts
)

val allRoutes = agentApi.routes ++ adminApi.routes
```

`contexts` 与业务路由使用同一个 `AgentRequestContextResolver`。框架不自带认证中间件：由宿主决定 token
是 JWT、opaque token 还是网关注入的头。

### 1.1 各能力的适配器

| 能力 | Layer | 依赖 |
| --- | --- | --- |
| `runs` | `PostgresRunDirectory.layer` | `DataSource` |
| `runEvents` | `RunEventAdminService.layer` | `AgentRuntime` + `DurableRunEventStream` |
| `config` | `RuntimeSettingsService.layer(baseline, topK, minScore, rerank)` | `RuntimeOverrideStore` |
| | `PostgresRuntimeOverrideStore.layer` | `DataSource` |
| `ops` | `PostgresOpsAdmin.layer` | `DataSource` + `RunCommandStore` |
| `knowledge` | `KnowledgeAdminLive.layer()` | 索引清单、索引存储、Embedding、向量库、Reranker、摄入服务、`IngestionJobStore`、`RetrievalPolicySource` |
| | `PostgresIngestionJobStore.layer` | `DataSource` |
| `evals` | `EvalTrendReaderLive.layer(tracked)` | `EvalTrendStore` |
| `models` | `ProviderRegistry.layer(registrations)` | `ChatModel` |
| | `ModelCatalogLive.layer(priceBook, embedding)` | `ProviderRegistry` |
| | `ModelAdminLive.layer()` | `ProviderRegistry` + `ModelCatalog` + `ModelPolicySource` |

`RunDirectory` 另有内存实现，适合单进程开发与测试；它不做跨副本聚合。

`RunEventAdminService` 不需要新的存储 Adapter：它复用业务侧已经装配的 `DurableRunEventStream`，后者从
`AgentRuntime` 的权威状态/事件读取路径取数。因此内存与 PostgreSQL 部署都能装配它，区别只在于内存部署里
「跨副本续传」退化为「跨连接续传」。`DurableRunEventStreamConfig.pollInterval` 决定运行中 Run 的事件延迟与
数据库压力，连接池较小的部署应调大它。

`KnowledgeIndexDirectory` 需要一个可枚举的清单来源。`PostgresAgentPersistence.knowledge` 与
`migratedKnowledge1536` 已经把 PostgreSQL 目录随知识库一起提供，`KnowledgeIndexDirectory.inMemoryKnowledge`
则用于本地开发。只装配了自定义 `KnowledgeIndexStore` 的部署可以使用显式空实现挂载路由，但控制台的文档列表会
**永远为空**——它明确返回空页而不是报错，以便部署能先跑起来。

`EvalTrendReaderLive.layer` 接受部署声明跟踪的趋势线清单，并在**装配阶段**校验它们。一个拼错的 suiteId
会让启动失败，而不是让控制台在某个页签上得到一个无法解释的 400。

### 1.2 配置覆盖的传播

`RuntimeSettingsService` 在装配时完成一次加载，此后需要一个刷新 Fiber 才能感知其他副本写入的覆盖：

```scala
ZIO.scoped {
  RuntimeSettingsService.pollingRefresh(15.seconds) *> server
}
```

首次加载失败会让整个 Layer 失败，而不是回退到部署基线。如果覆盖存储不可达，管理员在控制台看到的
「已生效配置」就是错的，静默回退比启动失败更危险。刷新失败只记录并继续重试——存储的短暂不可用不应让所有
副本永久停在最后一次成功的配置上。

### 1.3 让覆盖真正生效

保存一个不被读取的覆盖比没有这个开关更糟。要让工具治理与检索工作点在运行时可调，运行时必须经由策略解析器
而不是启动时固化的配置对象读取它们：

```scala
// 工具治理
AgentRuntimeLive.layer(...) 需要 ToolPolicySource
val toolPolicies = ZLayer.fromFunction((s: RuntimeSettingsService) => s.toolPolicySource)

// 检索工作点
val retrievalPolicies = ZLayer.fromFunction((s: RuntimeSettingsService) => s.retrievalPolicySource)
DefaultRetriever.governedLayer(...)   // 消费 RetrievalPolicySource
RagApplication.governed(...)
```

```scala
// 模型工作点与价格表
val modelPolicies = ZLayer.fromFunction((s: RuntimeSettingsService) => s.modelPolicySource)
AgentRuntimeLive.layer   // 消费 ModelPolicySource
```

不接这些解析器的部署行为不变：三个解析器都有返回部署基线的默认实现（`ModelPolicySource.default` 表示完全
沿用各 Agent 自己的 `modelSettings`，不做任何覆盖，也不估算费用）。代价是控制台上对应的字段会保存成功却不起
作用，因此如果你启用了配置页，就应该把解析器接上。

### 1.4 数据库迁移

`V002__zyblw_agent_admin_surface.sql` 为 `agent_runs` 增加租户、用户与审批等待的**生成列**及配套索引，并新建
`agent_runtime_overrides`、`agent_ingestion_jobs` 两张表。

生成列由 PostgreSQL 在写入时维护，因此运行时的写路径不需要任何改动，也不会出现读模型与权威状态不一致。
代价是加列会重写 `agent_runs`：大表部署需要安排停机窗口。

只装配了 `runs`/`ops` 而不需要配置覆盖与摄入的部署仍然会创建那两张表；它们保持为空，没有运行时开销。

### 1.5 观测深链

外部观测系统的地址由后端下发，而不是前端硬编码：

| 环境变量 | 含义 |
| --- | --- |
| `ZYBLW_AGENT_OBSERVABILITY_LANGFUSE_BASE` | Langfuse 站点根地址 |
| `ZYBLW_AGENT_OBSERVABILITY_LANGFUSE_PROJECT` | Langfuse 项目 ID |
| `ZYBLW_AGENT_OBSERVABILITY_GRAFANA_BASE` | Grafana 站点根地址 |
| `ZYBLW_AGENT_OBSERVABILITY_GRAFANA_UID` | 目标 dashboard UID |
| `ZYBLW_AGENT_OBSERVABILITY_OTLP` | OTLP 端点，仅用于在界面上显示当前导出目标 |

未配置的链接不会渲染出一个指向 `undefined` 的按钮。

Langfuse 深链依赖 traceId 与 runId 之间可推导的关系。默认推导规则要求 runId 是 32 位十六进制（即 UUID
去掉连字符后正好构成 W3C traceId）；不满足时不生成链接，而不是生成一个必然 404 的地址。使用其他 ID 方案的
部署可以声明自己的推导规则。

### 1.6 模型治理

#### 凭据是引用式的，管理台永不接触 Key

Provider 凭据只在装配阶段从环境变量或宿主的密钥后端解析。控制台看到的是
`ModelCredentialStatus(present, reference)`——「凭据是否就位」与「它来自哪个引用」，例如
`env:DEEPSEEK_API_KEY`。**没有任何端点接收、返回或存储 Key 的值。**

这不是能力上的欠缺，而是一条边界。把 Key 写进业务库会连带承担静态加密、轮换、备份脱敏与 `pg_dump` 泄漏面，
而这些都不是 Agent 框架该解决的问题。需要集中管理凭据的部署应该实现自己的解析逻辑对接 Vault 或 K8s Secret，
框架只要求它在装配时给出一个已解析的配置对象。

#### 由此推出的能力边界

| 操作 | 是否可运行时完成 | 原因 |
| --- | --- | --- |
| 在**已注册**的 Provider / 模型之间切换 | 是，立即生效 | `RoutedChatModel` 本来就按请求里的 provider 名路由 |
| 调整温度与输出上限 | 是，立即生效 | 采样参数逐次请求读取 |
| 新增一个**全新** Provider | 否，需重启 | 需要新凭据与新 HTTP 客户端，两者都在装配期固化 |
| 更换 Embedding 模型 | 否，需迁移 + 全量重新摄入 | 见下 |

切换走的是配置覆盖的写入路径（`PUT /api/v1/admin/config` 的 `modelProvider` / `modelName` /
`modelTemperature` / `modelMaxOutputTokens`），而不是一个独立的写端点，因此它复用同一套乐观锁、审计历史与跨
副本刷新。为模型再造一套版本化写入会产生两份可能互相矛盾的配置事实。

覆盖是**稀疏**的：只改 `modelProvider` 不会把模型名抹成 Provider 默认值。四项字段的基线渲染为
`(各 Agent 定义)`，因为部署层面并不存在唯一的基线模型——编一个出来会让运维以为所有 Agent 本来都在用它。

#### 装配顺序

依赖链看起来像个循环，其实不是。关键在于 `ModelCatalog` 只从已装配的 `ChatModel` 派生，自己不读工作点：

```
ChatModel → ProviderRegistry → ModelCatalog → RuntimeSettingsService → ModelPolicySource → ModelAdminService
```

`ModelCatalog` 与 `ModelAdminService` 因此是**两个** trait 而不是一个。合成一个会形成真正的循环：配置服务需要
目录做写入校验，而展示层需要配置服务才能报告当前生效模型。

`ProviderRegistry` 要求装配方**显式声明**每个 Provider 的部署默认模型与凭据引用，而不是让目录靠类型分支去猜：

```scala
val registry = ProviderRegistry.layer(
  List(
    ProviderRegistration.openAICompatible(deepSeekModel, deepSeekConfig,
      ProviderPresets.deepSeekCredentialReference),
    ProviderRegistration.anthropicMessages(claudeModel, claudeConfig)
  )
)
```

`ChatModel` SPI 刻意不暴露配置——一个模型实现不该被迫公开它的 endpoint 和密钥字段。靠反射或类型分支去猜只对框架
内置的四个适配器有效，自定义 `ChatModel` 会静默退化成「没有默认模型、凭据状态未知」；显式声明对所有实现一视同仁。

注册表在装配期**双向**校验声明与真实路由拓扑必须相等。少声明一个可路由 Provider 会让运维被告知一个明明可用的
Provider「未注册」（因为目录同时是写入校验依据）；多声明一个不可路由的，会让管理台展示一个选中后必然
`ProviderNotFound` 的选项。两种偏差都在启动时快速失败。

要让某个 Provider 成为可切换的故障切换目标，就必须在启动时注册它，即使它平时不承担流量。这正是正确的准备方式：
备用 Provider 的凭据与连通性应该在事故**之前**就用探活验证过。

#### 写入前按目录校验

`GET /api/v1/admin/models` 返回的目录同时是**写入校验的依据**。一份指向未注册组合的覆盖一旦落库，进程每次
重启都会重新加载它，把一次下拉框错误变成持续的全线 `ProviderNotFound`，而控制台会显示「保存成功」。因此：

- 未注册的 Provider 名被拒绝，错误消息列出可用名称；
- 模型名按**生效 Provider** 校验，不是按全局模型名集合——否则「Provider A 的模型名配到 Provider B 上」这种
  必然失败的组合会通过；
- **未装配 `ModelCatalog` 的部署无法写入任何模型覆盖**。默认 `ModelCatalog.empty` 是 fail-closed 的：没有目录
  就没有依据判断一个 Provider 名可否路由，放行的代价远大于拒绝。工具与检索覆盖不受影响。

#### Embedding 模型不可在运行时切换

控制台以**只读**方式展示 Embedding 的 provider / model / dimension，并直接展示后端给出的
`immutableReason`。它没有切换入口，这是有意的：向量维度由 Flyway 迁移固定，而一份索引里的向量只能与生成它的
模型比较。一个能保存成功却让整个知识库召回质量崩塌的开关，比没有这个开关危险得多。真正需要更换模型的部署必须
执行新维度迁移并全量重新摄入。

当 `dimension` 与 `indexDimension` 不一致时控制台会显著告警——这个状态意味着摄入会在写入向量前失败。

#### 连通性探活

`POST /api/v1/admin/models/probe` 对一个已注册组合发一次最小调用，返回成功与否、耗时、token 用量和稳定失败
码。它要求 `agent:admin:debug` 而不是读权限，因为它会产生真实 Provider 费用。

结果**不含模型输出正文**。回显输出会把一个只需要 debug scope 的端点变成一个可以向任意 Provider 提问并读回答案
的通道。失败码是框架分类而非 Provider 原始响应。内置适配器已经不把原始响应正文写进错误；管理服务仍然只读错误
类型与 `ErrorCategory`，不读 `message`，因为自定义 `ChatModel` 并不自动获得同样的脱敏保证。

探活刻意**不叠加运行时模型覆盖**。它回答的是「我切到这个组合会不会通」，而不是「当前生效组合通不通」——用生效
工作点改写目标会让运维在切换前根本无法验证目标本身。

目标组合不在目录中时直接返回失败，不发网络请求。放行会让一次拼写错误变成一次真实计费调用，而且失败原因还会被
Provider 的「模型不存在」掩盖，看不出其实是本地目录里就没有。目录拒绝会进一步区分
`provider-not-found` 与 `model-not-found`，运维可以判断应该修路由装配还是模型声明。

探活有独立于适配器自身超时的外层硬超时（默认 20 秒），超时会中断底层调用，因此不会留下一个继续计费的后台请求。

内置 Chat Provider 通过 `ModelHttpFailure` 按 HTTP 状态稳定分类：401/407 是认证失败，403 是授权失败，408 是超时，
409 是冲突，429 是限流，5xx 是暂时不可用，其余 4xx 是无效请求。分类只读取状态码；Provider code/type 只有满足
短、低基数字符集时才进入诊断。错误正文、`message`、网关 HTML 与任何疑似 Key 都不会进入框架错误或探活响应。

#### 成本估算

`UsageSummary.estimatedCost` 只有在部署声明了 `ModelPriceBook` 时才非零。框架**不内置**任何厂商价格：价格随
时间、合同与区域变化，把一份猜测的价目表编译进框架只会让成本看板显示一个看起来精确但其实错误的数字，而运维
没有任何线索知道它错了。缺失条目估算为零，与 `addModel` 既有的「未知费用保持零，不伪造账单事实」一致。

价格表有两个容易踩错的计费口径，框架已经处理：`cachedInputTokens` 是 `inputTokens` 的**子集**，两个字段各自
乘单价会把缓存命中部分收两次费；`reasoningOutputTokens` 同样是 `outputTokens` 的子集，主流厂商按普通输出
token 计费，为它单独计价就是重复计费。

价格表不允许混用货币：`estimatedCost` 是单一标量，混币会把不可比的金额直接相加，因此这一点在构造时就被拒绝。

价格表要传给**两处**——`ModelCatalogLive.layer(priceBook = ...)` 与 `RuntimeSettingsService.layer(priceBook = ...)`。
装配时必须传同一个 `val`：前者决定管理台展示的单价，后者决定实际写入 `estimatedCost` 的单价，传成两张不同的表
会让「界面上的单价」与「账单上的单价」长期不一致，而这种偏差没有任何报错能提示。类型上消除它需要把价格表提升
为独立的环境服务，那会改动 `ModelPolicySource.prices` 这个已公开的契约，因此当前用文档和 Scaladoc 约束。

## 2. 授权

管理端点一律要求显式 scope，缺失即拒绝。管理面不能复用业务侧的「归属即可读」规则：归属规则保护单个 Run 的
所有者视角，而管理台看到的是整个部署。

| scope | 覆盖 |
| --- | --- |
| `agent:admin:read` | Run 目录、单 Run 实时事件流、队列积压、有效配置、评测趋势、索引清单、摄入任务、模型目录 |
| `agent:admin:write` | 工具白名单、审批策略、死信重排、索引退役、模型切换（蕴含 read） |
| `agent:admin:debug` | 检索沙盒、文档摄入、模型探活（**不被 write 蕴含**） |

`debug` 单独存在是因为这三个操作会调用外部 Provider 并产生真实费用。让一个能改配置的账号
顺带获得无限量的 Provider 调用权限，是把两类不同的风险混为一谈。

建议的授予方式：值班与监控只给 `read`；变更审批人给 `write`；`debug` 只在排查检索质量时临时授予。

## 3. 端点

完整清单见 [ADR 0017 第 5 节](architecture/0017-agent-dashboard-architecture.md)。几个使用上的要点：

**Run 目录与知识库清单使用 keyset 游标，不是 OFFSET。** 响应中的 `nextCursor` 为 `null` 表示到底。Run 会在
翻页过程中持续更新，OFFSET 会让同一条记录重复出现或被整页跳过。游标是不透明文本，客户端只应原样回传。

游标内的时间戳使用**微秒**，与 `TIMESTAMPTZ` 的存储精度一致。这不是可有可无的细节：游标精度一旦低于排序列
精度，被截断的游标就会落在同一毫秒内所有行之前，行值比较会把整个毫秒区间连同游标行本身排除，下一页整段消失。
自定义 Adapter 必须让游标精度不低于排序列，并且用带亚毫秒位的时间戳测试翻页——毫秒对齐的夹具无法暴露该缺陷。

**配置写入使用 compare-and-set。** `PUT /api/v1/admin/config` 必须带上从 `GET` 读到的 `expectedVersion`。
两个管理员同时编辑时，后提交的一方收到 409 并必须重新加载，而不是静默覆盖对方的改动。请求体是**稀疏补丁**：
缺失的字段表示沿用部署基线，因此删除一项覆盖与从未设置过它完全等价。

**文档摄入是异步的。** `POST /api/v1/admin/knowledge/ingestions` 返回 202 与一个任务 ID，正文是原始字节而
不是 base64 JSON。解析 PDF 并写入向量可能耗时数分钟，把它做成同步端点意味着一个必然超时的 HTTP 连接。
后台 Fiber 挂在应用级 Scope 而不是请求 Scope 上——挂在请求 Scope 会让任务在响应写出的同一刻被中断，控制台
永远只能看到 `Queued`。

**索引退役需要前置条件。** `expectedActiveVersion` 防止在并发重建期间退役掉刚刚发布的新版本。

**实时事件流是可续传的 SSE，且只发低敏投影。** `GET /api/v1/admin/runs/{runId}/events/stream` 要求
`agent:admin:read`。它不是业务侧 `GET /api/v1/runs/{runId}/events/stream` 的别名：管理投影
（`AdminRunEventView`）是显式 allow-list，删除了 `output` 与 `message`，只保留状态、步数、用量、工具名与
审批元数据。设计理由见 [ADR 0017 第 5.1 节](architecture/0017-agent-dashboard-architecture.md)。

续传用标准的 `Last-Event-ID` 头，值就是事件 `sequence`；缺失或 `-1` 表示从头读取。**不存在的 Run 与超过当前
最后序号的游标返回 4xx**，不会先回 `200 OK` 再发 `stream_error`——否则客户端无法区分「Run 不存在」和「连接
临时中断」。运行中的 Run 由服务端按 `DurableRunEventStreamConfig.pollInterval` 轮询；Run 进入终态或等待
审批时流会正常结束，这不是错误，客户端不应据此重连。

装配该能力需要在 `AdminCapabilities.runEvents` 注入 `RunEventAdminService`，它依赖 `AgentRuntime` 与
`DurableRunEventStream`。未注入时路由不挂载，`capabilities.runEventStream` 为 `false`，控制台不显示调试器。

**模型切换没有独立的写端点。** 它复用 `PUT /api/v1/admin/config`，因此同样受 CAS 保护。`GET
/api/v1/admin/models` 是只读目录兼写入校验依据；`POST /api/v1/admin/models/probe` 是付费探活。详见 1.6。

## 4. 控制台

控制台是纯浏览器端应用，不持有状态、不做服务端数据获取、不直连数据库。使用与部署见
[`modules/agent-dashboard/README.md`](../modules/agent-dashboard/README.md)。

页签可见性由 `capabilities` 决定。未装配能力对应的页签不会显示，而不是显示一个只会返回 404 的空面板。

控制台与后端不同源时，宿主需要允许控制台来源的 CORS 预检，否则浏览器会在 `capabilities` 探测阶段就失败。

Run 列表只展示元数据。用户输入、模型输出、工具参数和审批理由属于业务数据，跨租户的运维界面不应成为它们的
导出通道；需要查看正文时请走业务侧的 `inspection` 端点及其归属授权。

选中 Run 后可打开**实时事件流调试器**。它有三个刻意的行为：

- **连接由人工显式点击启动**，不随选中行自动连接。每条连接在服务端都是一轮持续的数据库轮询，让点选列表
  隐式创建它们会把一次浏览变成对连接池的压力。
- **默认只读取最近 200 条**，而不是整个 Run 的历史；「从头读取」是单独的按钮。浏览器内存只保留最后 500 条。
- **断线按最后确认的 `sequence` 指数退避重连**，主动暂停与服务端正常结束（终态 / 等待审批）不重连。
  序号不连续时停止显示并报错，而不是把跳过的事件当成没发生过。

调试器使用 `fetch` 而不是 `EventSource`：管理 token 必须放在 `Authorization` 头，而 `EventSource` 只能把
凭据放进 URL，那会让 token 进入网关与浏览器历史的访问日志。

## 5. 未实现

- **单 Run 的 Step Timeline 与 Tool Ledger**：需要消费业务侧 `inspection` 端点，涉及一层额外授权判断。
- **PDF 双栏渲染与 bbox 叠加**：检索沙盒已返回完整页码与 bbox 几何，控制台目前以列表展示坐标。
- **跨 Run 的成本聚合**：Run 目录已返回单个 Run 的 token 与费用明细，尚无聚合端点。
- **嵌入式部署**：把静态资源打包进 `agent-zio-http` 托管于 `/dashboard` 尚未实现。
- **按 Agent 粒度的模型覆盖**：当前模型覆盖是部署级的，会同时改写所有 Agent。按 Agent 覆盖需要覆盖层带上
  Agent 标识，并决定它与部署级覆盖的优先级，属于契约变更而不是加一个字段。
- **Provider 降级链**：目前只能切到单一目标组合。「主 Provider 失败后自动降级到备用」需要运行时具备重试路由，
  而不只是一个可变的工作点；把它做成配置项会让一次限流变成对所有 Agent 静默改变模型。
- **新增 Provider**：装配期以外无法引入新 Provider——它需要新凭据与新 HTTP 客户端。管理台能切换的始终是
  已注册组合的子集。

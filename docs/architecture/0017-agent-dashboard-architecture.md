# ADR 0017: agent-dashboard 前端控制台架构与管理 API 边界

> 状态：Accepted
> 事实来源：`modules/agent-core/src/main/scala/com/zyblw/agent/admin/`、
> `modules/agent-zio-http/src/main/scala/com/zyblw/agent/http/AdminHttpApi.scala`、
> `modules/agent-dashboard/src/`、`modules/agent-postgres` 的 `V002` 迁移

本 ADR 定义框架内置运维控制台（`agent-dashboard`）与其依赖的管理 API 子面的边界、授权模型与装配方式。
文中每一节明确区分**已实现**与**计划中**；实现状态以源码、测试与迁移为准。

## 1. 边界

- **不是业务 UI**。终端用户面向的对话界面、Copilot 侧边栏等由业务团队自行开发。控制台服务于框架开发者与
  运维（Developer & SRE Console）。
- **不是必需组件**。后端不装配管理路由时控制台完全无法工作。管理面能读取跨租户聚合并改变部署行为，
  因此必须由宿主显式启用并授权。
- **只经 HTTP**。控制台不直连数据库、不持有服务端状态、不做服务端数据获取。全部数据来自
  `/api/v1/admin/**`。
- **不展示业务正文**。Run 目录、死信清单和索引清单只含元数据。用户输入、模型输出、工具参数和审批理由
  属于业务数据，跨租户的运维界面不应成为它们的导出通道。

## 2. 管理 API 是独立的 Beta 子面

管理路由挂在 `/api/v1/admin` 下，与业务 Run/Memory API 共享同一主版本和同一套错误映射，但在契约上独立：
它服务于运维界面并随管理台功能演进，**不进入** `AgentHttpContract` 的稳定 OpenAPI 承诺。

这个划分是有意的。管理视图的形状取决于界面需要展示什么，若把它纳入稳定契约，每次管理台增加一列都会变成
一次 minor 版本决策。

## 3. 授权模型

管理面不能复用业务侧 `RunAuthorization` 的「归属即可读」规则：归属规则保护单个 Run 的所有者视角，
而管理台看到的是整个部署。因此管理端点一律要求显式 scope，缺失即拒绝。

| scope | 覆盖操作 | 为什么单独存在 |
| --- | --- | --- |
| `agent:admin:read` | Run 目录、队列积压、有效配置快照、评测趋势、索引清单、模型目录 | 泄漏面最小，可以发给值班与监控 |
| `agent:admin:write` | 工具白名单、审批策略、死信重排、索引退役、模型切换 | 能改变部署行为，必须单独授予；蕴含读权限，因为改配置前必须先看到当前配置 |
| `agent:admin:debug` | 检索沙盒、文档摄入、模型探活 | 会触发真实 Provider 调用并产生费用，因此**不被写权限蕴含** |

框架不自带认证中间件。身份来自宿主的 `AgentRequestContextResolver`，与业务路由使用同一个解析器。

## 4. 能力探测取代路径试探

`GET /api/v1/admin/capabilities` 报告后端实际装配了哪些管理能力，以及外部观测系统的深链配置。

未提供的能力不挂载路由，请求自然得到 404。这比「挂载路由再返回 501」更好：管理台的能力探测有唯一事实来源，
而不是散落在每个端点的运行时错误里；前端也不需要靠对每个端点试探性请求来推断能力，那既慢又会在服务端日志
里制造一批无意义的错误。

外部观测地址（Langfuse、Grafana、OTLP 端点）由后端下发而不是前端硬编码：同一份前端构建会被部署到开发、
预发和生产，各自的观测后端不同。由后端返回链接模板可以让一次部署配置同时纠正所有页面的跳转目标。

## 5. 端点清单（已实现）

| 端点 | 方法 | scope | 说明 |
| --- | --- | --- | --- |
| `/api/v1/admin/capabilities` | GET | read | 能力声明与观测深链 |
| `/api/v1/admin/runs` | GET | read | Run 目录，keyset 游标分页 |
| `/api/v1/admin/runs/overview` | GET | read | 按状态聚合的部署总览 |
| `/api/v1/admin/runs/{runId}/events/stream` | GET | read | 单 Run 低敏耐久事件 SSE，可按 `Last-Event-ID` 续传 |
| `/api/v1/admin/config` | GET | read | 基线 / 覆盖 / 生效三列快照 |
| `/api/v1/admin/config` | PUT | write | 以 `expectedVersion` 做 CAS 写入 |
| `/api/v1/admin/config/history` | GET | read | 覆盖变更审计历史 |
| `/api/v1/admin/ops/queue` | GET | read | 队列积压快照 |
| `/api/v1/admin/ops/dead-letters` | GET | read | 死信清单（不含命令正文） |
| `/api/v1/admin/ops/dead-letters/{id}/retry` | POST | write | 人工重排 |
| `/api/v1/admin/knowledge/documents` | GET | read | 索引版本清单 |
| `/api/v1/admin/knowledge/documents/{id}/retire` | POST | write | 以 active 版本为前置条件退役 |
| `/api/v1/admin/knowledge/retrieve` | POST | debug | 检索沙盒 |
| `/api/v1/admin/knowledge/ingestions` | POST | debug | 提交异步摄入，返回 202 |
| `/api/v1/admin/knowledge/ingestions` | GET | read | 摄入任务列表 |
| `/api/v1/admin/knowledge/ingestions/{id}` | GET | read | 单个摄入任务 |
| `/api/v1/admin/evals/suites` | GET | read | 部署声明跟踪的趋势线 |
| `/api/v1/admin/evals/trend` | GET | read | 单条趋势线历史 |
| `/api/v1/admin/models` | GET | read | 已注册 Provider/模型目录、凭据状态与价格覆盖 |
| `/api/v1/admin/models/probe` | POST | debug | 对已注册组合做一次付费连通性探活 |

Run 目录使用 keyset 游标而不是 OFFSET：Run 会在翻页过程中持续更新，OFFSET 会让同一条记录重复出现或被
整页跳过。

### 5.1 实时事件流为什么是独立的管理端点

业务侧已有 `GET /api/v1/runs/{runId}/events/stream`，但管理台不复用它，原因是两者的授权与投影都不同：

- **授权不同**。业务端点用「归属即可读」，管理台看到的是跨租户视图，因此必须走显式 `agent:admin:read`。
  让控制台去调业务路由，等于要求它先持有某个租户的业务身份，那会把运维凭据和业务凭据混在一起。
- **投影不同**。业务事件允许携带最终输出与安全消息；管理事件不允许。`AdminRunEventView` 是显式 allow-list，
  只保留结构化状态、步数、用量、工具名与审批元数据，删除 `output` 与 `message`。跨租户运维界面不应成为
  业务正文的导出通道。

**Run 与游标在响应头发出前校验。** `RunEventAdminService.open` 返回 `IO[AgentError, ZStream[...]]` 而不是
直接返回流：不存在的 Run 和超前游标必须成为正常的 4xx，而不能退化成 `200 OK` 之后才发一条 `stream_error`——
后者会让控制台把「Run 不存在」显示成「连接中断，正在重试」，并触发一轮毫无意义的重连。

**续传语义由 `Last-Event-ID` 承担。** SSE 的 `id` 字段就是事件 `sequence`，`-1` 表示从头。底层
`DurableRunEventStream` 读取的是随状态事务提交的权威事件，因此续传可以落到任意 HTTP 实例，不依赖创建 Run
的 Worker。它对每一页做严格连续性校验（`cursor + i + 1`），存储损坏或分页遗漏会成为显式错误，而不是让控制台
悄悄跳过审批或终态事件。

**终态与人工暂停是本次连接的静止边界。** `WaitingForApproval`、`Suspended` 和各终态不会再自行产生事件，
流正常结束，控制台显示「已追平」而不是维持一条长期轮询数据库的空连接。

## 6. 运行时配置覆盖

管理台可以修改一份**有界白名单**内的配置项，覆盖持久化在数据库并在多副本间以秒级延迟传播。

设计上的三个关键决定：

- **白名单而非自由 key-value**。配置面一旦变成自由表单，就等于给管理台开了一个绕过编译期契约的后门，
  任何拼写错误都会静默失效。
- **稀疏补丁而非全量值**。缺失表示「沿用部署基线」，因此删除一项覆盖与从未设置过它完全等价。
- **只有真正会生效的项才可覆盖**。每一项都标注生效边界：`Immediate`（下次工具执行或检索即生效）、
  `NextRun`（既有 Run 已把该值冻结进状态）、`Restart`（装配时固化为不可变资源，因此**不接受覆盖**）。
  一个保存成功却毫无效果的开关比没有这个开关更糟。

为让工具治理、检索工作点与模型路由真正可在运行时调整，运行时读取路径改为经由三个同步解析器：
`ToolPolicySource`、`RetrievalPolicySource` 与 `ModelPolicySource`。它们返回裸值而不是 `UIO`，因为规划工具批次、判断审批和过滤检索得分都发生在纯
表达式里；把这些读取变成效果会迫使纯函数改写成 `ZIO`，却换不来额外保证——读取一个不可变配置引用本来就
是无副作用的。覆盖通过替换被引用的不可变值发布，而不是原地修改配置对象。

写入使用 compare-and-set。两个管理员同时编辑时，后提交的一方会收到 409 并必须重新加载，而不是静默覆盖
对方的改动。覆盖表是 append-only 的，因此它同时是配置存储和审计日志——分成两张表会引入「配置写入成功但
审计写入失败」的窗口。

### 6.1 模型治理的能力边界

模型切换**不新增写端点**，它复用第 6 节的覆盖写入路径，因此自动继承 CAS、审计与跨副本刷新。为模型再造一套
版本化写入会产生两份可能互相矛盾的配置事实。

真正的约束来自凭据。管理台永不接触 API Key：凭据只在装配阶段从环境变量或宿主的密钥后端解析，管理面只看到
`present` 与一个像 `env:DEEPSEEK_API_KEY` 的展示引用。把 Key 写进业务库会连带承担静态加密、轮换、备份脱敏
与 `pg_dump` 泄漏面，而这些都不是 Agent 框架该解决的问题。

由此推出一条清晰的边界：**可以在已注册的 Provider 之间热切换，不能热增一个全新 Provider。**后者需要新凭据与
新 HTTP 客户端，两者都在装配期固化。这个边界并不削弱实用性——它恰好覆盖了绝大多数真实运维场景（主 Provider
降级时切换、把某个用途换成更便宜的小模型、调温度），因为 `RoutedChatModel` 本来就按请求里的 provider 名路由。

`ModelCatalog` 同时是**写入校验依据**而不只是展示接口。允许把未注册组合存进覆盖，等于让一次下拉框错误把整个
部署的每一次模型调用变成 `ProviderNotFound`，而管理台会显示「保存成功」。未装配目录的部署因此完全无法写入
模型覆盖：没有目录就没有依据判断一个 Provider 名可否路由，fail-closed 的代价远小于放行。

Embedding 模型是**只读**的。维度由迁移固定，而一份索引里的向量只能与生成它的模型比较，因此换模型等于让整个
知识库的既有向量失去意义。给管理台一个能保存成功却悄悄让 RAG 召回崩塌的开关，比不给这个开关危险得多。

## 7. 数据来源与新增 SPI

管理面查询与运行时权威读写路径职责不同，因此使用独立的窄 trait，而不是给已发布的 Store trait 增加抽象
方法（那会让所有外部实现无法编译）。

| SPI | 位置 | 生产实现 |
| --- | --- | --- |
| `RunDirectory` | `agent-core` | `agent-postgres`；另有内存实现供测试与单进程开发 |
| `RuntimeOverrideStore` | `agent-core` | `agent-postgres` |
| `IngestionJobStore` | `agent-core` | `agent-postgres` |
| `OpsAdminService` | `agent-core` | `agent-postgres`（复用 `RunCommandStore` + 死信查询） |
| `KnowledgeAdminService` | `agent-core` 定义，`agent-rag` 实现 | `agent-rag` |
| `KnowledgeIndexDirectory` | `agent-rag` | 按索引存储装配 |
| `EvalTrendReader` | `agent-core` 定义，`agent-evals` 实现 | `agent-evals` |
| `ModelCatalog` / `ModelAdminService` | `agent-core` 定义，`agent-providers` 实现 | `agent-providers`，从已装配的 `ChatModel` descriptor 派生 |

Run 目录需要按租户、Agent、状态和审批等待过滤，而这些维度原先只存在于 `state_json` 内部。`V002` 迁移把
它们提升为**生成列**：由 PostgreSQL 在写入时维护，运行时代码不需要改变任何写路径，也不会出现读模型与
权威状态不一致。

`agent-core` 不依赖 `agent-rag` 或 `agent-evals`，因此这两个模块的管理视图投影位于各自模块内。HTTP 层只
依赖 core 的 trait，不会把向量检索或评测依赖强加给所有 HTTP 用户。

## 8. 前端技术栈（已实现）

```text
agent-dashboard
├── Next.js 16 (App Router, React 19, TypeScript 5)
├── Tailwind CSS v4 + Lucide Icons
├── TanStack Query v5
└── standalone 输出 / Docker 容器
```

选型上的两点克制：

- **没有图表库**。评测趋势是一条单序列折线，用内联 SVG 实现；一个图表库会带来数十 KB 依赖和一套需要长期
  跟随升级的 API。
- **没有独立状态管理库**。服务端状态由 TanStack Query 承担，连接配置用 `useSyncExternalStore` 订阅浏览器
  存储，其余是组件局部状态。引入第二套状态容器会制造「这份数据的事实来源在哪」的歧义。

线格式类型（`src/types/admin.ts`）与 Scala case class 一一对应，字段名和可空性完全一致，不做「前端更好用」
的改名。一旦线格式与视图模型分叉，后端改字段时 TypeScript 就再也发现不了问题。

## 9. 部署

**已实现**：独立运维模式。控制台作为独立容器部署，运行时可切换后端地址，因此同一份镜像可用于多个环境。
地址保存在 `localStorage`，token 只保存在 `sessionStorage`——管理 token 能改工具白名单和审批策略，让它在
关闭标签页后继续留在磁盘上没有必要的收益。

**计划中**：嵌入式模式（把静态资源打包进 `agent-zio-http` 由静态文件 Handler 托管于 `/dashboard`）。它需要
控制台改为纯静态导出，且需要决定框架 JAR 是否应该包含前端产物——这是一个尚未做出的许可与体积权衡。

## 10. 计划中的能力

以下能力在本 ADR 的目标范围内但尚未实现：

- **单 Run 的 Step Timeline 与 Tool Ledger 视图**。需要消费业务侧的 `inspection` 端点，因此涉及一层额外的
  授权判断：那些视图包含业务正文，不能沿用管理 scope。
- **PDF 双栏渲染与 Bounding Box 叠加**。检索沙盒已返回完整的页码与 bbox 几何信息（`KnowledgeOriginView`），
  但控制台目前以列表形式展示坐标，没有渲染原始 PDF。
- **按 Agent/模型维度的成本大盘**。Run 目录已返回每个 Run 的 token 与费用明细，`ModelPriceBook` 也已让
  `estimatedCost` 变为真实值，但尚无跨 Run 的聚合端点。
- **按 Agent 粒度的模型覆盖**。当前模型覆盖是部署级的：它叠加到所有 Agent 上。真实需求里"只给某个 Agent 换
  模型"同样常见，但那需要一个 Agent 定义的管理面，而框架目前刻意不管理 Agent 定义（它属于业务数据）。
- **Provider 降级链**。`RoutedChatModel` 只做显式路由，没有"主 Provider 失败自动切备用"的自动降级。手工切换
  已经可用，自动降级需要先定义清楚哪些错误可降级以及如何避免在部分失败下抖动。

## 11. 后果

- 管理面成为一个需要独立维护的授权边界。任何新增管理端点都必须显式选择 scope，且付费操作必须归入
  `debug` 而不是搭 `write` 的便车。
- 运行时读取工具策略、检索工作点与模型路由的路径多了一层间接。代价是每次读取一个 `AtomicReference`；收益是
  这些配置可以在不重启的前提下调整。
- `AgentRuntimeLive` 与 `RuntimeSettingsService.layer` 的环境各增加一个服务。直接装配运行时的部署需要补一行
  `ModelPolicySource.defaultLayer` / `ModelCatalog.emptyLayer`，两者都保持原有行为不变。
- `V002` 为 `agent_runs` 增加生成列，这是一次表重写。对 0.x 阶段可接受，但升级说明必须提示大表停机窗口。
- 控制台与管理 API 的演进节奏解耦于稳定 HTTP 契约，代价是控制台需要按 `capabilities` 做能力降级，而不能
  假设后端一定装配了全部适配器。

# AgentApplication、Builder 与业务接入

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-02
>
> 事实来源：对应模块源码、测试与构建定义

`com.zyblw.agent.app` 是 `zyblw-agent-core` 内面向业务宿主的易用装配层。它不创建第二套 Runtime，也不隐藏
Provider、数据库或工具权限，而是把已经稳定的
`AgentRuntime + AgentCommandService + WorkerHost` 组合成一条不容易接错的 ZLayer 路径。

## 1. 为什么需要单独的易用层

底层 SPI 允许高级业务替换每个组件，但手工列出十多个 Layer 容易产生三类错误：

1. 分别创建两套内存 `RunStore/RunCommandStore`，提交的命令永远不会被另一个 Worker 看见；
2. 配置了 Retriever、Guardrail 或 Observer，却使用不读取这些依赖的 Runtime Layer；
3. HTTP 使用耐久命令队列，后台 Worker 却旁路调用同步 `run/resume`，失去 lease fencing。

`AgentApplication` 不用运行时反射或 Service Locator 解决这些问题，而是用 ZIO 环境类型在编译期声明依赖。官方 ZIO 文档把
`ZLayer[-RIn, +E, +ROut]` 描述为从依赖到服务的可组合、可资源化构造；本模块沿用这个语义，而不是在 Builder 中保存全局
可变单例：[https://zio.dev/reference/contextual/zlayer](https://zio.dev/reference/contextual/zlayer)。

## 2. 模块依赖

业务项目至少引入：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core" % zyblwAgentVersion
)
```

再按需增加 Provider、PostgreSQL、ZIO HTTP、RAG 或 OpenTelemetry 模块。`zyblw-agent-core` 不传递 JDBC、厂商 SDK 或 HTTP
依赖，避免一个只做后台 Worker 的项目被迫引入所有外围组件。

长会话的模型辅助压缩使用统一 `ChatModel` SPI，已包含在 core 中；只有真正调用模型时才需要 Provider layer。

## 3. 使用不可变 AgentDefinitionBuilder

```scala
val toolPolicy = ToolPolicyConfig(
  allowedTools = Set(ToolName("search_knowledge")),
  maxCallsPerRun = 12,
  maxCallsPerStep = 3
)

val definition: IO[AgentError.InvalidConfiguration, AgentDefinition] =
  AgentDefinitionBuilder(AgentId("knowledge-assistant"), "知识学习助手")
    .withInstructions("只根据已授权知识片段回答；资料不足时明确说明。")
    .addSystemInstruction("safety.medical", "2026-07", "只提供学习信息，不替代医生诊疗。")
    .addDeveloperInstruction("answer.citations", "2", "关键结论必须附带来源引用。")
    .withProvider(ProviderId("deepseek"))
    .withModel(ModelId("deepseek-chat"))
    .allowTool(ToolName("search_knowledge"))
    .withMetadata("version", "2026-07")
    .buildFor(toolPolicy)
```

Builder 的约束：

- Builder 不可变，公共基线可以安全派生多个 Agent。
- 指令必须显式设置，名称、指令、工具名和 metadata 有硬上限。
- System/Developer 指令块拥有稳定 id/version；重复 ID、非法顺序和总量超限在启动阶段失败。
- `InstructionSet.fingerprint` 可关联 eval/trace/发布版本，不需要记录完整 Prompt。
- metadata 拒绝 `api_key/password/secret/access_token` 等字段；它不是 Secret Store。
- `temperature` 必须是非负有限数，`maxOutputTokens` 必须大于零。
- `buildFor` 要求 Agent 工具集合是全局执行白名单的子集，配置漂移会在启动期以 typed error 失败。
- Provider 的具体能力仍由 Adapter 在请求前校验；Builder 不会把不兼容字段静默删除。

稳定规则与动态上下文的信任边界、Prompt Cache 友好顺序和 token 明细见
[指令、Context 与成本工程](instruction-context-cost.md)。

## 4. 使用 ZIO Config 加载治理参数

`AgentApplicationConfigLoader` 为工具策略和 Worker 调度提供完整的 ZIO Config 描述。它不直接调用 `sys.env`，因此同一份
代码可以由环境变量、系统属性、测试 `ConfigProvider.fromMap`，或宿主提供的 HOCON/YAML 后端驱动：

```scala
val configured: IO[AgentError.InvalidConfiguration, AgentApplicationConfig] =
  AgentApplicationConfigLoader.load()

val configuredLayer: Layer[AgentError.InvalidConfiguration, AgentApplicationConfig] =
  AgentApplicationConfigLoader.layer()
```

默认点分路径是 `zyblw.agent`。源码中的叶子键使用 `snake_case`，因此 ZIO 默认环境 Provider 会稳定生成下列标准
下划线变量，而不会产生 shell 难以 export 的连字符变量：

```bash
ZYBLW_AGENT_TOOL_ALLOWED_TOOLS=knowledge_search,article_draft
ZYBLW_AGENT_TOOL_DENIED_TOOLS=admin_delete
ZYBLW_AGENT_TOOL_MAX_CALLS_PER_RUN=32
ZYBLW_AGENT_TOOL_MAX_PARALLELISM=4
ZYBLW_AGENT_TOOL_DEFAULT_TIMEOUT=30s
ZYBLW_AGENT_TOOL_APPROVAL_POLICY=risk-based
ZYBLW_AGENT_TOOL_RETRY_MODE=idempotent-only
ZYBLW_AGENT_TOOL_RETRY_MAX_ATTEMPTS=3
ZYBLW_AGENT_WORKER_LEASE_DURATION=30s
ZYBLW_AGENT_WORKER_HEARTBEAT_EVERY=10s
ZYBLW_AGENT_WORKER_POLL_EVERY=500ms
ZYBLW_AGENT_WORKER_PARALLELISM=4
```

加载阶段会拒绝：

- 同一工具同时出现在 allow/deny 集合；
- 非正调用次数、并行度、结果上限和超时；
- `heartbeatEvery >= leaseDuration`；
- 非法审批/重试模式、重试退避或 jitter；
- 超长或含控制字符的工具名称。

空 `allowed-tools` 仍表示拒绝全部工具。`idempotent-only` 也不会把普通写工具自动变成幂等工具：只有工具元数据明确声明
可安全重试，并且真实副作用遵守业务幂等键/outbox 契约，Runtime 才会自动重试。

模型 API Key、数据库密码、OTLP/Langfuse 认证头不属于 `AgentApplicationConfig`。这些值必须留在各 Adapter 的 Secret
配置与部署平台 Secret Manager 中，避免整个应用配置被调试打印时泄漏凭据。ZIO Core 配置前端和可替换
`ConfigProvider` 的官方说明见 [https://zio.dev/reference/configuration/](https://zio.dev/reference/configuration/)。

模型辅助 Context 压缩有独立的 `LlmContextCompressorConfigLoader`，默认路径是
`zyblw.agent.context.compression`。它只加载 Provider/模型路由、输入上限、超时、有限修复和降级策略，不读取 API Key：

```scala
val compressionConfig: IO[AgentError.InvalidConfiguration, LlmContextCompressorConfig] =
  LlmContextCompressorConfigLoader.load()
```

## 5. 最小本地 Starter

`inMemoryDefaults` 只适合教程和测试。它仍然经过异步 Start 命令和 WorkerHost，但进程退出会丢失全部状态：

```scala
val appConfig = AgentApplicationConfig(toolPolicy = toolPolicy)

val localLayer: URLayer[
  ChatModel & RegisteredToolRegistry,
  AgentApplication.Services
] = AgentApplication.inMemoryDefaults(
  WorkerId("local-worker-1"),
  appConfig
)

val program = for
  app <- ZIO.service[AgentApplication]
  command <- app.submit(
    agent,
    RunRequest(
      ThreadId("thread-1"),
      AgentMessage.user("请检索阴阳基础资料"),
      RunContext(Some("user-1"), Some("tenant-1"), Set("knowledge:read"))
    ),
    idempotencyKey = "client-request-uuid"
  )
  // 仅在测试中手动处理一条；常驻服务应启动 runWorker/startWorkerScoped。
  _     <- app.claimOnce
  state <- app.inspect(command.runId)
yield state
```

该入口明确注入空 `ContextSourceResolver`、空 `GuardrailEngine` 和 noop `RunObserver`。知识库、敏感数据、真实用户或生产
环境不能使用这个默认值。

## 6. 带业务治理的内存集成测试

希望在接入 PostgreSQL 之前验证真实 Context/Guardrail/Telemetry 时，使用 `inMemory`：

```scala
val testedLayer: URLayer[
  ChatModel & RegisteredToolRegistry & ContextSourceResolver & GuardrailEngine & RunObserver,
  AgentApplication.Services
] = AgentApplication.inMemory(WorkerId("integration-worker"), appConfig)
```

它仍使用一套共享的 `AgentPersistence.inMemory`，但不会替业务填充空治理组件。CI 可以借此验证“组件确实进入主 loop”，而不只
验证类能够构造。

需要验证真实模型辅助摘要时，使用显式压缩器入口：

```scala
val testedLayer: URLayer[
  ChatModel & RegisteredToolRegistry & ContextSourceResolver & GuardrailEngine & RunObserver & ContextCompressor,
  AgentApplication.Services
] = AgentApplication.inMemoryWithContextCompressor(
  WorkerId("compression-integration-worker"),
  appConfig
)
```

## 7. PostgreSQL 生产装配

生产入口 `durable` 强制要求三个持久化 SPI 来自业务提供的组合层。推荐让它们共享同一个 `DataSource`：

```scala
val applicationLayer: ZLayer[Any, Throwable, AgentApplication.Services] =
  ZLayer.make[AgentApplication.Services](
    dataSourceLayer,
    PostgresAgentPersistence.layer,
    providerLayer,
    registeredToolRegistryLayer,
    contextSourceResolverLayer,
    guardrailEngineLayer,
    runObserverLayer,
    llmContextCompressorLayer,
    AgentApplication.durableWithContextCompressor(
      WorkerId(s"agent-worker-${java.util.UUID.randomUUID()}"),
      AgentApplicationConfig(toolPolicy, WorkerHostConfig())
    )
  )
```

必须保持的生产不变量：

- `RunStore`、`RunCommandStore` 和 `RunSubmissionStore` 共享同一数据库/事务边界；
- 不允许用内存 Store 作为 PostgreSQL 故障时的自动 fallback，否则多副本会产生分叉事实；
- `ContextSourceResolver` 必须先做 tenant/user/scope 授权，再返回 Memory/RAG 内容；
- `RunObserver` 只输出脱敏、低基数字段，模型正文和工具参数不得进入 OTLP/Langfuse；
- HTTP 只调用 `AgentCommandService`，Worker 才能调用 lease-aware Runtime；
- `WorkerId` 每次进程启动唯一，不能把多个副本配置成同一个固定值。
- `worker.parallelism` 是单实例同时推进的不同 Run 上限，默认 4、允许 1..256；同一 Run 仍由 dispatcher
  严格串行。它必须与 JDBC 连接池、Provider 配额、工具下游容量和 Pod 内存一起压测，不能只为缩短队列而盲目调大。
- `app.queueSnapshot` 只返回队列聚合，可直接供宿主的内部运维端点或定时指标采集使用；不要为查看 backlog 暴露命令正文或
  绕过 `AgentApplication` 读取任意 SQL。
- `CompressionMode.Deterministic` 始终使用本地算法，即使图中存在 LLM compressor 也不产生费用；
- `CompressionMode.ModelAssisted` 必须使用 `*WithContextCompressor` 入口，否则在 Provider 调用前明确失败。

## 8. Worker 生命周期

常驻 Worker 可以直接作为应用主 effect。`runWorker` 会启动 `worker.parallelism` 个结构化 claim lane；任一 lane
永久失败都会中断其余 lane 并让外层 Supervisor 重启实例，不会留下部分 lane 静默死亡：

```scala
val workerProgram: ZIO[AgentApplication, AgentError, Nothing] =
  ZIO.serviceWithZIO[AgentApplication](_.runWorker)
```

同一门面可以采集低敏队列状态：

```scala
val queueHealth: ZIO[AgentApplication, AgentError, RunCommandQueueSnapshot] =
  AgentApplication.queueSnapshot
```

建议对 `dispatchableRuns`、`oldestDispatchableAgeMillis`、`expiredLeases` 和 `deadLetterCommands` 分别建立阈值与 runbook。
快照是采样值，不替代 `RunStore`、命令审计或 Prometheus 的历史时间序列。

如果同一个进程还运行自定义 ZIO HTTP Server，可使用 Scope 管理后台 Fiber：

```scala
val program = ZIO.scoped {
  for
    _ <- AgentApplication.startWorkerScoped
    _ <- serveHttpApi
  yield ()
}
```

`forkScoped` 让 Worker 可以超出启动方法本身继续运行，但 Scope 关闭时一定中断；取消会继续传播到 heartbeat、Provider
流、工具 Fiber 与资源 finalizer。不要用 `forkDaemon` 启动业务 Worker。ZIO 对 scoped Fiber 生命周期的说明见
[https://zio.dev/reference/fiber/fiber.md/](https://zio.dev/reference/fiber/fiber.md/)。

独立部署优先使用 `zyblw-agent-zio-http` 中的 `AgentHttpHost`，由 Host 同时管理 Server 与 Worker。此时不要再调用
`AgentApplication.startWorkerScoped`，否则同一进程会启动重复的 command claim 循环。Host 内部拥有子 Scope，任一关键
Worker 或 Server 失败都会中断另一方，完整装配见 [ZIO HTTP 生产宿主](http-host.md)。

## 9. HTTP 接入

`AgentApplication.Services` 同时输出 `AgentRuntime` 和 `AgentCommandService`，可以继续组装：

```scala
val httpLayer = ZLayer.make[AgentHttpApi](
  applicationLayer,
  AgentRegistry.fromAgents(List(agent)),
  authenticatedRequestContextResolver,
  DurableRunEventStream.default,
  AgentHttpApi.layer
)
```

跨语言客户端或网关应直接消费 `/api/v1/openapi.json`，无需依赖 Scala/JVM Runtime。所有稳定控制面路径位于
`/api/v1`，OpenAPI 由 `zyblw-agent-zio-http` 中的 ZIO HTTP Endpoint 生成；详细兼容规则见
[HTTP API、OpenAPI 与 Schema 演进](http-api-versioning.md)。

HTTP 创建仍返回 `202 + runId + commandId`；`AgentApplication` 没有引入一个等待最终答案的同步快捷方式，以免业务服务器
重新让请求连接拥有长时间 Run 生命周期。

若要把 Agent 作为独立服务部署，在上面的 `AgentHttpApi` 之外继续组装 `AgentHttpHost.fromApplication`、
`AgentHostReadiness.jdbc`、`AgentHttpServer.zioHttp` 与 ZIO HTTP `Server.configured()`。如果 Agent routes 嵌入已有
`zyblw-server`，则只合并 `AgentHttpApi.routes`，继续由业务服务器拥有端口、TLS、认证和主 Scope。

## 10. 选择哪一个入口

| 场景                       | 入口                                      | Store                      | 治理组件                   |
| -------------------------- | ----------------------------------------- | -------------------------- | -------------------------- |
| 教程、快速单测             | `inMemoryDefaults`                      | 内存                       | 明确为空，确定性压缩       |
| 压缩器教程/契约测试        | `inMemoryDefaultsWithContextCompressor` | 内存                       | 空业务治理，显式压缩器     |
| 业务集成测试               | `inMemory`                              | 内存                       | 必须显式提供，确定性压缩   |
| 模型摘要集成测试           | `inMemoryWithContextCompressor`         | 内存                       | 显式治理与压缩器           |
| 单实例试运行但要求重启恢复 | `durable`                               | PostgreSQL                 | 显式治理，确定性压缩       |
| 多副本与模型摘要生产       | `durableWithContextCompressor`          | PostgreSQL + lease/fencing | 显式治理、压缩器并完成演练 |

完整可运行代码见 `BasicAgentExample`、`ApprovalAgentExample`、`RagAgentExample`、
`ContextCompressionExample`、`StandaloneHttpAgentExample` 和 `PostgresQuickstartExample`；Context 示例会经过真实异步主循环
生成耐久摘要 checkpoint，HTTP 示例会启动真实 ZIO HTTP 端口并由 Host 管理 Worker 生命周期，PostgreSQL 示例则覆盖
DataSource、migration、durable application、类型化只读工具和 scoped shutdown 的独立宿主路径。

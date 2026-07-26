# MCP 集成

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 当前定位

`agent-mcp` 是 zyblw-agent 的 MCP 2025-11-25 稳定版客户端模块。它负责把外部 MCP server 提供的工具、资源和 Prompt 接入框架，但不会让远端描述绕过本地权限、审批、预算、Guardrail、审计或工具执行账本。

当前不是一个“把 JSON 发出去”的薄封装。模块已经包含：

- 严格 JSON-RPC 2.0 envelope、字符串/数字 request id、并发 pending 关联和晚到响应处理。
- `initialize -> initialized -> operation -> shutdown` 生命周期。
- 协议版本锁定和 Client/Server capability negotiation。
- Tools、Resources、Prompts 的分页、类型检查和 cursor 环检测。
- 工具 structured content、资源 text/blob 区分、Prompt message 和安全通知投影。
- 反向 `ping`、sampling、elicitation 请求治理。
- 2025-11-25 实验性 Tasks 的 `list/get/result/cancel` 客户端。
- 真实 stdio 子进程 transport，以及复用同一 framing 的 scoped OCI Sandbox session。
- 真实 Streamable HTTP POST/GET/DELETE、JSON/SSE、session、Last-Event-ID 恢复和 bearer header。
- Scope 关闭、硬超时、Fiber 取消传播、请求取消通知和有界入站队列。

协议入口位于：

- `McpProtocol.scala`：协议模型、能力和严格 JSON 解析。
- `McpClient.scala`：生命周期、分页和高层 API。
- `McpJsonRpcPeer.scala`：并发 request id、Promise、取消和入站路由。
- `StdioMcpTransport.scala`：受 Scope 管理的进程 transport。
- `StreamableHttpMcpTransport.scala`：Streamable HTTP、SSE 和 session 恢复。
- `McpInteractiveRequests.scala`：sampling/elicitation 的审批治理。

## 为什么锁定 2025-11-25

MCP 使用日期版本，transport 和 session 语义会随版本改变。框架只接受已经有契约测试的 `2025-11-25` 稳定版，不会自动接受未知 draft 或未来版本。升级步骤应是：

1. 阅读新版本 changelog 和 schema。
2. 增加版本专属 transport/lifecycle 契约。
3. 验证认证、session、取消和 capability 变化。
4. 最后显式加入 `McpProtocolVersion.supported`。

这比“收到什么版本都继续跑”更符合 Scala 的类型安全与生产 fail-closed 原则。

## stdio 使用

```scala
import com.zyblw.agent.mcp.*
import zio.*

val clientConfig = McpClientConfig(
  serverId = McpServerId("local-knowledge"),
  clientInfo = McpImplementation("zyblw-server", "1.0.0")
)

val program = ZIO.scoped {
  for
    transport <- StdioMcpTransport.scoped(
      StdioMcpTransportConfig(
        command = Chunk("/opt/mcp/bin/knowledge-server"),
        environment = Map("CONFIG_PATH" -> "/etc/zyblw/mcp.conf"),
        inheritParentEnvironment = false
      )
    )
    client <- DefaultMcpClient.scoped(transport, clientConfig)
    tools  <- client.listTools
  yield tools
}
```

`command` 直接进入 Java `ProcessBuilder`，不经过 shell，因此 `$()`、反引号和重定向不会被展开。默认不继承父进程环境，业务必须显式提供子进程所需变量；这可防止 MCP server 自动得到数据库密码、云凭据和全部应用 secret。

stdio transport 的资源语义：

- stdout 只能出现一行一个 JSON-RPC 消息；空行和非 JSON 都是致命协议错误。
- 使用严格 UTF-8，非法字节不会被替换字符吞掉。
- stdout/stderr 都有单行上限。
- stderr 始终被排空，但正文不会进入日志和错误，只记录“出现了一行”的低敏事件。
- 关闭顺序是关闭 stdin、等待、terminate、再次等待、force kill。
- reader、stderr drainer 和 exit watcher 都属于调用方 `Scope`。

### 在 OCI Sandbox 中运行 stdio server

对于不完全受信的本地 MCP package，不要使用宿主 `StdioMcpTransportConfig`，而应显式装配容器会话：

```scala
import com.zyblw.agent.mcp.*
import com.zyblw.agent.workspace.*
import zio.*

val transportProgram = ZIO.scoped {
  StdioMcpTransport.sandboxedScoped(
    SandboxSessionCommand(
      executable = "/usr/local/bin/knowledge-mcp",
      arguments = Chunk("--stdio"),
      workingDirectory = WorkspacePath("mcp"),
      environment = Map("MCP_CONFIG_TOKEN" -> "由 Secret Manager 注入")
    ),
    SandboxedStdioMcpConfig(maxLineChars = 1024 * 1024, inboundCapacity = 256)
  )
}

val runnable = transportProgram.provide(
  SandboxProcessSessionFactory.live,
  OciSandboxExecutor.sessionLayer(ociConfig)
)
```

`SandboxSessionLauncher` 是比一次性 `SandboxExecutor` 更窄且更明确的 capability：它只在调用方 `Scope` 内暴露双向
stdin/stdout/stderr。OCI 层负责镜像、断网、文件系统、用户和资源限制；MCP 层负责 UTF-8 framing、单行上限、请求超时、
取消通知和 stderr 脱敏。两条路径共用唯一 `McpJsonRpcPeer`，不会形成“容器版协议分叉”。详细隔离边界见
[Workspace 与 OCI Sandbox](sandbox.md)。

## Streamable HTTP 使用

```scala
import com.zyblw.agent.mcp.*
import zio.*
import zio.http.Client

def program(client: Client, tokenProvider: McpBearerTokenProvider) = ZIO.scoped {
  for
    transport <- StreamableHttpMcpTransport.scoped(
      client,
      StreamableHttpMcpTransportConfig(
        endpoint = "https://mcp.example.com/mcp"
      ),
      tokenProvider
    )
    mcp <- DefaultMcpClient.scoped(
      transport,
      McpClientConfig(
        McpServerId("remote-knowledge"),
        McpImplementation("zyblw-server", "1.0.0")
      )
    )
    resources <- mcp.listResources
  yield resources
}
```

生产配置默认只允许 HTTPS，并拒绝 endpoint 中的 user-info、query 和 fragment。Bearer token 每次请求前从 `McpBearerTokenProvider` 获取，只进入 `Authorization` header；不能把 token 配成 URL query。

HTTP transport 当前保证：

- 每条 JSON-RPC message 使用新的 POST。
- POST `Accept` 同时包含 `application/json` 和 `text/event-stream`。
- 初始化后自动携带 `MCP-Protocol-Version`。
- 安全保存并回填 `MCP-Session-Id`，关闭时尽力 DELETE。
- 支持 JSON 或 SSE POST 响应。
- POST SSE 断开后使用 `Last-Event-ID` 通过 GET 恢复原流。
- 可选独立 GET listener 接收服务端通知和反向请求；405 表示服务端不支持该能力，并非错误。
- 404 session 失效后串行重新 initialize，只有协议版本、capabilities 和 serverInfo 与原协商兼容时才重放原请求。
- JSON、SSE line、SSE event 和 session id 都有硬上限。
- 408、409、425、429 和 5xx 被分类为可重试 transport failure；响应正文不会拼入错误。

## 工具接入本地 Runtime

远端工具描述不是本地授权。业务应为每个工具提供本地 `ToolMetadata`：

```scala
val registered: RegisteredTool = new McpRegisteredTool(
  client = mcpClient,
  descriptor = remoteDescriptor,
  metadata = ToolMetadata(
    risk = ToolRisk.ApprovalWrite,
    sideEffect = SideEffect.IdempotentWrite,
    requiredScopes = Set("content:write")
  )
)
```

`McpRegisteredTool` 只负责远端调用适配。真正执行仍必须经过 `RegisteredToolRegistry`、`ToolExecutor`、权限、审批、预算、审计和耐久工具账本。远端 annotations 不能自动降低本地风险等级。

## sampling 与 elicitation

默认 `McpClientCapabilities()` 不开放 sampling、elicitation 和 Roots；`McpClientRequestHandler.denyPrivileged` 只响应 ping。

若业务确实需要开放，应同时：

1. 在 capability 中显式打开对应子能力。
2. 使用 `GovernedMcpClientRequestHandler`。
3. 提供 `McpInteractiveApproval` 的耐久审批实现。
4. 提供 `McpSamplingService` 或 `McpElicitationService`。

```scala
val capabilities = McpClientCapabilities(
  sampling = true,
  samplingTools = false,
  elicitationForm = true
)

val handler = new GovernedMcpClientRequestHandler(
  capabilities,
  durableApproval,
  businessSamplingService,
  businessElicitationService
)
```

处理顺序固定为：能力门禁、严格解析、子能力检查、人工审批、实际服务。sampling 携带 tools 时还必须声明 `sampling.tools`；跨 server context 默认拒绝。URL elicitation 只接受无 user-info 的绝对 HTTPS URL，框架不会自动打开链接或携带浏览器 cookie。

生产 `McpInteractiveApproval` 应连接 durable command queue，使审批可暂停数小时或数天后恢复。同步 callback 只适用于测试，不能替代耐久 HITL。

## 实验性 Tasks

只有双方协商 `tasks` 后，以下方法才可调用：

- `listTasks`
- `getTask`
- `taskResult`
- `cancelTask`

Task status 被解析成 `McpTaskStatus`，时间必须为 ISO-8601，TTL 和 poll interval 必须非负。Tasks 在 2025-11-25 仍是实验能力，因此 API 明确标记为实验性，不能作为框架 durable Runtime 的替代品。zyblw-agent 自身的 Run/Command/Lease/Checkpoint 仍由 PostgreSQL durable runtime 管理。

## 已验证测试

`mcp/test` 当前覆盖：

- 初始化顺序、能力协商与 Scope 关闭。
- 工具分页、结果、cursor 环和未协商能力拒绝。
- 真实 JDK 子进程 stdio 调用、超时、Fiber 中断、非法 stdout、超长行和进程回收。
- scoped Sandbox session 路径的初始化、工具调用、同一 framing 与 Scope 进程回收。
- 真实 ZIO HTTP stub 的 JSON/SSE、双 Accept、Bearer、session/version header、DELETE。
- SSE 断流与 Last-Event-ID 恢复。
- 404 session 自动重建、握手 fencing 和请求重放。
- sampling.tools 子能力、审批先后顺序和摘要脱敏。
- elicitation 拒绝、HTTPS URL 边界和实验 Tasks。

## 仍未完成的边界

以下能力仍不能宣称生产完成：

- OAuth 2.1 protected-resource metadata、authorization-server discovery、PKCE 和动态 token 获取；当前提供的是可轮换 bearer token SPI。
- MCP server 端实现和 `Origin` 校验；当前模块是客户端。服务端实现必须对非法 Origin 返回 403。
- Roots provider、completion、logging level 设置、资源模板和资源订阅后的业务缓存失效器。
- sampling/elicitation task augmentation 的完整创建与结果恢复。
- MCP Registry/供应链签名、server package provenance 和版本锁定策略。
- 真实 Docker/Podman rootless+cgroup、镜像签名/SBOM、容器逃逸与恶意 MCP package 混沌门禁；当前确定性契约使用
  真实 JDK session 验证进程语义，并独立验证 OCI hardening argv，不把“参数已生成”冒充生产隔离已验收。

因此，下一阶段应补 OAuth 2.1 授权客户端、Registry 供应链校验和真实容器部署门禁，而不是立即扩大远端 MCP server 数量。

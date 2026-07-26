# ZIO HTTP 生产宿主、健康检查与生命周期

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-25
>
> 事实来源：对应模块源码、测试与构建定义

`zyblw-agent-zio-http` 同时包含稳定 wire contract、耐久 Agent routes 和可选独立 Host。它们使用同一个 ZIO HTTP
版本和协议生命周期，因此不再拆成三个 Maven 坐标；Scala package 仍将 contract、adapter、host 分开。既有
`zyblw-server` 可以只组合 routes，独立 Agent 服务则使用 Host 管理 Server、Worker 和健康检查。

## 1. 什么时候引入本模块

业务项目按部署形态二选一：

| 形态 | 推荐做法 | 原因 |
|---|---|---|
| 嵌入既有 ZIO HTTP 后端 | 引入 `zyblw-agent-zio-http`，只把 `AgentHttpApi.routes` 合并到业务 `Routes` | 端口、TLS、认证、优雅关闭和应用主 Scope 已由宿主拥有 |
| 独立 Agent 服务 | 引入同一 artifact，并使用 `AgentHttpHost` | Host 统一管理 Server、command worker、健康探针和关键后台进程 |

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"     % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-zio-http" % zyblwAgentVersion
)
```

独立 Host 不会创建以下业务资源：

- 不创建 JDBC 连接池或猜测数据库地址；所有 PostgreSQL Adapter 必须共享宿主 `DataSource`。
- 不从请求正文接受 tenant、user 或 scopes，也不提供生产匿名认证。
- 不选择 Provider、模型或 API Key；密钥仍由 Provider 的 `Config.Secret` 与部署平台 Secret 管理。
- 不替业务决定端口、TLS、压缩和 graceful shutdown；这些属于 ZIO HTTP `Server.Config`。

## 2. 一条完整的装配路径

下面的代码展示依赖关系。`dataSourceLayer`、Provider、工具、Context、Guardrail、Observer 和认证解析器都必须由业务实现；
这段代码没有任何内存 fallback：

```scala
import com.zyblw.agent.app.*
import com.zyblw.agent.http.*
import com.zyblw.agent.http.host.*
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import zio.*
import zio.http.Server

val applicationLayer = ZLayer.make[AgentApplication.Services](
  dataSourceLayer,
  PostgresAgentPersistence.layer,
  providerLayer,
  registeredToolRegistryLayer,
  contextSourceResolverLayer,
  guardrailEngineLayer,
  runObserverLayer,
  AgentApplication.durable(
    WorkerId(s"agent-worker-${java.util.UUID.randomUUID()}"),
    applicationConfig
  )
)

val apiLayer = ZLayer.make[AgentHttpApi](
  applicationLayer,
  AgentRegistry.fromAgents(List(knowledgeAgent)),
  authenticatedRequestContextResolver,
  DurableRunEventStream.default,
  AgentHttpApi.layer
)

val hostLayer = ZLayer.make[AgentHttpHost](
  applicationLayer,
  apiLayer,
  dataSourceLayer,
  AgentHostReadiness.jdbc,
  AgentHttpAdditionalRoutes.empty,
  AgentHttpHostConfig.layer(),
  Server.configured(),
  AgentHttpServer.zioHttp,
  AgentHttpHost.fromApplication
)

val program: ZIO[Any, Throwable, Nothing] =
  AgentHttpHost.serve.provideLayer(hostLayer)
```

这里必须只启动一次 Worker。使用 `AgentHttpHost.fromApplication` 后，不要再调用
`AgentApplication.startWorkerScoped` 或单独运行 `application.runWorker`，否则同一进程会出现两个 command claim 循环。

## 3. 结构化生命周期为何重要

`AgentHttpHost.serve` 内部创建一个子 `Scope`，并在其中用 `forkScoped` 启动所有关键后台进程。HTTP Server 与关键进程
进行对称竞速：

1. Server 启动或运行失败，会中断 command worker 和其他关键进程；
2. command worker 发生 typed failure、defect、意外完成或独立中断，会中断 Server 并让主 effect 失败；
3. Kubernetes、systemd 或调用方中断 Host Fiber 时，内部 Scope 立即关闭，等待 Worker、Provider 流、工具 Fiber 和资源
   finalizer 退出；
4. Host 不使用 `forkDaemon`，因此不会留下“HTTP 端口还活着，但 Worker 已永久死亡”的半活进程。

`routes` 使用和 `serve` 不同：`AgentHttpHost.routes` 只返回合并后的 routes，适合由更外层业务 Server 安装；它不会替调用方
启动 Server。独立部署才应调用 `AgentHttpHost.serve`。

## 4. 健康接口语义

Host 保留两个路径，附加业务 routes 不应重复定义：

| 接口 | 成功条件 | 失败示例 | 用途 |
|---|---|---|---|
| `GET /health/live` | Host 已进入 Running 且关键进程未退出 | `starting`、`stopping`、`process_typed_failure` | 判断进程是否应被重启 |
| `GET /health/ready` | liveness 成立，且 readiness 依赖在硬超时内通过 | `dependency_timeout`、`dependency_unavailable` | 判断实例是否接收流量 |

返回正文只包含 service、version、environment、check、status 和稳定 code，并带
`Cache-Control: no-store`。JDBC URL、SQLState、Provider 原文、工具参数、用户输入和异常堆栈不会进入响应。

`AgentHostReadiness.jdbc` 每次借用一个连接执行 `SELECT 1`，连接、statement 和 result 在成功、失败及中断路径都关闭。
外层 `readinessTimeout` 防止连接池耗尽时探针永久悬挂。它只证明当前实例能够接触基础持久化依赖，并不证明：

- PostgreSQL 读写容量、主从切换或备份恢复已经通过演练；
- Provider 凭据、模型额度或第三方网络一定可用；
- 某个业务 Agent 的回答质量已通过 eval。

不要在高频 readiness 探针中调用付费模型。Provider 可用性应通过低频受控 smoke、断路指标和业务告警验证。

Kubernetes 可使用：

```yaml
livenessProbe:
  httpGet: { path: /health/live, port: 8080 }
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /health/ready, port: 8080 }
  periodSeconds: 5
  timeoutSeconds: 3
```

探针 `timeoutSeconds` 应略大于框架 `readiness-timeout`，让应用先返回稳定 503，而不是由 kubelet 强制断开连接。

## 5. Host 配置

默认 shell-safe 点分路径是 `zyblw.agent.http.host`，环境变量为：

```bash
ZYBLW_AGENT_HTTP_HOST_SERVICE_NAME=zyblw-agent
ZYBLW_AGENT_HTTP_HOST_SERVICE_VERSION=2026.07.15
ZYBLW_AGENT_HTTP_HOST_ENVIRONMENT=production
ZYBLW_AGENT_HTTP_HOST_READINESS_TIMEOUT=2s
```

三个标签都必须是 1..128 个无控制字符文本；readiness timeout 必须位于 `(0, 30s]`。这些值会出现在健康响应中，因此只能
使用低敏、低基数的发布标签，不能放 hostname、pod UID、tenant ID、API Key 或数据库地址。

端口等 Server 配置继续使用 ZIO HTTP 自身的配置机制，例如在业务应用层提供 `Server.Config` 后调用
`Server.configured()`。框架故意不再包装一份平行的 HTTP Server 配置，避免同一个参数出现两个事实源。

## 6. 附加 routes 与认证边界

`AgentHttpAdditionalRoutes` 可合并 Memory 用户治理、反馈或业务管理 API：

```scala
val additionalRoutesLayer =
  AgentHttpAdditionalRoutes.layer(memoryRoutes ++ feedbackRoutes)
```

传入前必须已经消除 route 错误通道并安装业务认证/授权。Host 只负责编排，不会从 DTO 推导可信身份，也不会自动让附加路由
继承某个认证中间件。主 Agent API 的身份仍由 `AgentRequestContextResolver` 从已验签 JWT、服务端 session 或 mTLS 上下文
构造。

## 7. 增加关键后台进程

独立服务可能还要运行 outbox publisher 或 memory retention worker。先为 effect 创建稳定名称，再与 command worker 一起
构造集合：

```scala
for
  outbox   <- AgentHostProcess.make("outbox-publisher", outboxPublisher.run)
  retention <- AgentHostProcess.make("memory-retention", retentionWorker.run)
yield AgentHostProcesses.fromApplication(Chunk(outbox, retention))
```

名称只允许 1..64 个字母、数字、`-` 或 `_`，集合非空且不能重名。只有“退出后本实例不应继续提供服务”的进程才放入关键
集合；普通低优先级维护作业若失败不应杀死 API，应由业务使用独立 supervisor、重试策略和告警管理。

附加 effect 应先捕获自己的 ZLayer 依赖，并且本身遵守中断与 Scope。Host 不会把无限重试强加给它：永久错误应终止进程，
让部署 supervisor 重启并触发告警；瞬时错误的有界退避应在对应 worker 内明确实现。

## 8. 测试与尚未完成的边界

`AgentHttpHostSpec` 已确定性验证：

- live/ready、主 Agent routes 与附加 routes 同时安装，健康响应禁止缓存；
- readiness 失败与 TestClock 硬超时只输出稳定低敏 code；
- 关键进程失败会关闭 Server，Server 失败会关闭 Worker；
- 中断 Host Fiber 会立即关闭内部子 Scope，而不是等待外部应用 Scope；
- 空进程集合、重名和非法进程名在启动前失败。

可以用不访问公网的完整示例验证真实端口：

```bash
sbt "examples/runMain com.zyblw.agent.examples.StandaloneHttpAgentExample"
```

示例使用固定本地模型、内存 Store、匿名身份和 `alwaysReady`，目的是展示依赖图与关闭语义；这些教程默认值都不能进入
生产。源码见
[StandaloneHttpAgentExample.scala](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/StandaloneHttpAgentExample.scala)。

这些测试证明宿主生命周期契约，不等于生产容量结论。发布前仍要在真实部署环境执行 SIGKILL、网络分区、PostgreSQL
切换、HikariCP/PgBouncer 饱和、长时间 soak、滚动发布和优雅终止演练。`zyblw-agent-zio-http` 的
`http.contract` package 已提供 `/api/v1`、ZIO
Schema 与机械 OpenAPI；尚待发布流水线补 OpenAPI 归档/diff、生成客户端矩阵和 JVM 二进制兼容检查。认证实现与业务
vertical slice 仍由宿主负责。

ZIO HTTP 官方以 `Server.serve` 作为长驻服务 effect，并允许通过 `Routes` 组合应用；ZIO `Scope` 则保证已注册资源在
Scope 关闭时执行 finalizer。本模块直接使用这些语义，而不是额外模拟一套生命周期。

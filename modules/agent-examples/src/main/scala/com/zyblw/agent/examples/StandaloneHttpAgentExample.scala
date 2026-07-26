package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.*
import com.zyblw.agent.http.host.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.runtime.DurableRunEventStream
import com.zyblw.agent.tools.{RegisteredToolRegistry, ToolPolicyConfig}
import zio.*
import zio.http.Server

/** 可直接启动的独立 Agent HTTP 服务示例。
  *
  * 这个示例刻意使用内存 Store、匿名身份和本地固定模型，因此只用于理解 ZLayer 装配与 HTTP 生命周期，不能作为生产配置。 生产接入必须分别替换为
  * `AgentApplication.durable`、PostgreSQL、真实 Provider、已验签身份解析器以及 JDBC readiness。
  *
  * 启动后可以先访问：
  *
  * {{ curl http://localhost:8080/health/live curl http://localhost:8080/health/ready curl -i -X POST
  * http://localhost:8080/api/v1/agents/http-demo/runs \ -H 'Content-Type: application/json' \ -H
  * 'Idempotency-Key: http-demo-1' \ -d '{"threadId":"demo-thread","input":"你好"}' }}
  *
  * 创建接口返回 `202` 是正确语义：HTTP 请求只提交耐久命令，Host 内的 command worker 随后推进 Run。调用方可使用回执中的 runId 查询
  * `/api/v1/runs/{runId}`，不应让请求连接等待模型完成。
  */
object StandaloneHttpAgentExample extends ZIOAppDefault:

  /** 教程专用模型：每次请求都返回固定答案，因此无需 API Key，也不会访问公网。
    *
    * 它实现同一个 `ChatModel` SPI，说明 Host 不依赖任何厂商类型；替换 DeepSeek、GLM、OpenAI、Anthropic 或 Gemini 时，
    * HTTP/Worker/健康检查的其余 Layer 不需要改变。
    */
  private val localModel: ChatModel = new ChatModel:
    val provider: String = "standalone-demo"

    /** @param request
      *   Runtime 已完成上下文预算、权限与能力校验的 Provider-neutral 请求；示例不读取正文
      * @return
      *   固定助手消息和非负 token usage，用于演示一次完整的异步 Run
      */
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      ZIO.succeed(
        ChatResponse(
          AgentMessage.assistant("独立 ZIO HTTP Agent Host 已成功处理这次请求。"),
          FinishReason.Stop,
          TokenUsage(inputTokens = 8L, outputTokens = 12L)
        )
      )

  /** 空白名单表示模型看不到任何工具，符合框架默认拒绝原则。 */
  private val applicationConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig.secureDefault
  )

  /** 构造教程 Agent，并把 Application、HTTP Adapter 与 Host 组成唯一依赖图。
    *
    * `AgentHttpHost.fromApplication` 会把 `application.runWorker` 注册为关键后台进程，所以这里不能再调用
    * `AgentApplication.startWorkerScoped`。Host 自己创建子 Scope，Ctrl-C 中断时 Server、Worker 和其子 Fiber 会一起退出。
    */
  def run: ZIO[Any, Any, Any] =
    for
      agent <- AgentDefinitionBuilder(AgentId("http-demo"), "独立 HTTP 宿主示例")
        .withInstructions("只返回安全的演示回答，不调用工具。")
        .withProvider(ProviderId("standalone-demo"))
        .withModel(ModelId("standalone-demo-v1"))
        .buildFor(applicationConfig.toolPolicy)
      _ <- Console.printLine("Agent HTTP Host 正在监听 http://localhost:8080；按 Ctrl-C 触发结构化关闭。")
      _ <- AgentHttpHost.serve.provide(
        ZLayer.succeed(localModel),
        RegisteredToolRegistry.fromTools(Nil),
        AgentApplication.inMemoryDefaults(WorkerId("standalone-http-example"), applicationConfig),
        AgentRegistry.fromAgents(List(agent)),
        AgentRequestContextResolver.anonymous,
        DurableRunEventStream.default,
        AgentHttpApi.layer,
        AgentHostReadiness.alwaysReady,
        AgentHttpAdditionalRoutes.empty,
        ZLayer.succeed(
          AgentHttpHostConfig(
            serviceName = "zyblw-agent-example",
            serviceVersion = "development",
            environment = "local"
          )
        ),
        Server.defaultWithPort(8080),
        AgentHttpServer.zioHttp,
        AgentHttpHost.fromApplication
      )
    yield ()

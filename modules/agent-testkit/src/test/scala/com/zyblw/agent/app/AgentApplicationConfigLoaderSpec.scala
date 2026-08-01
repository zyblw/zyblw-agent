package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.*
import zio.*
import zio.test.*

/** 验证配置加载、默认值、启动期约束和错误脱敏，避免配置问题拖到第一条真实 Run 才出现。 */
object AgentApplicationConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentApplicationConfigLoader")(
    test("完整解析工具、重试和 Worker 配置") {
      val values = Map(
        "zyblw.agent.tool.allowed_tools"       -> "knowledge_search, article_draft,knowledge_search",
        "zyblw.agent.tool.denied_tools"        -> "admin_delete",
        "zyblw.agent.tool.max_calls_per_run"   -> "40",
        "zyblw.agent.tool.max_calls_per_step"  -> "6",
        "zyblw.agent.tool.max_parallelism"     -> "3",
        "zyblw.agent.tool.default_timeout"     -> "12s",
        "zyblw.agent.tool.max_result_bytes"    -> "8192",
        "zyblw.agent.tool.approval_policy"     -> "always",
        "zyblw.agent.tool.retry.mode"          -> "idempotent_only",
        "zyblw.agent.tool.retry.max_attempts"  -> "4",
        "zyblw.agent.tool.retry.initial_delay" -> "100ms",
        "zyblw.agent.tool.retry.max_delay"     -> "2s",
        "zyblw.agent.tool.retry.jitter"        -> "0.1",
        "zyblw.agent.tool.retry.max_elapsed"   -> "8s",
        "zyblw.agent.worker.lease_duration"    -> "45s",
        "zyblw.agent.worker.heartbeat_every"   -> "12s",
        "zyblw.agent.worker.poll_every"        -> "250ms",
        "zyblw.agent.worker.retry_delay"       -> "3s",
        "zyblw.agent.worker.max_attempts"      -> "11",
        "zyblw.agent.worker.parallelism"       -> "6"
      )

      AgentApplicationConfigLoader.load().provide(configProvider(values)).map { config =>
        val retry = config.toolPolicy.retryPolicy match
          case ToolRetryPolicy.IdempotentOnly(value) => Some(value)
          case ToolRetryPolicy.Never                 => None
        assertTrue(
          config.toolPolicy.allowedTools == Set(ToolName("knowledge_search"), ToolName("article_draft")),
          config.toolPolicy.deniedTools == Set(ToolName("admin_delete")),
          config.toolPolicy.maxCallsPerRun == 40,
          config.toolPolicy.maxCallsPerStep == 6,
          config.toolPolicy.maxParallelism == 3,
          config.toolPolicy.defaultTimeout == 12.seconds,
          config.toolPolicy.maxResultBytes == 8192L,
          config.toolPolicy.approvalPolicy == ApprovalPolicy.Always,
          retry.exists(_.maxAttempts == 4),
          retry.exists(_.initialDelay == 100.millis),
          config.worker.leaseDuration == 45.seconds,
          config.worker.heartbeatEvery == 12.seconds,
          config.worker.pollEvery == 250.millis,
          config.worker.retryDelay == 3.seconds,
          config.worker.maxAttempts == 11,
          config.worker.parallelism == 6
        )
      }
    },
    test("缺省配置保持安全的默认拒绝工具策略") {
      AgentApplicationConfigLoader.load().provide(configProvider(Map.empty)).map { config =>
        assertTrue(
          config.toolPolicy.allowedTools.isEmpty,
          config.toolPolicy.approvalPolicy == ApprovalPolicy.RiskBased,
          config.toolPolicy.retryPolicy == ToolRetryPolicy.Never,
          config.worker.maxAttempts == 8,
          config.worker.parallelism == 4
        )
      }
    },
    test("拒绝同时允许和禁止同一个工具") {
      val values = Map(
        "zyblw.agent.tool.allowed_tools" -> "dangerous_write",
        "zyblw.agent.tool.denied_tools"  -> "dangerous_write"
      )
      AgentApplicationConfigLoader.load().provide(configProvider(values)).exit.map { exit =>
        val message = exit match
          case Exit.Failure(cause) => cause.failureOption.map(_.message).getOrElse("")
          case Exit.Success(_)     => ""
        assertTrue(
          exit.isFailure,
          message.contains("allowed-tools")
        )
      }
    },
    test("拒绝 heartbeat 不小于 lease 的 Worker 配置") {
      val values = Map(
        "zyblw.agent.worker.lease_duration"  -> "10s",
        "zyblw.agent.worker.heartbeat_every" -> "10s"
      )
      AgentApplicationConfigLoader.load().provide(configProvider(values)).exit.map { exit =>
        val message = exit match
          case Exit.Failure(cause) => cause.failureOption.map(_.message).getOrElse("")
          case Exit.Success(_)     => ""
        assertTrue(
          exit.isFailure,
          message.contains("heartbeatEvery")
        )
      }
    },
    test("拒绝超过硬上限的 Worker 并发") {
      AgentApplicationConfigLoader
        .load()
        .provide(configProvider(Map("zyblw.agent.worker.parallelism" -> "257")))
        .exit
        .map { exit =>
          val message = exit match
            case Exit.Failure(cause) => cause.failureOption.map(_.message).getOrElse("")
            case Exit.Success(_)     => ""
          assertTrue(exit.isFailure, message.contains("parallelism"))
        }
    },
    test("自定义 prefix 可以与同一业务进程中的其他 Agent 集群隔离") {
      val values = Map("research.worker.max_attempts" -> "13")
      AgentApplicationConfigLoader
        .load("research")
        .provide(configProvider(values))
        .map(config => assertTrue(config.worker.maxAttempts == 13))
    }
  )

  /** 为单个测试安装进程内 ConfigProvider；Map 中不需要真实环境变量，也不会污染并行测试。 */
  private def configProvider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))

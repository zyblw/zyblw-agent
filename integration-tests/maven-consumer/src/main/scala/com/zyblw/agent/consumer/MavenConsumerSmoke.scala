package com.zyblw.agent.consumer

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import com.zyblw.agent.rag.*
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.tools.*
import javax.sql.DataSource
import zio.*

/** 只依赖 Maven 制品编译的外部消费者契约。
  *
  * 除最小 ADT 外，这里刻意组装生产常用的 Worker 配置、Agent Definition、PostgreSQL 控制面、知识存储和 Durable
  * Application Layer。发布流水线由此验证公开 POM 与跨 artifact 类型确实能被独立业务项目使用，而不是只验证类文件存在。
  */
object MavenConsumerSmoke:
  private val toolPolicy = ToolPolicyConfig.secureDefault

  val applicationConfig: AgentApplicationConfig = AgentApplicationConfig(
    toolPolicy = toolPolicy,
    worker = WorkerHostConfig(parallelism = 4)
  )

  val definition: IO[AgentError.InvalidConfiguration, AgentDefinition] =
    AgentDefinitionBuilder(AgentId("maven-consumer"), "Maven consumer")
      .withInstructions("Return a deterministic consumer contract response.")
      .buildFor(toolPolicy)

  val postgresControlPlane: URLayer[
    DataSource,
    RunStore & RunCommandStore & RunSubmissionStore
  ] = PostgresAgentPersistence.layer

  val postgresKnowledge: URLayer[DataSource, KnowledgeIndexStore & VectorStore] =
    PostgresAgentPersistence.knowledge(dimension = 1024)

  val durableApplication: URLayer[AgentApplication.DurableDependencies, AgentApplication.Services] =
    AgentApplication.durable(WorkerId("maven-consumer-worker"), applicationConfig)

  val providerUnauthorized: AgentError.ModelHttpFailure =
    AgentError.ModelHttpFailure("consumer-provider", 401, Some("invalid_api_key"))

  def main(args: Array[String]): Unit =
    val agentId = AgentId("maven-consumer")
    val message = AgentMessage.user("consumer contract")

    require(agentId.value == "maven-consumer")
    require(message.text == "consumer contract")
    require(applicationConfig.worker.parallelism == 4)
    require(providerUnauthorized.category == ErrorCategory.Authentication)

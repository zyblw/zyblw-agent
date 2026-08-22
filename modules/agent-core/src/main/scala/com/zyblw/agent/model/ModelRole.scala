package com.zyblw.agent.model

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 部署侧任务角色。Agent 声明角色，宿主配置把它映射到已注册的 Provider/模型。
  *
  * 角色不是权限：它不能扩大 `allowedTools`、不能绕过审批，也不能在 Run 创建后热改映射。`AgentCommandService.submitStart` 与同步
  * `AgentRuntime.run` 在创建 Run 时调用 `ModelRoleCatalog.applyTo`，把解析后的 provider/model 写入冻结的 `AgentDefinition`
  * 与组合指纹。中途改目录会在下一次恢复被 `CompositionIncompatible` 挡住。
  */
final case class ModelRole(value: String) derives JsonCodec:
  require(ModelRole.isValid(value), "ModelRole 必须是 1..64 的小写标识")

object ModelRole:
  val Planner: ModelRole    = ModelRole("planner")
  val Summarizer: ModelRole = ModelRole("summarizer")
  val Extraction: ModelRole = ModelRole("extraction")
  val Default: ModelRole    = ModelRole("default")

  private lazy val Pattern = "[a-z][a-z0-9_-]{0,63}".r

  def isValid(value: String): Boolean = Pattern.matches(value)

  def fromString(value: String): Either[String, ModelRole] =
    Either.cond(isValid(value), ModelRole(value), s"非法 ModelRole: $value")

/** 一个角色到已注册 Provider/模型的冻结绑定。 */
final case class ModelRoleBinding(role: ModelRole, provider: String, model: String) derives JsonCodec:
  require(provider.trim.nonEmpty && model.trim.nonEmpty, "角色绑定的 provider 与 model 不能为空")

/** 部署声明的角色目录；未声明的角色 fail-closed，不会猜测成默认模型。 */
final case class ModelRoleCatalog(bindings: Map[String, ModelRoleBinding] = Map.empty) derives JsonCodec:
  /** 把 Agent 声明的角色叠加到其 `ModelSettings` 上。
    *
    * Agent 已经显式写了 provider/model 时，角色只作为审计元数据，不覆盖作者选择。只有两者都空时才使用目录。
    */
  def applyTo(settings: ModelSettings): IO[AgentError, ModelSettings] =
    settings.role match
      case None       => ZIO.succeed(settings)
      case Some(role) =>
        bindings.get(role.value) match
          case None =>
            ZIO.fail(AgentError.InvalidConfiguration(s"未声明的模型角色: ${role.value}"))
          case Some(binding) =>
            val resolved =
              if settings.provider.nonEmpty || settings.model.nonEmpty then settings
              else settings.copy(provider = Some(binding.provider), model = Some(binding.model))
            ZIO.succeed(resolved.copy(metadata = resolved.metadata.updated("model-role", role.value)))

object ModelRoleCatalog:
  val empty: ModelRoleCatalog = ModelRoleCatalog()

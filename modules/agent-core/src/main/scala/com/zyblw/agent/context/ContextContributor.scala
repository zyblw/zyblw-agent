package com.zyblw.agent.context

import com.zyblw.agent.composition.{CapabilityKind, CapabilityRef}
import com.zyblw.agent.core.*
import zio.*

/** 向模型上下文贡献一类来源的插件。
  *
  * Kernel 只通过 [[ContextSourceResolver]] 读取来源，不认识 Memory、RAG、Skill 或 Goal。新增贡献者不应修改
  * `AgentRuntimeDriver`。贡献者不能授予工具权限，也不能把不可信正文提升为 System 指令；格式化与预算仍由 [[ContextManager]] 负责。
  */
trait ContextContributor:
  /** 稳定来源 ID；进入组合指纹，不能包含 `@`。 */
  def id: String

  /** 来源实现版本；用于漂移检测，不是授权。 */
  def version: String = "1"

  /** 按权威 Run 状态选择本回合可注入的记忆、检索或安全约束。 */
  def contribute(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources]

  final def sourceId: String = capability.sourceId

  /** 进入组合指纹的身份。上下文贡献者一律归为 [[CapabilityKind.Context]]。 */
  final def capability: CapabilityRef =
    require(id.trim.nonEmpty && id.length <= 64 && !id.contains('@'), "ContextContributor.id 必须为 1..64 且不含 @")
    require(version.matches("[A-Za-z0-9._-]{1,32}"), "ContextContributor.version 只能包含安全版本字符")
    CapabilityRef(CapabilityKind.Context, id, version)

object ContextContributor:
  /** 把若干贡献者按声明顺序组成 Kernel 已认识的 Resolver。 */
  def resolver(contributors: ContextContributor*): ContextSourceResolver =
    resolver(Chunk.fromIterable(contributors))

  /** 把若干贡献者按声明顺序组成 Kernel 已认识的 Resolver。 */
  def resolver(contributors: Chunk[ContextContributor]): ContextSourceResolver =
    ContextSourceResolver.combine(contributors.map(asResolver))

  /** 单个贡献者适配为 Resolver，并暴露带类别的身份供组合指纹使用。 */
  def asResolver(contributor: ContextContributor): ContextSourceResolver =
    new ContextSourceResolver:
      override val sourceIds: Chunk[CapabilityRef] = Chunk(contributor.capability)

      def resolve(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources] =
        contributor.contribute(state, definition)

package com.zyblw.agent.harness

import com.zyblw.agent.context.{ContextDocument, ContextSources}
import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*

/** Skill 来源信任。不是授权，也不能把正文自动变成 System 指令。 */
enum SkillTrust derives JsonCodec:
  case Untrusted, Reviewed, Trusted

/** 按需加载的程序性知识。不能授予 Tool，不能写入 allowedTools。 */
final case class SkillDescriptor(
    id: String,
    version: String,
    source: String,
    trust: SkillTrust,
    body: String,
    fingerprint: String
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64 && !id.contains('@'), "SkillDescriptor.id 必须为 1..64 且不含 @")
  require(version.matches("[A-Za-z0-9._-]{1,32}"), "SkillDescriptor.version 只能包含安全版本字符")
  require(source.trim.nonEmpty && source.length <= 256, "SkillDescriptor.source 必须为 1..256 个字符")
  require(body.nonEmpty && body.length <= 16_000, "SkillDescriptor.body 必须为 1..16000 个字符")
  require(fingerprint.matches("[0-9a-f]{64}"), "SkillDescriptor.fingerprint 必须是 SHA-256 十六进制")
  require(fingerprint == SkillDescriptor.hash(body), "SkillDescriptor.fingerprint 必须等于 body 的 SHA-256")

  def sourceId: String = s"$id@$version"

  /** Skill 永远不能授予工具。 */
  def grantedTools: Set[String] = Set.empty

object SkillDescriptor:
  def hash(body: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(body.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def make(
      id: String,
      version: String,
      source: String,
      trust: SkillTrust,
      body: String
  ): SkillDescriptor =
    SkillDescriptor(id, version, source, trust, body, hash(body))

/** 把 Skill 投影为上下文数据。Kernel 与 ToolPolicy 都不读取本对象。 */
object SkillMaterializer:
  /** 始终作为检索资料注入；即使 Trusted 也不得写入 safetyInstructions / System。 */
  def toSources(skill: SkillDescriptor): ContextSources =
    val label = skill.trust match
      case SkillTrust.Trusted   => "skill-trusted"
      case SkillTrust.Reviewed  => "skill-reviewed"
      case SkillTrust.Untrusted => "skill-untrusted"
    ContextSources(retrieval =
      Chunk(
        ContextDocument(
          id = s"$label:${skill.sourceId}",
          content = skill.body,
          source = s"skill://${skill.sourceId}"
        )
      )
    )

  /** Trusted Skill 可由宿主显式提升为 Developer 指令；Reviewed/Untrusted 拒绝。永不返回 System。 */
  def asDeveloperInstruction(skill: SkillDescriptor): IO[AgentError.InvalidConfiguration, InstructionBlock] =
    skill.trust match
      case SkillTrust.Trusted =>
        ZIO.succeed(
          InstructionBlock(s"skill.${skill.id}", InstructionAuthority.Developer, skill.body, skill.version)
        )
      case _ =>
        ZIO.fail(AgentError.InvalidConfiguration(s"不可信 Skill ${skill.sourceId} 不能提升为 Developer 指令"))

  /** Skill 不能成为 System 指令，包括 Trusted。 */
  def asSystemInstruction(skill: SkillDescriptor): IO[AgentError.InvalidConfiguration, InstructionBlock] =
    val _ = skill
    ZIO.fail(AgentError.InvalidConfiguration("Skill 不能提升为 System 指令"))

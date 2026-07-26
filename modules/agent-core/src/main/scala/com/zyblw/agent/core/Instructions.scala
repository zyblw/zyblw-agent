package com.zyblw.agent.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*

/** 稳定指令块的权威级别。
  *
  * 这里只允许框架拥有的 `System` 和业务应用拥有的 `Developer`。用户输入、Memory、RAG 和工具结果属于不可信运行时数据， 不能伪装成 InstructionBlock。
  */
enum InstructionAuthority derives JsonCodec:
  case System, Developer

/** 一段可独立版本化的可信指令。
  *
  * `id + version` 用于评测、Trace 和回滚定位，不把完整 Prompt 写入日志。正文仍随 AgentDefinition 快照持久化，保证恢复时 不会误用进程内更新后的指令。
  */
final case class InstructionBlock(
    id: String,
    authority: InstructionAuthority,
    content: String,
    version: String = "1"
) derives JsonCodec

/** 按声明顺序冻结的分层指令集合。
  *
  * System 块始终先于 Developer 块；同一权威级别按 Builder 调用顺序稳定渲染。Fingerprint 只用于版本关联，不应作为 鉴权依据。为了兼容早期直接构造的
  * AgentDefinition，`instructionSet = None` 时 ContextManager 仍读取旧 `instructions`。
  */
final case class InstructionSet(blocks: Chunk[InstructionBlock]) derives JsonCodec:
  /** 把可信指令编译为至多一条 System 和一条 Developer 消息，形成 cache-friendly 稳定前缀。 */
  def messages: Chunk[AgentMessage] =
    val system    = render(InstructionAuthority.System)
    val developer = render(InstructionAuthority.Developer)
    Chunk.fromIterable(
      system.map(AgentMessage.system).toList ++ developer.map(AgentMessage.developer).toList
    )

  /** 返回不泄漏正文的稳定 SHA-256，供 Trace、评测数据集和发布版本关联。 */
  lazy val fingerprint: String =
    val canonical = blocks
      .map { block =>
        s"${block.authority}:${block.id.length}:${block.id}:${block.version.length}:${block.version}:${block.content.length}:${block.content}"
      }
      .mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def render(authority: InstructionAuthority): Option[String] =
    val selected = blocks.filter(_.authority == authority)
    Option.when(selected.nonEmpty)(
      selected.map(block => s"[instruction:${block.id}@${block.version}]\n${block.content}").mkString("\n\n")
    )

object InstructionSet:
  private val SafeIdentifier     = "[A-Za-z0-9._-]{1,100}".r
  private val MaxTotalCharacters = 100_000

  /** 规范化并校验指令集合。
    *
    * @return
    *   空集合、重复 ID、非法版本、空正文、总量超限或 System/Developer 顺序反转时返回 typed 配置错误
    */
  def make(blocks: Iterable[InstructionBlock]): IO[AgentError.InvalidConfiguration, InstructionSet] =
    val normalized = Chunk
      .fromIterable(blocks)
      .map(block =>
        block.copy(id = block.id.trim, content = block.content.trim, version = block.version.trim)
      )
    val duplicateIds = normalized
      .groupMapReduce(_.id)(_ => 1)(_ + _)
      .collect { case (id, count) if count > 1 => id }
      .toList
      .sorted
    val firstDeveloper       = normalized.indexWhere(_.authority == InstructionAuthority.Developer)
    val systemAfterDeveloper =
      firstDeveloper >= 0 && normalized
        .drop(firstDeveloper + 1)
        .exists(_.authority == InstructionAuthority.System)
    val invalidIdentifier =
      normalized.find(block => !SafeIdentifier.matches(block.id) || !SafeIdentifier.matches(block.version))
    val invalidContent = normalized.find(block =>
      block.content.isEmpty ||
        block.content.exists(ch => ch.isControl && ch != '\n' && ch != '\r' && ch != '\t')
    )
    val totalCharacters = normalized.foldLeft(0L)((total, block) => total + block.content.length.toLong)
    val error           =
      Option
        .when(normalized.isEmpty)("InstructionSet 至少需要一个指令块")
        .orElse(Option.when(duplicateIds.nonEmpty)(s"重复指令 ID: ${duplicateIds.mkString(",")}"))
        .orElse(invalidIdentifier.map(_ => "指令 id/version 只能包含 1..100 个字母、数字、点、下划线或连字符"))
        .orElse(invalidContent.map(block => s"指令 ${block.id.take(100)} 的正文为空或包含非法控制字符"))
        .orElse(Option.when(totalCharacters > MaxTotalCharacters)(s"全部指令正文总长度不能超过 $MaxTotalCharacters"))
        .orElse(Option.when(systemAfterDeveloper)("System 指令必须位于所有 Developer 指令之前"))
    error match
      case Some(message) => ZIO.fail(AgentError.InvalidConfiguration(message))
      case None          => ZIO.succeed(InstructionSet(normalized))

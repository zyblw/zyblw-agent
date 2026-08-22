package com.zyblw.agent.extension

import com.zyblw.agent.composition.*
import com.zyblw.agent.context.{ContextPayloadSensitivity, ContextSectionSnapshot}
import com.zyblw.agent.core.*
import com.zyblw.agent.execution.{ExecutionEnvironment, LocalExecutionEnvironment}
import com.zyblw.agent.harness.{SkillDescriptor, SkillTrust}
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*

/** 扩展的职责种类。每种扩展只做这一件事，禁止万能 Plugin。 */
enum ExtensionKind derives JsonCodec:
  case Context, Tools, Skills, Guardrail, ApprovalReview, RunLifecycle, ToolLifecycle, Artifacts, Telemetry,
    ExecutionEnvironment

/** 进入组合指纹的扩展身份。`id@version` 与 [[com.zyblw.agent.context.ContextContributor.sourceId]] 同一形状。 */
final case class ExtensionDescriptor(
    kind: ExtensionKind,
    id: String,
    version: String = "1"
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64 && !id.contains('@'), "ExtensionDescriptor.id 必须为 1..64 且不含 @")
  require(version.matches("[A-Za-z0-9._-]{1,32}"), "ExtensionDescriptor.version 只能包含安全版本字符")

  def sourceId: String = s"$id@$version"

/** Host 显式交给扩展的稳定输入。
  *
  * 扩展永远拿不到 `AgentRuntimeLive`、`RunStore` 或可变 Kernel 内部。授权上下文已经是摘要：租户/主体/scope 的明文不在这里重复出现，模型输出也不能构造本对象。
  */
final case class ExtensionInput(
    runId: RunId,
    agentId: AgentId,
    authorization: AuthorizationFingerprint,
    composition: Option[RuntimeCompositionFingerprint]
) derives JsonCodec

/** 装配期工具目录。Kernel 执行仍走 [[RegisteredToolRegistry]]；本 trait 只提供目录与组合身份。 */
trait ToolProvider:
  def descriptor: ExtensionDescriptor
  def tools: UIO[Chunk[RegisteredTool]]

object ToolProvider:
  /** 把已物化的工具包成带稳定身份的目录。 */
  def static(
      id: String,
      registered: Iterable[RegisteredTool],
      version: String = "1"
  ): ToolProvider =
    val snapshot = Chunk.fromIterable(registered)
    new ToolProvider:
      val descriptor = ExtensionDescriptor(ExtensionKind.Tools, id, version)
      def tools      = ZIO.succeed(snapshot)

  /** 合并若干目录为唯一注册表；重名在装配期失败。 */
  def registry(
      providers: Iterable[ToolProvider]
  ): IO[AgentError.InvalidConfiguration, RegisteredToolRegistry] =
    ZIO.foreach(Chunk.fromIterable(providers))(_.tools).flatMap { catalogs =>
      RegisteredToolRegistry.make(catalogs.flatten)
    }

/** 审批评审的唯一出口。推荐放行不能跳过人工审批或修改 [[ApprovalSubject]]。 */
enum ApprovalReview derives JsonCodec:
  case Abstain
  case Deny(reason: String)
  case RecommendAllow(reason: String)

trait ApprovalReviewer:
  def descriptor: ExtensionDescriptor

  /** 只读取主体与 Host 输入；返回值不能改冻结计划、不能提交状态。 */
  def review(subject: ApprovalSubject, input: ExtensionInput): UIO[ApprovalReview]

/** 观察工具生命周期。全部为 `UIO`，失败不得反向破坏执行。 */
trait ToolLifecycleObserver:
  def descriptor: ExtensionDescriptor
  def onPrepared(callId: String, capability: String): UIO[Unit] =
    val _ = (callId, capability)
    ZIO.unit

  def onStarted(callId: String, capability: String): UIO[Unit] =
    val _ = (callId, capability)
    ZIO.unit

  def onCompleted(callId: String, capability: String): UIO[Unit] =
    val _ = (callId, capability)
    ZIO.unit

  def onFailed(callId: String, capability: String, category: String): UIO[Unit] =
    val _ = (callId, capability, category)
    ZIO.unit

/** Skill 目录条目：有身份与信任，没有正文，也不能授予工具。 */
final case class SkillCatalogEntry(
    id: String,
    version: String,
    source: String,
    trust: SkillTrust,
    fingerprint: String
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64 && !id.contains('@'), "SkillCatalogEntry.id 必须为 1..64 且不含 @")
  require(version.matches("[A-Za-z0-9._-]{1,32}"), "SkillCatalogEntry.version 只能包含安全版本字符")
  require(source.trim.nonEmpty && source.length <= 256, "SkillCatalogEntry.source 必须为 1..256 个字符")
  require(fingerprint.matches("[0-9a-f]{64}"), "SkillCatalogEntry.fingerprint 必须是 SHA-256 十六进制")

  def sourceId: String          = s"$id@$version"
  def grantedTools: Set[String] = Set.empty

object SkillCatalogEntry:
  def of(skill: SkillDescriptor): SkillCatalogEntry =
    SkillCatalogEntry(skill.id, skill.version, skill.source, skill.trust, skill.fingerprint)

/** 把 Skill 目录投影为 Metadata section：只有身份，没有正文。 */
/** 目录级指纹：排序后的 id@version + 单条 fingerprint + trust，不含正文。 */
object SkillCatalogSignature:
  def fingerprint(entries: Chunk[SkillCatalogEntry]): String =
    val canonical = entries
      .map(entry => s"${entry.sourceId}\t${entry.trust}\t${entry.fingerprint}")
      .sorted
      .mkString("\n")
    ContextSectionSnapshot.sha256(canonical)

object SkillCatalogSection:
  def snapshot(entries: Chunk[SkillCatalogEntry]): ContextSectionSnapshot =
    val payload = entries
      .map { entry =>
        s"${entry.sourceId}\tsource=${entry.source}\ttrust=${entry.trust}\tfp=${entry.fingerprint.take(8)}"
      }
      .mkString("\n")
    ContextSectionSnapshot.of("skill-catalog", payload, ContextPayloadSensitivity.Metadata)

/** 目录先行、按需取正文。正文加载后仍须经 [[com.zyblw.agent.harness.SkillMaterializer]] 投影，不能升 System。 */
trait SkillProvider:
  def descriptor: ExtensionDescriptor
  def catalog: UIO[Chunk[SkillCatalogEntry]]
  def load(id: String, version: String): IO[AgentError, SkillDescriptor]

object SkillProvider:
  /** 测试与进程内目录；生产应按 Wave 2 接到 HarnessStore，而不是在 Kernel 里再存一份正文。 */
  def static(id: String, skills: Chunk[SkillDescriptor], version: String = "1"): SkillProvider =
    val byKey = skills.map(skill => (skill.id, skill.version) -> skill).toMap
    new SkillProvider:
      val descriptor = ExtensionDescriptor(ExtensionKind.Skills, id, version)
      def catalog    = ZIO.succeed(skills.map(SkillCatalogEntry.of))
      def load(skillId: String, skillVersion: String): IO[AgentError, SkillDescriptor] =
        ZIO
          .fromOption(byKey.get(skillId -> skillVersion))
          .orElseFail(AgentError.InvalidConfiguration(s"Skill $skillId@$skillVersion 不在目录中"))

/** 一次部署装配的 typed extension 集合。Kernel 拥有生命周期；扩展只贡献能力。 */
final case class RuntimeExtensions(
    toolProviders: Chunk[ToolProvider] = Chunk.empty,
    skillProviders: Chunk[SkillProvider] = Chunk.empty,
    approvalReviewers: Chunk[ApprovalReviewer] = Chunk.empty,
    toolLifecycleObservers: Chunk[ToolLifecycleObserver] = Chunk.empty,
    environment: ExecutionEnvironment = LocalExecutionEnvironment.default
):
  val descriptors: Chunk[ExtensionDescriptor] =
    toolProviders.map(_.descriptor) ++
      skillProviders.map(_.descriptor) ++
      approvalReviewers.map(_.descriptor) ++
      toolLifecycleObservers.map(_.descriptor)

  val sourceIds: Chunk[String] =
    Chunk.fromIterable(descriptors.map(_.sourceId).toList.distinct.sorted)

  require(
    descriptors.map(_.sourceId).toList.distinct.length == descriptors.length,
    "扩展 id@version 不能重复"
  )

object RuntimeExtensions:
  val empty: RuntimeExtensions                                   = RuntimeExtensions()
  val emptyLayer: ULayer[RuntimeExtensions]                      = ZLayer.succeed(empty)
  def layer(value: RuntimeExtensions): ULayer[RuntimeExtensions] = ZLayer.succeed(value)

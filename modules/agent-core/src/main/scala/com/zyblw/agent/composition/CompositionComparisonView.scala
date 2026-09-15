package com.zyblw.agent.composition

import zio.Chunk
import zio.json.JsonCodec

/** 一侧组合指纹的低敏投影。
  *
  * 摘要类字段只取前缀：前缀足以判断"两侧是不是同一份"，完整摘要没有额外诊断价值，却会把可用于比对的指纹散播到管理面日志与 事故包里。工具名、来源 ID 与扩展 ID
  * 是装配身份而不是业务数据，因此完整保留——排查漂移时"少了哪个工具"正是要看的东西。
  */
final case class CompositionFacetView(
    fingerprintPrefix: String,
    profileId: String,
    instructionFingerprintPrefix: Option[String],
    modelRefPrefix: String,
    capturePolicy: String,
    allowedTools: Chunk[String],
    sourceIds: Chunk[CapabilityRef],
    extensionIds: Chunk[CapabilityRef],
    environmentId: String,
    permissionFingerprintPrefix: String,
    modelRoutingFingerprintPrefix: Option[String],
    modelPricingFingerprintPrefix: Option[String]
) derives JsonCodec

object CompositionFacetView:
  /** 摘要前缀长度。16 个十六进制字符足以在一个部署内区分不同指纹。 */
  val PrefixLength = 16

  def of(fingerprint: RuntimeCompositionFingerprint): CompositionFacetView =
    CompositionFacetView(
      fingerprintPrefix = fingerprint.value.take(PrefixLength),
      profileId = fingerprint.profileId,
      instructionFingerprintPrefix = fingerprint.instructionFingerprint.map(_.take(PrefixLength)),
      modelRefPrefix = fingerprint.modelRef.take(PrefixLength),
      capturePolicy = fingerprint.capturePolicy.toString,
      allowedTools = Chunk.fromIterable(fingerprint.allowedTools.toList.sorted),
      sourceIds = CapabilityRef.normalize(fingerprint.sourceIds),
      extensionIds = CapabilityRef.normalize(fingerprint.extensionIds),
      environmentId = fingerprint.executionEnvironmentId,
      permissionFingerprintPrefix = fingerprint.permissionProfileFingerprint.take(PrefixLength),
      modelRoutingFingerprintPrefix = fingerprint.modelRoutingFingerprint.map(_.take(PrefixLength)),
      modelPricingFingerprintPrefix = fingerprint.modelPricingFingerprint.map(_.take(PrefixLength))
    )

/** 冻结组合与现场组合的对照，供管理面与事故包共用。
  *
  * @param live
  *   现场侧。`None` 表示消费方没有拿到现场组合读出口（例如只读数据库的导出工具）；此时 `driftKind` 同样为 `None`，绝不 伪造一个"无漂移"的结论。
  */
final case class CompositionComparisonView(
    frozen: CompositionFacetView,
    live: Option[CompositionFacetView],
    driftKind: Option[String],
    changedFields: Chunk[CompositionDriftField]
) derives JsonCodec

object CompositionComparisonView:
  def of(
      frozen: RuntimeCompositionFingerprint,
      live: Option[RuntimeCompositionFingerprint]
  ): CompositionComparisonView =
    val drift = live.map(RuntimeComposition.compare(frozen, _))
    CompositionComparisonView(
      frozen = CompositionFacetView.of(frozen),
      live = live.map(CompositionFacetView.of),
      driftKind = drift.map(_.code),
      changedFields = drift.fold(Chunk.empty)(_.changedFields)
    )

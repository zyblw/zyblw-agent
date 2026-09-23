package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** Provider 编码角色；与治理用途 `EmbeddingPurpose` 分离。 */
enum EmbeddingInputRole:
  case Query, Document

enum EmbeddingOutputKind:
  case Dense, Sparse, DenseAndSparse

enum InstructionPolicy:
  case Unsupported, QueryOnly, QueryAndDocument

final case class EmbeddingInstruction(id: String, version: String, text: String):
  require(id.trim.nonEmpty && id.length <= 128, "EmbeddingInstruction.id 长度必须位于 1..128")
  require(version.trim.nonEmpty && version.length <= 64, "EmbeddingInstruction.version 长度必须位于 1..64")
  require(text.nonEmpty && text.length <= 4000, "EmbeddingInstruction.text 长度必须位于 1..4000")
  require(!id.contains('\n') && !version.contains('\n'), "instruction 身份不能换行")

final case class EmbeddingCapabilities(
    inputRoles: Set[EmbeddingInputRole],
    outputs: Set[EmbeddingOutputKind],
    minDenseDimension: Int,
    maxDenseDimension: Int,
    defaultDenseDimension: Int,
    maxTextsPerRequest: Int,
    maxTokensPerRequest: Option[Long] = None,
    instructionPolicy: InstructionPolicy = InstructionPolicy.Unsupported,
    reportsUsage: Boolean = false,
    /** 与切分计数器 `TokenCounter.id` 对齐的 tokenizer。未声明时索引器不假装已经对齐。 */
    tokenizerId: Option[String] = None
):
  require(inputRoles.nonEmpty && outputs.nonEmpty, "Embedding 能力不能为空")
  require(tokenizerId.forall(id => id.trim.nonEmpty && id == id.trim), "tokenizerId 不能为空白")
  require(minDenseDimension > 0 && maxDenseDimension >= minDenseDimension, "Embedding 维度范围无效")
  require(
    defaultDenseDimension >= minDenseDimension && defaultDenseDimension <= maxDenseDimension,
    "默认维度必须落在能力范围内"
  )
  require(maxTextsPerRequest > 0, "maxTextsPerRequest 必须为正数")
  require(maxTokensPerRequest.forall(_ > 0L), "maxTokensPerRequest 必须为正数")

final case class EmbeddingProviderDescriptorV2(
    provider: String,
    model: String,
    capabilities: EmbeddingCapabilities
):
  require(provider.trim.nonEmpty, "Embedding provider 不能为空")
  require(model.trim.nonEmpty, "Embedding model 不能为空")

  def denseDescriptor: EmbeddingProviderDescriptor =
    EmbeddingProviderDescriptor(
      provider,
      model,
      capabilities.defaultDenseDimension,
      capabilities.maxTextsPerRequest,
      capabilities.minDenseDimension != capabilities.maxDenseDimension
    )

final case class EmbeddingRequest(
    texts: Chunk[String],
    role: EmbeddingInputRole,
    output: EmbeddingOutputKind = EmbeddingOutputKind.Dense,
    dimension: Option[Int] = None,
    instruction: Option[EmbeddingInstruction] = None,
    context: EmbeddingRequestContext
):
  require(texts.nonEmpty, "EmbeddingRequest.texts 不能为空")
  require(texts.forall(_.nonEmpty), "Embedding 文本不能为空串")
  require(dimension.forall(_ > 0), "请求维度必须为正数")

final case class SparseEmbeddingEntry(index: Int, value: Float):
  require(index >= 0, "sparse index 不能为负")
  require(java.lang.Float.isFinite(value) && value != 0.0f, "sparse value 必须是非零有限数")

final case class SparseEmbedding(dimension: Int, entries: Chunk[SparseEmbeddingEntry]):
  require(dimension > 0, "sparse dimension 必须为正数")
  require(entries.map(_.index).toSet.size == entries.length, "sparse index 必须唯一")
  require(entries.forall(_.index < dimension), "sparse index 必须小于 dimension")
  require(entries.map(_.index) == entries.map(_.index).sorted, "sparse index 必须升序")

  def requireWithinNnz(maxNnz: Int): Either[String, SparseEmbedding] =
    Either.cond(entries.length <= maxNnz, this, s"sparse NNZ ${entries.length} 超过容量门禁 $maxNnz")

object SparseEmbedding:
  def validated(dimension: Int, entries: Chunk[SparseEmbeddingEntry]): Either[String, SparseEmbedding] =
    val sorted = entries.sortBy(_.index)
    Either.cond(
      sorted.map(_.index).toSet.size == sorted.length && sorted.forall(e => e.index < dimension),
      SparseEmbedding(dimension, sorted),
      "非法 sparse embedding"
    )

final case class EmbeddingItem(dense: Option[Embedding], sparse: Option[SparseEmbedding]):
  require(dense.nonEmpty || sparse.nonEmpty, "EmbeddingItem 至少要有 dense 或 sparse")
  require(dense.forall(_.values.forall(java.lang.Float.isFinite)), "dense 值必须有限")

final case class EmbeddingResponse(
    items: Chunk[EmbeddingItem],
    descriptor: EmbeddingProviderDescriptorV2,
    usage: Option[EmbeddingUsage] = None,
    providerRequestId: Option[String] = None
):
  def denseEmbeddings: Chunk[Embedding] =
    items.flatMap(_.dense)

/** 唯一 Embedding SPI。能力不匹配必须失败，不得静默降级。 */
trait EmbeddingModel:
  def capabilities: EmbeddingCapabilities
  def descriptor: EmbeddingProviderDescriptorV2

  def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse]

  final def validate(request: EmbeddingRequest): IO[RetrievalError, Unit] =
    val caps = capabilities
    val dim  = request.dimension.getOrElse(caps.defaultDenseDimension)
    if !caps.inputRoles.contains(request.role) then
      ZIO.fail(AgentError.RetrievalFailed(s"Embedding 不支持 role=${request.role}"))
    else if !caps.outputs.contains(request.output) then
      ZIO.fail(AgentError.RetrievalFailed(s"Embedding 不支持 output=${request.output}"))
    else if dim < caps.minDenseDimension || dim > caps.maxDenseDimension then
      ZIO.fail(AgentError.RetrievalFailed(s"Embedding 维度 $dim 超出能力范围"))
    else if request.texts.length > caps.maxTextsPerRequest then
      ZIO.fail(AgentError.RetrievalFailed(s"Embedding 批次超过 ${caps.maxTextsPerRequest}"))
    else if request.instruction.nonEmpty && caps.instructionPolicy == InstructionPolicy.Unsupported then
      ZIO.fail(AgentError.RetrievalFailed("Embedding 不支持 instruction"))
    else if request.instruction.nonEmpty && request.role == EmbeddingInputRole.Document &&
      caps.instructionPolicy == InstructionPolicy.QueryOnly
    then ZIO.fail(AgentError.RetrievalFailed("Embedding instruction 仅允许 Query"))
    else ZIO.unit

object EmbeddingModel:
  /** 测试与示例用的固定 dense 模型。 */
  def stub(
      provider: String = "test",
      model: String = "v1",
      dimension: Int = 2,
      onEmbed: Chunk[String] => UIO[Chunk[Embedding]] = texts =>
        ZIO.succeed(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))
  ): EmbeddingModel =
    val caps = EmbeddingDefaults
      .denseCapabilities(dimension)
      .copy(tokenizerId = Some(ChunkEmbeddingAlignment.TestTokenizerId))
    new EmbeddingModel:
      override val capabilities: EmbeddingCapabilities       = caps
      override val descriptor: EmbeddingProviderDescriptorV2 =
        EmbeddingProviderDescriptorV2(provider, model, caps)
      def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
        validate(request) *>
          onEmbed(request.texts).map { embeddings =>
            EmbeddingResponse(
              embeddings.map(embedding => EmbeddingItem(Some(embedding), None)),
              descriptor
            )
          }

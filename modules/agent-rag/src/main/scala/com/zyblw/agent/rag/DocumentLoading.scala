package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

/** 一份待解析文档的单次字节来源。
  *
  * `content` 使用 ZStream 而不是 `Array[Byte]`，让对象存储、HTTP Body、数据库大对象和本地文件都能以背压方式接入。 Loader
  * 仍必须实施自己的最大字节数，因为背压只能控制速率，不能自动限制总量。该流按一次性资源理解；重试时业务 Adapter 必须重新创建 `DocumentInput`，不能假设同一网络 Body 可以重复消费。
  *
  * @param id
  *   租户内稳定文档 ID，最终必须与 SourceDocument.id 完全一致
  * @param sourceUri
  *   可进入引用的稳定定位符；不得包含临时签名、密码或 token
  * @param fileName
  *   仅用于格式检测的安全文件名，不是宿主文件系统路径
  * @param declaredMediaType
  *   可信业务层声明的 MIME type，小写且不带参数
  * @param declaredLength
  *   上游已知字节数；未知时为 None，Loader 仍会从流中实施硬上限
  * @param metadata
  *   业务提供的低敏元数据；解析器不能用文档内字段覆盖这些可信字段
  * @param content
  *   一次性、有背压、可取消的字节流
  */
final case class DocumentInput(
    id: String,
    sourceUri: String,
    fileName: String,
    declaredMediaType: String,
    declaredLength: Option[Long],
    metadata: Map[String, String],
    content: ZStream[Any, RetrievalError, Byte]
):
  require(id.trim.nonEmpty && id.length <= 500, "DocumentInput.id 长度必须位于 1..500")
  require(sourceUri.trim.nonEmpty && sourceUri.length <= 4000, "DocumentInput.sourceUri 长度必须位于 1..4000")
  require(
    fileName.trim.nonEmpty && fileName.length <= 500 && !fileName.contains('\u0000'),
    "DocumentInput.fileName 无效"
  )
  require(
    declaredMediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"),
    "DocumentInput.declaredMediaType 必须是小写且不带参数的 MIME type"
  )
  require(declaredLength.forall(_ >= 0L), "DocumentInput.declaredLength 不能为负数")

object DocumentInput:
  /** 从内存字节创建可重复构造的测试/小文件输入。 生产对象存储应直接提供流，避免先把大文件完整读入 JVM 堆。
    */
  def fromBytes(
      id: String,
      sourceUri: String,
      fileName: String,
      declaredMediaType: String,
      bytes: Chunk[Byte],
      metadata: Map[String, String] = Map.empty
  ): DocumentInput = DocumentInput(
    id,
    sourceUri,
    fileName,
    declaredMediaType,
    Some(bytes.length.toLong),
    metadata,
    ZStream.fromChunk(bytes)
  )

/** Loader 注册表的输出治理策略。
  *
  * @param maxExtractedCodePoints
  *   单文档最多进入切分器的 Unicode code point 数
  * @param maxMetadataEntries
  *   可信业务元数据与解析元数据合并后的最大键数
  * @param maxMetadataValueLength
  *   单个元数据值最大字符数，避免标题/作者字段变成隐蔽正文通道
  */
final case class DocumentLoadPolicy(
    maxExtractedCodePoints: Int = 2_000_000,
    maxMetadataEntries: Int = 64,
    maxMetadataValueLength: Int = 1000
):
  require(maxExtractedCodePoints > 0, "maxExtractedCodePoints 必须为正数")
  require(maxMetadataEntries > 0 && maxMetadataEntries <= 256, "maxMetadataEntries 必须位于 1..256")
  require(
    maxMetadataValueLength > 0 && maxMetadataValueLength <= 10_000,
    "maxMetadataValueLength 必须位于 1..10000"
  )

/** 单一格式族的文档解析 SPI。
  *
  * 实现只负责把字节转成文本和低敏解析元数据，不负责 tenant/permission、Embedding 或数据库事务。PDF/EPUB 等复杂格式 应位于可选 integration 模块，避免其解析依赖进入
  * `agent-rag` 核心。
  */
trait DocumentLoader:
  /** 稳定实现 ID，进入索引 metadata 和评测报告，例如 `apache-tika-3`. */
  def id: String

  /** 该实现接受的规范 MIME type。 */
  def supportedMediaTypes: Set[String]

  /** 消费一次输入流并返回一份同身份文本；失败必须使用 RetrievalError。 */
  def load(input: DocumentInput): IO[RetrievalError, SourceDocument]

/** 根据 MIME type 选择 Loader，并在解析后重新建立可信身份/元数据边界。 */
trait DocumentLoaderRegistry:
  /** 解析一份文档；未注册类型、身份漂移、空正文或输出超限均 fail-closed。 */
  def load(input: DocumentInput): IO[RetrievalError, SourceDocument]

object DocumentLoaderRegistry:
  /** 构造不可变注册表。同一 MIME type 只能由一个 Loader 拥有，避免 classpath/注册顺序改变生产行为。
    *
    * @param loaders
    *   显式注册的格式实现
    * @param policy
    *   输出正文和元数据硬上限
    */
  def make(
      loaders: Chunk[DocumentLoader],
      policy: DocumentLoadPolicy = DocumentLoadPolicy()
  ): IO[RetrievalError, DocumentLoaderRegistry] =
    val registrations =
      loaders.flatMap(loader => Chunk.fromIterable(loader.supportedMediaTypes.map(_ -> loader)))
    val duplicate =
      registrations.groupBy(_._1).collectFirst { case (mediaType, values) if values.length > 1 => mediaType }
    duplicate match
      case Some(mediaType) =>
        ZIO.fail(AgentError.RetrievalFailed(s"DocumentLoader MIME type 重复注册: $mediaType"))
      case None =>
        val byMediaType = registrations.toMap
        ZIO.succeed(
          new DocumentLoaderRegistry:
            def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
              byMediaType.get(input.declaredMediaType) match
                case None =>
                  ZIO.fail(
                    AgentError.RetrievalFailed(
                      s"不支持的文档 MIME type: ${input.declaredMediaType}"
                    )
                  )
                case Some(loader) =>
                  loader.load(input).flatMap(document => validate(input, loader, document, policy))
        )

  /** Loader 是不可信解析边界：不得改变 ID/sourceUri，不得用文档内 metadata 覆盖业务字段。 框架固定写入 `contentTrust=untrusted`，提醒 Context
    * 层把文档文本始终当数据而非指令。
    */
  private def validate(
      input: DocumentInput,
      loader: DocumentLoader,
      document: SourceDocument,
      policy: DocumentLoadPolicy
  ): IO[RetrievalError, SourceDocument] =
    val codePoints     = document.text.codePointCount(0, document.text.length)
    val parserMetadata = document.metadata.filterNot((key, _) => input.metadata.contains(key))
    val merged         = parserMetadata ++ input.metadata ++ Map(
      "loaderId"          -> loader.id,
      "declaredMediaType" -> input.declaredMediaType,
      "contentTrust"      -> "untrusted"
    )
    val metadataValid = merged.size <= policy.maxMetadataEntries && merged.forall { case (key, value) =>
      key.matches("[A-Za-z0-9_.-]{1,100}") && value.length <= policy.maxMetadataValueLength && !value
        .contains('\u0000')
    }
    if document.id != input.id || document.sourceUri != input.sourceUri then
      ZIO.fail(AgentError.RetrievalFailed("DocumentLoader 输出身份与输入不一致"))
    else if document.text.trim.isEmpty then ZIO.fail(AgentError.RetrievalFailed("DocumentLoader 未提取到可索引正文"))
    else if codePoints > policy.maxExtractedCodePoints then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"DocumentLoader 正文长度 $codePoints 超过上限 ${policy.maxExtractedCodePoints}"
        )
      )
    else if !metadataValid then ZIO.fail(AgentError.RetrievalFailed("DocumentLoader metadata 超过数量、键或值边界"))
    else ZIO.succeed(document.copy(metadata = merged))

/** 一份流式摄取请求；tenant、permissions 与 ingestionId 必须来自可信业务控制面。 */
final case class DocumentIngestionRequest(
    input: DocumentInput,
    tenantId: TenantId,
    permissions: Set[String],
    ingestionId: String,
    expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion
):
  require(ingestionId.trim.nonEmpty && ingestionId.length <= 500, "Document ingestionId 长度必须位于 1..500")

/** 批量摄取的错误传播方式。 */
enum DocumentIngestionFailureMode:
  /** 任一文档失败就让输出流失败，适合必须全批成功的受控发布任务。 */
  case FailFast

  /** 把每份失败转成低敏结果并继续处理其他文档，适合后台导入队列。 */
  case Continue

/** 每份文档的稳定摄取结果；失败不保存解析器异常消息或文档正文。 */
enum DocumentIngestionOutcome:
  case Indexed(documentId: String, result: KnowledgeIndexResult)
  case Failed(documentId: String, category: ErrorCategory, retryable: Boolean)

/** 把 DocumentLoaderRegistry 与 KnowledgeIndexer 组合成有背压的多文档摄取入口。
  *
  * `mapZIOPar` 让不同文档用 Fiber 并发，同时输出顺序保持与输入一致；每份文档内部仍沿用 KnowledgeIndexer 的 Building→stage→activate
  * 耐久协议。取消输出流会中断仍在运行的解析/Embedding Fiber。
  *
  * @param loaders
  *   MIME 路由和输出治理注册表
  * @param indexer
  *   单文档耐久索引器
  * @param maxParallelism
  *   最大并发文档数，应同时受 Provider 与连接池容量约束
  * @param failureMode
  *   单文档失败是终止整流还是形成低敏结果
  */
final class DocumentIngestionService(
    loaders: DocumentLoaderRegistry,
    indexer: KnowledgeIndexer,
    maxParallelism: Int = 2,
    failureMode: DocumentIngestionFailureMode = DocumentIngestionFailureMode.Continue
):
  require(maxParallelism > 0 && maxParallelism <= 64, "Document ingestion maxParallelism 必须位于 1..64")

  /** 持续消费请求流并逐项输出结果，不把所有文件或结果一次性收集到内存。
    * @param requests
    *   可来自有界 Queue、Kafka consumer、数据库 claim 或测试流
    */
  def ingest(
      requests: ZStream[Any, RetrievalError, DocumentIngestionRequest]
  ): ZStream[Any, RetrievalError, DocumentIngestionOutcome] =
    requests.mapZIOPar(maxParallelism) { request =>
      val process = for
        document <- loaders.load(request.input)
        result   <- indexer.index(
          document,
          request.tenantId,
          request.permissions,
          request.ingestionId,
          request.expectation
        )
      yield DocumentIngestionOutcome.Indexed(document.id, result)
      failureMode match
        case DocumentIngestionFailureMode.FailFast => process
        case DocumentIngestionFailureMode.Continue =>
          process.catchAll(error =>
            ZIO.succeed(DocumentIngestionOutcome.Failed(request.input.id, error.category, error.retryable))
          )
    }

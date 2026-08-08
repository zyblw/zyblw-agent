package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import scala.util.Try

/** OpenAI `/embeddings` wire schema 的部署配置。
  *
  * 该协议也可用于明确兼容其请求/响应结构的国内服务，但 Provider ID、模型、维度和 endpoint 必须显式配置， 不能因为聊天接口兼容就假设 Embedding 接口也存在。
  *
  * @param providerId
  *   路由、指标与索引 manifest 使用的稳定 ID，例如 `openai-embeddings`
  * @param baseUrl
  *   API 根地址，通常以 `/v1` 结束
  * @param apiKey
  *   Bearer Token，只能来自 Secret Manager 或环境变量
  * @param model
  *   真实 Embedding 模型名；切换后必须创建新索引版本
  * @param dimension
  *   期望响应维度；同时作为可选 `dimensions` 请求参数
  * @param sendDimensions
  *   是否向兼容服务发送 `dimensions`；只有明确支持时才能开启
  * @param maxBatchSize
  *   一个 HTTP 请求的最大文本数
  * @param maxParallelBatches
  *   同一逻辑调用最多并发多少个 HTTP 子批次
  * @param maxCharactersPerText
  *   单条文本的本地硬上限，避免异常文档制造无限请求
  * @param maxCharactersPerBatch
  *   单个 HTTP 子批次的总字符硬上限
  * @param requestTimeout
  *   每个 HTTP 子批次的独立超时
  * @param defaultOptions
  *   只允许 `user` 等白名单顶层字段，不能覆盖框架管理字段
  */
final case class OpenAICompatibleEmbeddingConfig(
    providerId: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    dimension: Int,
    sendDimensions: Boolean = true,
    maxBatchSize: Int = 128,
    maxParallelBatches: Int = 4,
    maxCharactersPerText: Int = 100_000,
    maxCharactersPerBatch: Int = 500_000,
    requestTimeout: Duration = 60.seconds,
    defaultOptions: Map[String, Json] = Map.empty
):
  require(providerId.trim.nonEmpty, "providerId 不能为空")
  require(baseUrl.trim.nonEmpty, "baseUrl 不能为空")
  require(apiKey.trim.nonEmpty, "apiKey 不能为空")
  require(model.trim.nonEmpty, "model 不能为空")
  require(dimension > 0, "dimension 必须为正数")
  require(maxBatchSize > 0, "maxBatchSize 必须为正数")
  require(maxParallelBatches > 0, "maxParallelBatches 必须为正数")
  require(maxCharactersPerText > 0, "maxCharactersPerText 必须为正数")
  require(maxCharactersPerBatch >= maxCharactersPerText, "maxCharactersPerBatch 不能小于单文本上限")
  require(requestTimeout > Duration.Zero, "requestTimeout 必须为正数")

  /** 标准 embeddings 创建端点。 */
  val embeddingsUrl: String = s"${baseUrl.stripSuffix("/")}/embeddings"

  /** 日志摘要永远不包含 API Key 或默认选项正文。 */
  override def toString: String =
    s"OpenAICompatibleEmbeddingConfig(providerId=$providerId, baseUrl=$baseUrl, apiKey=<redacted>, " +
      s"model=$model, dimension=$dimension, maxBatchSize=$maxBatchSize, maxParallelBatches=$maxParallelBatches)"

object OpenAICompatibleEmbeddingConfig:
  /** 本 loader 读取 API Key 的环境变量名。
    *
    * Embedding 与聊天使用**独立**的凭据键:兼容 `/chat/completions` 的服务不一定提供 `/embeddings`，很多部署的 向量化走的是另一家厂商或另一个账号。复用聊天 Key
    * 会让这种常见拓扑无法表达。
    */
  val ApiKeyVariable: String = "EMBEDDING_API_KEY"

  /** 默认 Provider ID；只是一个路由与索引 manifest 标签，切换真实厂商时必须显式覆盖。 */
  val DefaultProviderId: String = "openai-embeddings"

  /** 使用 OpenAI 官方 API 的便捷配置；维度应与业务索引 migration 保持一致。 */
  def openAI(
      apiKey: String,
      model: String = "text-embedding-3-small",
      dimension: Int = 1536
  ): OpenAICompatibleEmbeddingConfig =
    OpenAICompatibleEmbeddingConfig(
      providerId = DefaultProviderId,
      baseUrl = "https://api.openai.com/v1",
      apiKey = apiKey,
      model = model,
      dimension = dimension,
      sendDimensions = true
    )

  /** OpenAI-compatible Embedding 的 ZIO Config 描述。
    *
    * `EMBEDDING_MODEL` 与 `EMBEDDING_DIMENSION` 刻意没有默认值。维度必须与知识库 migration 里的 `vector(N)` 一致，
    * 而模型决定了既有向量的可比性；给这两项一个"看起来合理"的默认值，会让一次遗漏配置表现为整库召回质量下降， 而不是启动失败。缺失时在装配期以 `InvalidConfiguration` 快速失败要便宜得多。
    *
    * API Key 使用 `Config.Secret`，因此配置错误、测试报告和调试输出都不会展开它。
    */
  val environmentConfig: Config[OpenAICompatibleEmbeddingConfig] =
    (
      Config.string("EMBEDDING_PROVIDER_ID").withDefault(DefaultProviderId) ++
        Config.string("EMBEDDING_BASE_URL").withDefault("https://api.openai.com/v1") ++
        Config.secret(ApiKeyVariable) ++
        Config.string("EMBEDDING_MODEL") ++
        Config.int("EMBEDDING_DIMENSION") ++
        Config.boolean("EMBEDDING_SEND_DIMENSIONS").withDefault(true) ++
        Config.int("EMBEDDING_MAX_BATCH_SIZE").withDefault(128) ++
        Config.duration("EMBEDDING_REQUEST_TIMEOUT").withDefault(60.seconds)
    ).mapAttempt {
      case (providerId, baseUrl, apiKey, model, dimension, sendDimensions, maxBatchSize, timeout) =>
        OpenAICompatibleEmbeddingConfig(
          providerId = providerId,
          baseUrl = baseUrl,
          apiKey = apiKey.stringValue,
          model = model,
          dimension = dimension,
          sendDimensions = sendDimensions,
          maxBatchSize = maxBatchSize,
          requestTimeout = timeout
        )
    }

  /** 从当前 ZIO `ConfigProvider` 构造配置。
    *
    * 错误只保留 ZIO Config 自己的键名与原因描述；`Config.Secret` 保证其中不含 Key 值。
    */
  def fromEnvironment: IO[AgentError, OpenAICompatibleEmbeddingConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"Embedding 配置无效: $error"))

/** OpenAI-compatible `/embeddings` 的 ZIO HTTP Adapter。
  *
  * 大批输入先按“数量 + 总字符”确定性切分，再使用 `foreachPar.withParallelism` 有界并发。ZIO 在一个 子批次失败时会中断同组其他 Fiber；HTTP Client 的
  * Scope/中断负责释放连接。
  *
  * @param client
  *   宿主共享的 ZIO HTTP Client
  * @param config
  *   endpoint、凭据、模型、固定维度与并发预算
  */
final class OpenAICompatibleEmbeddingService(
    client: Client,
    config: OpenAICompatibleEmbeddingConfig
) extends EmbeddingService:
  val dimension: Int                                   = config.dimension
  override val descriptor: EmbeddingProviderDescriptor = EmbeddingProviderDescriptor(
    config.providerId,
    config.model,
    config.dimension,
    config.maxBatchSize,
    config.sendDimensions
  )

  /** 返回与输入严格同序的向量；空输入不访问网络。 */
  def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
    embedDetailed(texts).map(_.embeddings)

  /** 执行确定性分批、有界并发、协议校验和 usage 汇总。
    *
    * @param texts
    *   所有文本必须非空且不超过配置上限
    * @return
    *   向量、可选累计 usage 与子批次 request ID
    */
  override def embedDetailed(texts: Chunk[String]): IO[RetrievalError, EmbeddingBatchResult] =
    if texts.isEmpty then ZIO.succeed(EmbeddingBatchResult(Chunk.empty, Some(EmbeddingUsage(0L, 0L))))
    else
      for
        _ <- validateInputs(texts)
        batches = splitBatches(texts)
        results <- ZIO.foreachPar(batches)(callBatch).withParallelism(config.maxParallelBatches)
        vectors = results.flatMap(_.embeddings)
        _ <- ZIO
          .fail(
            AgentError.RetrievalFailed(
              s"Embedding 总输出数量 ${vectors.length} != 输入数量 ${texts.length}"
            )
          )
          .unless(vectors.length == texts.length)
        usage <- aggregateUsage(results)
      yield EmbeddingBatchResult(vectors, usage, results.flatMap(_.providerRequestIds))

  /** 汇总子批次用量。
    *
    * 只要一个兼容服务没有返回 usage，逻辑调用就保持 `None`，避免把“不知道”错误表示成部分总量；加法使用 `Math.addExact`，极端或恶意响应导致溢出时返回协议错误而不是负数指标。
    */
  private def aggregateUsage(
      results: Chunk[EmbeddingBatchResult]
  ): IO[RetrievalError, Option[EmbeddingUsage]] =
    if results.exists(_.usage.isEmpty) then ZIO.none
    else
      ZIO
        .attempt {
          results.flatMap(_.usage).foldLeft(EmbeddingUsage(0L, 0L)) { (left, right) =>
            EmbeddingUsage(
              Math.addExact(left.inputTokens, right.inputTokens),
              Math.addExact(left.totalTokens, right.totalTokens)
            )
          }
        }
        .mapBoth(
          _ => AgentError.RetrievalFailed("Embedding usage 汇总溢出"),
          Some(_)
        )

  /** 验证非空、单条长度与逻辑调用总长度，错误发生在任何网络请求之前。 */
  private def validateInputs(texts: Chunk[String]): IO[RetrievalError, Unit] =
    val emptyIndex = texts.indexWhere(_.trim.isEmpty)
    val oversized  = texts.indexWhere(_.length > config.maxCharactersPerText)
    if emptyIndex >= 0 then ZIO.fail(AgentError.RetrievalFailed(s"Embedding 输入[$emptyIndex] 不能为空"))
    else if oversized >= 0 then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"Embedding 输入[$oversized] 字符数 ${texts(oversized).length} 超过 ${config.maxCharactersPerText}"
        )
      )
    else ZIO.unit

  /** 同时按最大条数和总字符切分；算法保持输入顺序且保证每批非空。 */
  private def splitBatches(texts: Chunk[String]): Chunk[Chunk[String]] =
    val (completed, current, _) = texts.foldLeft((Chunk.empty[Chunk[String]], Chunk.empty[String], 0)) {
      case ((done, batch, characters), text) =>
        val exceedsCount = batch.length >= config.maxBatchSize
        val exceedsChars = batch.nonEmpty && characters + text.length > config.maxCharactersPerBatch
        if exceedsCount || exceedsChars then (done :+ batch, Chunk(text), text.length)
        else (done, batch :+ text, characters + text.length)
    }
    if current.nonEmpty then completed :+ current else completed

  /** 发送一个 HTTP 子批次并校验响应 index、数量、维度、有限浮点与 usage。 */
  private def callBatch(texts: Chunk[String]): IO[RetrievalError, EmbeddingBatchResult] =
    for
      requestJson <- ZIO.fromEither(encodeRequest(texts))
      response    <- client
        .batched(
          Request
            .post(config.embeddingsUrl, Body.fromString(requestJson.toJson))
            .addHeader(Header.Authorization.Bearer(config.apiKey))
            .addHeader(Header.ContentType(MediaType.application.json))
        )
        .timeoutFail(timeoutError)(config.requestTimeout)
        .mapError(mapTransportError)
      body   <- response.body.asString.mapError(mapTransportError)
      result <-
        if response.status.isSuccess then decodeResponse(body, texts.length)
        else ZIO.fail(httpError(response.status.code, body))
    yield result

  /** 构造请求并拒绝 defaultOptions 覆盖 model/input/encoding_format/dimensions。 */
  private def encodeRequest(texts: Chunk[String]): Either[RetrievalError, Json.Obj] =
    val reserved =
      config.defaultOptions.keySet.intersect(Set("model", "input", "encoding_format", "dimensions"))
    val unknown = config.defaultOptions.keySet.diff(Set("user")).diff(reserved)
    if reserved.nonEmpty then
      Left(
        AgentError.RetrievalFailed(
          s"Embedding defaultOptions 不能覆盖保留字段: ${reserved.toList.sorted.mkString(", ")}"
        )
      )
    else if unknown.nonEmpty then
      Left(
        AgentError.RetrievalFailed(
          s"Embedding defaultOptions 未进入白名单: ${unknown.toList.sorted.mkString(", ")}"
        )
      )
    else
      val fields = List(
        "model"           -> Json.Str(config.model),
        "input"           -> Json.Arr(texts.map(Json.Str(_))),
        "encoding_format" -> Json.Str("float")
      ) ++ Option.when(config.sendDimensions)("dimensions" -> Json.Num(config.dimension)) ++
        config.defaultOptions.toList.sortBy(_._1)
      Right(Json.Obj(Chunk.fromIterable(fields)))

  /** 解码 OpenAI list response；data 可以乱序，但 index 必须恰好覆盖 0..N-1。 */
  private def decodeResponse(body: String, expected: Int): IO[RetrievalError, EmbeddingBatchResult] =
    for
      json <- ZIO
        .fromEither(body.fromJson[Json])
        .mapError(details =>
          AgentError.RetrievalFailed(
            s"Embedding 响应不是合法 JSON: $details"
          )
        )
      data <- ZIO
        .fromOption(arrayField(json, "data"))
        .orElseFail(
          AgentError.RetrievalFailed("Embedding 响应缺少 data 数组")
        )
      decoded <- ZIO.foreach(data)(decodeItem)
      sorted  = decoded.sortBy(_._1)
      indices = sorted.map(_._1)
      _ <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"Embedding 响应 index 不连续: ${indices.mkString(",")}"
          )
        )
        .unless(indices == Chunk.fromIterable(0 until expected))
      vectors = sorted.map(_._2)
      usage <- decodeUsage(field(json, "usage"))
      requestIds = Chunk.fromIterable(stringField(json, "id"))
    yield EmbeddingBatchResult(vectors, usage, requestIds)

  /** 解码单个 data item，并在写库前验证所有数值有限且维度固定。 */
  private def decodeItem(json: Json): IO[RetrievalError, (Int, Embedding)] =
    for
      index <- ZIO
        .fromOption(intField(json, "index"))
        .orElseFail(
          AgentError.RetrievalFailed("Embedding data item 缺少 index")
        )
      values <- ZIO
        .fromOption(arrayField(json, "embedding"))
        .orElseFail(
          AgentError.RetrievalFailed(s"Embedding data[$index] 缺少 embedding 数组")
        )
      floats <- ZIO.foreach(values) {
        case Json.Num(value) =>
          val float = value.floatValue
          if java.lang.Float.isFinite(float) then ZIO.succeed(float)
          else ZIO.fail(AgentError.RetrievalFailed(s"Embedding data[$index] 包含非有限数值"))
        case _ => ZIO.fail(AgentError.RetrievalFailed(s"Embedding data[$index] 包含非数值元素"))
      }
      _ <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"Embedding data[$index] 维度 ${floats.length} != ${config.dimension}"
          )
        )
        .unless(floats.length == config.dimension)
    yield index -> Embedding(floats)

  /** usage 缺失时返回 None；存在时必须是非负数。 */
  private def decodeUsage(value: Option[Json]): IO[RetrievalError, Option[EmbeddingUsage]] = value match
    case None       => ZIO.none
    case Some(json) =>
      (longField(json, "prompt_tokens"), longField(json, "total_tokens")) match
        case (Some(input), Some(total)) if input >= 0L && total >= input =>
          ZIO.some(EmbeddingUsage(input, total))
        case (Some(input), Some(total)) =>
          ZIO.fail(AgentError.RetrievalFailed(s"Embedding usage 非法: input=$input, total=$total"))
        case _ =>
          ZIO.fail(AgentError.RetrievalFailed("Embedding usage 缺少整数 prompt_tokens/total_tokens"))

  /** 网络/TLS/连接错误为可重试 RetrievalFailed；已有 RetrievalError 不重复包装。 */
  private def mapTransportError(error: Throwable): RetrievalError = error match
    case value: RetrievalError => value
    case other                 =>
      AgentError.RetrievalFailed(
        s"${config.providerId} Embedding transport failure: ${other.getClass.getSimpleName}",
        retryable = true
      )

  /** 408/409/429/5xx 可重试；错误正文只抽取稳定 code/type，防止敏感输入进入日志。 */
  private def httpError(status: Int, body: String): RetrievalError =
    val retryable = status == 408 || status == 409 || status == 429 || status >= 500
    val code      = body
      .fromJson[Json]
      .toOption
      .flatMap(json => field(json, "error"))
      .flatMap(error => stringField(error, "code").orElse(stringField(error, "type")))
      .getOrElse("unknown_error")
    AgentError.RetrievalFailed(s"${config.providerId} Embedding HTTP $status ($code)", retryable)

  /** 单个子批次超时不会被伪装成空向量。 */
  private def timeoutError: RetrievalError =
    AgentError.RetrievalFailed(s"${config.providerId} Embedding request timed out", retryable = true)

  /** 安全读取 JSON object 字段。 */
  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  /** 安全读取字符串字段。 */
  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  /** 安全读取数组字段。 */
  private def arrayField(json: Json, name: String): Option[Chunk[Json]] =
    field(json, name).collect { case Json.Arr(values) => values }

  /** 安全读取整数索引。 */
  private def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => Try(value.intValueExact()).toOption }.flatten

  /** 安全读取 Long token 计数。 */
  private def longField(json: Json, name: String): Option[Long] =
    field(json, name).collect { case Json.Num(value) => Try(value.longValueExact()).toOption }.flatten

object OpenAICompatibleEmbeddingService:
  /** 使用显式配置构造只依赖共享 Client 的 Layer。 */
  def configured(config: OpenAICompatibleEmbeddingConfig): URLayer[Client, EmbeddingService] =
    ZLayer.fromFunction((client: Client) => OpenAICompatibleEmbeddingService(client, config))

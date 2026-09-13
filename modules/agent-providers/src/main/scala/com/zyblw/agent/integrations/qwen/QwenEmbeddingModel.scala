package com.zyblw.agent.integrations.qwen

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** DashScope native embedding 区域；endpoint 只能来自枚举或显式 allowlist。 */
enum QwenEmbeddingRegion:
  case Beijing, Singapore

  def defaultBaseUrl: String = this match
    case QwenEmbeddingRegion.Beijing   => "https://dashscope.aliyuncs.com/api/v1"
    case QwenEmbeddingRegion.Singapore => "https://dashscope-intl.aliyuncs.com/api/v1"

final case class QwenEmbeddingConfig(
    apiKey: String,
    model: String,
    dimension: Int = 1024,
    region: QwenEmbeddingRegion = QwenEmbeddingRegion.Beijing,
    baseUrl: Option[String] = None,
    maxBatchTexts: Int = 20,
    requestTimeout: Duration = 30.seconds,
    allowInsecureHttp: Boolean = false
):
  require(apiKey.trim.nonEmpty, "Qwen embedding apiKey 不能为空")
  require(model.trim.nonEmpty, "Qwen embedding model 不能为空")
  require(dimension > 0, "Qwen embedding dimension 必须为正数")
  require(maxBatchTexts > 0 && maxBatchTexts <= 20, "Qwen maxBatchTexts 必须位于 1..20")

  val embeddingsUrl: String =
    s"${baseUrl.getOrElse(region.defaultBaseUrl).stripSuffix("/")}/services/embeddings/text-embedding/text-embedding"

  override def toString: String =
    s"QwenEmbeddingConfig(model=$model, dimension=$dimension, region=$region, apiKey=<redacted>)"

/** Qwen native embedding Adapter。OpenAI-compatible 端点继续走 dense-only 通用 Adapter。 */
final class QwenEmbeddingModel(client: Client, config: QwenEmbeddingConfig) extends EmbeddingModel:
  override val capabilities: EmbeddingCapabilities = EmbeddingCapabilities(
    inputRoles = Set(EmbeddingInputRole.Query, EmbeddingInputRole.Document),
    outputs = Set(EmbeddingOutputKind.Dense, EmbeddingOutputKind.Sparse, EmbeddingOutputKind.DenseAndSparse),
    minDenseDimension = config.dimension,
    maxDenseDimension = config.dimension,
    defaultDenseDimension = config.dimension,
    maxTextsPerRequest = config.maxBatchTexts,
    instructionPolicy = InstructionPolicy.QueryAndDocument,
    reportsUsage = true
  )
  override val descriptor: EmbeddingProviderDescriptorV2 =
    EmbeddingProviderDescriptorV2("qwen", config.model, capabilities)

  def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
    validate(request) *> {
      if request.output != EmbeddingOutputKind.Dense then
        ZIO.fail(AgentError.RetrievalFailed("本部署未启用 Qwen sparse 检索；output 必须是 Dense"))
      else
        val textType = request.role match
          case EmbeddingInputRole.Query    => "query"
          case EmbeddingInputRole.Document => "document"
        val payload = Json.Obj(
          Chunk(
            "model"      -> Json.Str(config.model),
            "input"      -> Json.Obj(Chunk("texts" -> Json.Arr(request.texts.map(Json.Str(_))))),
            "parameters" -> Json.Obj(
              Chunk(
                "text_type"   -> Json.Str(textType),
                "dimension"   -> Json.Num(config.dimension),
                "output_type" -> Json.Str("dense")
              ) ++ request.instruction.toList.map(i => "instruct" -> Json.Str(i.text))
            )
          )
        )
        client
          .batched(
            Request
              .post(config.embeddingsUrl, Body.fromString(payload.toJson))
              .addHeader(Header.Authorization.Bearer(config.apiKey))
              .addHeader(Header.ContentType(MediaType.application.json))
          )
          .timeoutFail(AgentError.RetrievalFailed("Qwen embedding timed out", retryable = true))(
            config.requestTimeout
          )
          .mapError {
            case error: RetrievalError => error
            case other                 =>
              AgentError.RetrievalFailed(
                s"Qwen embedding transport: ${other.getClass.getSimpleName}",
                retryable = true
              )
          }
          .flatMap { response =>
            response.body.asString
              .mapError(err =>
                AgentError
                  .RetrievalFailed(s"Qwen embedding body: ${err.getClass.getSimpleName}", retryable = true)
              )
              .flatMap { body =>
                if response.status.isSuccess then decode(body, request.texts.length)
                else
                  val retryable = response.status.code == 429 || response.status.code >= 500
                  ZIO.fail(
                    AgentError.RetrievalFailed(s"Qwen embedding HTTP ${response.status.code}", retryable)
                  )
              }
          }
    }

  private def decode(body: String, expected: Int): IO[RetrievalError, EmbeddingResponse] =
    ZIO
      .fromEither(body.fromJson[Json])
      .mapError(err => AgentError.RetrievalFailed(s"Qwen embedding JSON: $err"))
      .flatMap {
        case obj: Json.Obj =>
          val embeddings = obj.fields
            .find(_._1 == "output")
            .flatMap {
              case (_, Json.Obj(fields)) => fields.find(_._1 == "embeddings").map(_._2)
              case _                     => None
            }
            .collect { case Json.Arr(items) => items }
            .getOrElse(Chunk.empty)
          if embeddings.length != expected then
            ZIO.fail(AgentError.RetrievalFailed("Qwen embedding 输出数量与输入不一致"))
          else
            ZIO
              .foreach(embeddings) {
                case Json.Obj(fields) =>
                  fields.find(_._1 == "embedding").map(_._2) match
                    case Some(Json.Arr(values)) =>
                      ZIO
                        .foreach(values) {
                          case Json.Num(n) if java.lang.Float.isFinite(n.floatValue) =>
                            ZIO.succeed(n.floatValue)
                          case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen embedding 含非有限值"))
                        }
                        .map(floats => EmbeddingItem(Some(Embedding(floats)), None))
                    case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen embedding 缺少向量"))
                case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen embedding item 非法"))
              }
              .map(items => EmbeddingResponse(items, descriptor))
        case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen embedding 根节点非法"))
      }

object QwenEmbeddingModel:
  def configured(config: QwenEmbeddingConfig): URLayer[Client, EmbeddingModel] =
    ZLayer.fromFunction((client: Client) => QwenEmbeddingModel(client, config))

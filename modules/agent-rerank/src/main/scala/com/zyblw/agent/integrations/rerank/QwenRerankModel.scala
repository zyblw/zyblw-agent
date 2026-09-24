package com.zyblw.agent.integrations.rerank

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

final case class QwenRerankInstruction(id: String, version: String, text: String):
  require(id.trim.nonEmpty && version.trim.nonEmpty, "Qwen rerank instruction 身份不能为空")
  require(text.nonEmpty && text.length <= 4000, "Qwen rerank instruction 文本长度无效")

final case class QwenRerankConfig(
    apiKey: String,
    model: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
    maxCandidates: Int = 100,
    requestTimeout: Duration = 10.seconds,
    instruct: Option[String] = None,
    maxResponseBytes: Int = 256 * 1024
):
  require(apiKey.trim.nonEmpty && model.trim.nonEmpty, "Qwen rerank 配置无效")
  require(maxCandidates > 0 && maxCandidates <= 500, "Qwen rerank maxCandidates 必须位于 1..500")
  require(instruct.forall(text => text.nonEmpty && text.length <= 4000), "Qwen rerank instruct 长度无效")
  require(requestTimeout > Duration.Zero && maxResponseBytes > 0, "Qwen rerank timeout/response limit 必须为正数")
  val rerankUrl: String         = s"${baseUrl.stripSuffix("/")}/services/rerank/text-rerank/text-rerank"
  override def toString: String = s"QwenRerankConfig(model=$model, apiKey=<redacted>)"

/** Qwen native rerank。不能添加候选，分数必须有限。 */
final class QwenRerankModel(client: Client, config: QwenRerankConfig) extends RerankerModel:
  override val descriptor: RerankerDescriptor =
    RerankerDescriptor("qwen", config.model, config.maxCandidates, 16_000, 32_000)

  def score(request: RerankRequest): IO[RetrievalError, RerankResponse] =
    if request.candidates.length > config.maxCandidates then
      ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 候选数超过上限"))
    else if request.topN > request.candidates.length then
      ZIO.fail(AgentError.RetrievalFailed("Qwen rerank topN 不能大于候选数"))
    else
      val payload = Json.Obj(
        Chunk(
          "model" -> Json.Str(config.model),
          "input" -> Json.Obj(
            Chunk(
              "query"     -> Json.Str(request.query),
              "documents" -> Json.Arr(request.candidates.map(c => Json.Str(c.text)))
            )
          ),
          "parameters" -> Json.Obj(
            Chunk("top_n" -> Json.Num(request.topN), "return_documents" -> Json.Bool(false)) ++
              config.instruct.toList.map(text => "instruct" -> Json.Str(text))
          )
        )
      )
      val httpRequest = Request
        .post(config.rerankUrl, Body.fromString(payload.toJson))
        .addHeader(Header.Authorization.Bearer(config.apiKey))
        .addHeader(Header.ContentType(MediaType.application.json))
      client
        .stream(httpRequest) { response =>
          ZStream.fromZIO(
            response.body.asStream
              .take(config.maxResponseBytes.toLong + 1L)
              .runCollect
              .flatMap { bytes =>
                if bytes.length > config.maxResponseBytes then
                  ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 响应超过字节上限"))
                else ZIO.succeed(response.status -> String(bytes.toArray, StandardCharsets.UTF_8))
              }
          )
        }
        .runHead
        .someOrFail(AgentError.RetrievalFailed("Qwen rerank 响应流为空", retryable = true))
        .mapError {
          case error: RetrievalError => error
          case other                 =>
            AgentError.RetrievalFailed(
              s"Qwen rerank transport: ${other.getClass.getSimpleName}",
              retryable = true
            )
        }
        .timeoutFail(AgentError.RetrievalFailed("Qwen rerank timed out", retryable = true))(
          config.requestTimeout
        )
        .flatMap { case (status, body) =>
          if !status.isSuccess then
            val retryable = status.code == 429 || status.code >= 500
            ZIO.fail(AgentError.RetrievalFailed(s"Qwen rerank HTTP ${status.code}", retryable))
          else decode(body, request)
        }

  private def decode(body: String, request: RerankRequest): IO[RetrievalError, RerankResponse] =
    ZIO
      .fromEither(body.fromJson[Json])
      .mapError(err => AgentError.RetrievalFailed(s"Qwen rerank JSON: $err"))
      .flatMap {
        case obj: Json.Obj =>
          val results = obj.fields
            .find(_._1 == "output")
            .flatMap {
              case (_, Json.Obj(fields)) => fields.find(_._1 == "results").map(_._2)
              case _                     => None
            }
            .collect { case Json.Arr(items) => items }
          val allowed = request.candidates.map(_.candidateId).toSet
          ZIO
            .fromOption(results)
            .orElseFail(AgentError.RetrievalFailed("Qwen rerank 缺少 output.results"))
            .flatMap { results =>
              if results.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 返回空结果"))
              else if results.length > request.topN then
                ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 返回结果超过 topN"))
              else
                ZIO
                  .foreach(results) {
                    case Json.Obj(fields) =>
                      val index = fields.collectFirst { case ("index", Json.Num(n)) =>
                        n.toString.toIntOption
                      }.flatten
                      val score = fields.collectFirst { case ("relevance_score", Json.Num(n)) =>
                        n.doubleValue
                      }
                      (index, score) match
                        case (Some(i), Some(s))
                            if request.candidates.isDefinedAt(i) && java.lang.Double.isFinite(s) &&
                              s >= 0.0 && s <= 1.0 =>
                          val id = request.candidates(i).candidateId
                          if allowed.contains(id) then ZIO.succeed(RerankScore(id, s))
                          else ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 返回了未知候选"))
                        case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 分数越界或 index 越界"))
                    case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen rerank result 非法"))
                  }
                  .flatMap { scores =>
                    if scores.map(_.candidateId).distinct.length != scores.length then
                      ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 返回重复 index"))
                    else ZIO.succeed(scores)
                  }
            }
            .map(scores => RerankResponse(scores))
        case _ => ZIO.fail(AgentError.RetrievalFailed("Qwen rerank 根节点非法"))
      }

object QwenRerankModel:
  def configured(config: QwenRerankConfig): URLayer[Client, RerankerModel] =
    ZLayer.fromFunction((client: Client) => QwenRerankModel(client, config))

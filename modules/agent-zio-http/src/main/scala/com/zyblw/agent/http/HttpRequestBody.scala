package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.AgentHttpLimits
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import zio.*
import zio.http.*

/** HTTP Adapter 共享的有界 JSON 请求体读取器。
  *
  * 把限制放在 DTO 解码之前，避免 Agent 和 Memory 两套路由分别退回 `Body.asString` 的无界缓冲。该组件只处理传输资源 边界，不解析
  * JSON、不记录正文，也不替代字段级验证或网关的连接/速率治理。
  */
private[http] object HttpRequestBody:

  /** 以 ZStream 最多读取 `maxBytes + 1` 个字节。
    *
    * 多读取一个字节即可区分“刚好达到上限”和“已经溢出”；ZStream 提前结束后会取消剩余 Body 流。错误只返回稳定说明， 不回显用户输入。
    *
    * @param request
    *   当前 ZIO HTTP 请求
    * @param maxBytes
    *   最大 UTF-8 字节数，默认使用 v1 公共契约上限
    * @return
    *   有界 UTF-8 文本；JSON 语法由调用方的 zio-json decoder 校验
    */
  def readJson(request: Request, maxBytes: Long = AgentHttpLimits.JsonBodyBytes): IO[AgentError, String] =
    if maxBytes <= 0L then ZIO.fail(AgentError.InvalidConfiguration("JSON 请求体上限必须为正数"))
    else
      request.body.asStream
        .take(maxBytes + 1L)
        .runCollect
        .mapError(error => AgentError.InvalidConfiguration(Option(error.getMessage).getOrElse("读取请求体失败")))
        .flatMap { bytes =>
          if bytes.length.toLong > maxBytes then
            ZIO.fail(AgentError.InvalidConfiguration(s"JSON 请求体不能超过 $maxBytes 字节"))
          else decodeUtf8(bytes)
        }

  /** 以 ZStream 最多读取 `maxBytes + 1` 个原始字节。
    *
    * 管理面文档上传不能走 [[readJson]]：Base64 会把二进制放大三分之一，而 JSON 上限是为控制面 DTO 设计的。 这里保持同样的“多读一个字节判定溢出”策略，只是不做 UTF-8 解码。
    *
    * @param request
    *   当前 ZIO HTTP 请求
    * @param maxBytes
    *   最大字节数
    */
  def readBytes(request: Request, maxBytes: Long): IO[AgentError, Chunk[Byte]] =
    if maxBytes <= 0L then ZIO.fail(AgentError.InvalidConfiguration("请求体上限必须为正数"))
    else
      request.body.asStream
        .take(maxBytes + 1L)
        .runCollect
        .mapError(error => AgentError.InvalidConfiguration(Option(error.getMessage).getOrElse("读取请求体失败")))
        .flatMap { bytes =>
          if bytes.length.toLong > maxBytes then
            ZIO.fail(AgentError.InvalidConfiguration(s"请求体不能超过 $maxBytes 字节"))
          else ZIO.succeed(bytes)
        }

  /** 严格拒绝畸形 UTF-8，而不是让 `new String` 用替换字符悄悄改变签名、幂等指纹或用户输入。 */
  private def decodeUtf8(bytes: Chunk[Byte]): IO[AgentError, String] =
    ZIO
      .attempt {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toArray))
          .toString
      }
      .mapError(_ => AgentError.InvalidConfiguration("JSON 请求体必须是合法 UTF-8"))

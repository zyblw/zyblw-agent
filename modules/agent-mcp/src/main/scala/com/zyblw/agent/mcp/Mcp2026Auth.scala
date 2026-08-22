package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import java.net.URI

/** MCP 2026 授权边界：issuer / CIMD URL 的 SSRF 与凭据隔离。
  *
  * 不实现完整 OAuth 交换或官方授权服务器互操作。权威要求见 https://modelcontextprotocol.io/specification/2026-07-28/changelog
  * （SEP-2468 / CIMD）。
  */
object Mcp2026Auth:
  def validateIssuer(issuer: String): Either[AgentError, URI] =
    Mcp2026Endpoint.validate(issuer).left.map(_ => AgentError.InvalidConfiguration("mcp-2026-issuer-ssrf"))

  def validateCimdUrl(url: String): Either[AgentError, URI] =
    Mcp2026Endpoint.validate(url).left.map(_ => AgentError.InvalidConfiguration("mcp-2026-cimd-ssrf"))

  /** 授权响应若带 `iss`，必须与已记录 issuer 完全一致，才能兑换 code。 */
  def validateIssParameter(recordedIssuer: String, responseIss: Option[String]): Either[AgentError, Unit] =
    validateIssuer(recordedIssuer).flatMap { issuer =>
      responseIss match
        case None                                                               => Right(())
        case Some(value) if value == issuer.toString || value == recordedIssuer => Right(())
        case Some(_)                                                            =>
          Left(
            AgentError.ExternalProtocolFailure(
              "mcp",
              "oauth/iss",
              "authorization iss does not match recorded issuer",
              Some("issuer_mismatch")
            )
          )
    }

  /** 凭据必须按 issuer 分钥，禁止跨授权服务器复用 client id。 */
  def credentialKey(issuer: String, clientId: String): Either[AgentError, String] =
    if clientId.trim.isEmpty then Left(AgentError.InvalidConfiguration("mcp-2026-client-id"))
    else validateIssuer(issuer).map(uri => s"${uri.toString}\n${clientId.trim}")

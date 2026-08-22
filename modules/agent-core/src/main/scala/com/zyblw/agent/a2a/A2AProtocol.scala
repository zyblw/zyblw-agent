package com.zyblw.agent.a2a

import com.zyblw.agent.core.*
import java.net.URI
import zio.json.ast.Json

/** A2A `1.0` 远程 Agent 边界：只解析 Agent Card / Task 引用，不把远程技能提升为本地工具。
  *
  * 权威字段见 https://a2a-protocol.org/v1.0.0/specification/ 。本模块不是 A2A server，也不替代 Workflow 或 MCP。
  */
object A2AProtocol:
  val Version: String       = "1.0"
  val CardPath: String      = "/.well-known/agent-card.json"
  val Bindings: Set[String] = Set("JSONRPC", "GRPC", "HTTP+JSON")

final case class A2AInterface(
    url: String,
    protocolBinding: String,
    protocolVersion: String,
    tenant: Option[String] = None
)

final case class A2ASkill(id: String, name: String, description: String, tags: List[String])

final case class A2ACapabilities(
    streaming: Boolean = false,
    pushNotifications: Boolean = false,
    extendedAgentCard: Boolean = false
)

final case class A2AAgentCard(
    name: String,
    description: String,
    version: String,
    supportedInterfaces: List[A2AInterface],
    capabilities: A2ACapabilities,
    defaultInputModes: List[String],
    defaultOutputModes: List[String],
    skills: List[A2ASkill],
    securitySchemes: Map[String, Json] = Map.empty
)

final case class A2ATaskRef(id: String, contextId: Option[String] = None)

object A2AAgentCard:
  def parse(json: Json, allowPrivateNetwork: Boolean = false): Either[AgentError, A2AAgentCard] =
    json match
      case obj: Json.Obj =>
        for
          name        <- requiredString(obj, "name")
          description <- requiredString(obj, "description")
          version     <- requiredString(obj, "version")
          interfaces  <- requiredArray(obj, "supportedInterfaces").flatMap { values =>
            if values.isEmpty then Left(fail("supportedInterfaces must not be empty"))
            else
              values.foldLeft[Either[AgentError, List[A2AInterface]]](Right(Nil)) { (acc, value) =>
                acc.flatMap(current => parseInterface(value, allowPrivateNetwork).map(current :+ _))
              }
          }
          capabilities <- field(obj, "capabilities")
            .map(parseCapabilities)
            .getOrElse(Left(fail("missing capabilities")))
          inputs  <- requiredStringArray(obj, "defaultInputModes")
          outputs <- requiredStringArray(obj, "defaultOutputModes")
          skills  <- requiredArray(obj, "skills").flatMap { values =>
            if values.isEmpty then Left(fail("skills must not be empty"))
            else
              values.foldLeft[Either[AgentError, List[A2ASkill]]](Right(Nil)) { (acc, value) =>
                acc.flatMap(current => parseSkill(value).map(current :+ _))
              }
          }
          schemes = field(obj, "securitySchemes") match
            case Some(Json.Obj(fields)) => fields.toMap
            case _                      => Map.empty[String, Json]
          _ <- Either.cond(schemes.nonEmpty, (), fail("A2A Agent Card must declare securitySchemes"))
        yield A2AAgentCard(
          name,
          description,
          version,
          interfaces,
          capabilities,
          inputs,
          outputs,
          skills,
          schemes
        )
      case _ => Left(fail("Agent Card must be an object"))

  /** 远程接口不能放大本地工具/预算；Card 技能只是描述，不是授权。 */
  def remoteOnly(card: A2AAgentCard): Either[AgentError, A2AInterface] =
    card.supportedInterfaces.headOption.toRight(fail("no supported interface")).flatMap { preferred =>
      Either.cond(
        preferred.protocolVersion == A2AProtocol.Version,
        preferred,
        fail(s"unsupported A2A protocolVersion ${preferred.protocolVersion}")
      )
    }

  def validateUrl(url: String, allowPrivateNetwork: Boolean = false): Either[AgentError, URI] =
    scala.util
      .Try(URI.create(url))
      .toEither
      .left
      .map(_ => fail("a2a-endpoint-invalid"))
      .flatMap { uri =>
        val host    = Option(uri.getHost).getOrElse("")
        val blocked =
          !allowPrivateNetwork && (
            host.equalsIgnoreCase("localhost") ||
              host.endsWith(".localhost") ||
              host == "0.0.0.0" ||
              host == "::1" ||
              host == "169.254.169.254" ||
              host.startsWith("10.") ||
              host.startsWith("192.168.") ||
              host.startsWith("169.254.")
          )
        Either.cond(
          uri.isAbsolute &&
            uri.getScheme == "https" &&
            uri.getUserInfo == null &&
            uri.getRawQuery == null &&
            uri.getRawFragment == null &&
            host.nonEmpty &&
            !blocked,
          uri,
          fail("a2a-endpoint-ssrf")
        )
      }

  private def parseInterface(json: Json, allowPrivateNetwork: Boolean): Either[AgentError, A2AInterface] =
    json match
      case obj: Json.Obj =>
        for
          url     <- requiredString(obj, "url")
          _       <- validateUrl(url, allowPrivateNetwork)
          binding <- requiredString(obj, "protocolBinding")
          _       <- Either.cond(
            A2AProtocol.Bindings.contains(binding),
            (),
            fail(s"unsupported protocolBinding $binding")
          )
          version <- requiredString(obj, "protocolVersion")
          tenant = stringField(obj, "tenant")
        yield A2AInterface(url, binding, version, tenant)
      case _ => Left(fail("supportedInterfaces entry must be an object"))

  private def parseCapabilities(json: Json): Either[AgentError, A2ACapabilities] =
    json match
      case obj: Json.Obj =>
        Right(
          A2ACapabilities(
            streaming = boolField(obj, "streaming"),
            pushNotifications = boolField(obj, "pushNotifications"),
            extendedAgentCard = boolField(obj, "extendedAgentCard")
          )
        )
      case _ => Left(fail("capabilities must be an object"))

  private def parseSkill(json: Json): Either[AgentError, A2ASkill] =
    json match
      case obj: Json.Obj =>
        for
          id          <- requiredString(obj, "id")
          name        <- requiredString(obj, "name")
          description <- requiredString(obj, "description")
          tags        <- requiredStringArray(obj, "tags")
        yield A2ASkill(id, name, description, tags)
      case _ => Left(fail("skill must be an object"))

  private def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.find(_._1 == name).map(_._2)

  private def requiredString(obj: Json.Obj, name: String): Either[AgentError, String] =
    field(obj, name) match
      case Some(Json.Str(value)) if value.trim.nonEmpty => Right(value)
      case _                                            => Left(fail(s"missing $name"))

  private def stringField(obj: Json.Obj, name: String): Option[String] =
    field(obj, name).collect { case Json.Str(value) if value.nonEmpty => value }

  private def boolField(obj: Json.Obj, name: String): Boolean =
    field(obj, name).contains(Json.Bool(true))

  private def requiredArray(obj: Json.Obj, name: String): Either[AgentError, List[Json]] =
    field(obj, name) match
      case Some(Json.Arr(values)) => Right(values.toList)
      case _                      => Left(fail(s"$name must be an array"))

  private def requiredStringArray(obj: Json.Obj, name: String): Either[AgentError, List[String]] =
    requiredArray(obj, name).flatMap { values =>
      val strings = values.collect { case Json.Str(value) if value.nonEmpty => value }
      Either.cond(
        strings.size == values.size && strings.nonEmpty,
        strings,
        fail(s"$name must be a non-empty string array")
      )
    }

  private def fail(message: String): AgentError =
    AgentError.ExternalProtocolFailure("a2a", "agent-card", message, Some("a2a_card_invalid"))

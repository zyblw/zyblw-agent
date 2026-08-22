package com.zyblw.agent.observability

/** 内部低敏事件到 OpenTelemetry GenAI 约定的版本化映射。
  *
  * OTel GenAI 仍是 Development，因此本映射不是公共 API：升级约定时递增 `ConventionVersion`， 不把 vendor span
  * 名称直接暴露给业务。Prompt、工具参数、文档和隐藏推理不得进入 attributes。
  *
  * 约定来源：https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/
  */
object GenAiSemanticMap:
  val ConventionVersion: String = "1.37.0"

  enum Operation(val otelName: String):
    case Chat        extends Operation("chat")
    case ExecuteTool extends Operation("execute_tool")
    case Embeddings  extends Operation("embeddings")
    case InvokeAgent extends Operation("invoke_agent")

  val ForbiddenAttributeKeys: Set[String] = Set(
    "gen_ai.prompt",
    "gen_ai.completion",
    "gen_ai.request.messages",
    "gen_ai.tool.call.arguments",
    "gen_ai.tool.call.result",
    "gen_ai.rag.document",
    "prompt",
    "messages",
    "arguments",
    "document"
  )

  def operation(internalKind: String): Option[Operation] =
    internalKind match
      case "model" | "chat" | "completion" | "agent.model.call" => Some(Operation.Chat)
      case "tool" | "execute_tool" | "agent.tool.execute"       => Some(Operation.ExecuteTool)
      case "retrieval" | "embeddings" | "agent.retrieval"       => Some(Operation.Embeddings)
      case "agent" | "invoke_agent" | "agent.run"               => Some(Operation.InvokeAgent)
      case _                                                    => None

  /** 供 OTLP exporter 使用：丢掉禁止键后再合并约定字段，观测失败不得抛出。 */
  def project(eventName: String, attributes: Map[String, String]): Map[String, String] =
    val safe = attributes.view.filterKeys(key => !ForbiddenAttributeKeys.contains(key)).toMap
    operation(eventName).flatMap(op => GenAiSemanticMap.attributes(op, extra = safe).toOption).getOrElse(safe)

  def attributes(
      operation: Operation,
      provider: Option[String] = None,
      model: Option[String] = None,
      extra: Map[String, String] = Map.empty
  ): Either[String, Map[String, String]] =
    val base = Map(
      "gen_ai.operation.name"     -> operation.otelName,
      "gen_ai.convention.version" -> ConventionVersion
    ) ++ provider.filter(_.nonEmpty).map("gen_ai.provider.name" -> _) ++
      model.filter(_.nonEmpty).map("gen_ai.request.model" -> _) ++ extra
    val leak = base.keySet.intersect(ForbiddenAttributeKeys)
    if leak.nonEmpty then Left("genai-sensitive-attribute")
    else if extra.values.exists(value => value.length > 64 && looksLikePayload(value)) then
      Left("genai-sensitive-attribute")
    else Right(base)

  private def looksLikePayload(value: String): Boolean =
    value.contains("\"role\"") || value.contains("system prompt") || value.contains("ignore previous")

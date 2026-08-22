package com.zyblw.agent.observability

import zio.test.*

object GenAiSemanticMapSpec extends ZIOSpecDefault:
  def spec = suite("GenAiSemanticMap")(
    test("内部事件映射到版本化 GenAI operation，并拒绝 prompt 属性") {
      val chat = GenAiSemanticMap.attributes(
        GenAiSemanticMap.Operation.Chat,
        provider = Some("openai"),
        model = Some("gpt-test")
      )
      val leaked = GenAiSemanticMap.attributes(
        GenAiSemanticMap.Operation.ExecuteTool,
        extra = Map("gen_ai.prompt" -> "secret")
      )
      assertTrue(
        GenAiSemanticMap.operation("model").contains(GenAiSemanticMap.Operation.Chat),
        GenAiSemanticMap.operation("tool").contains(GenAiSemanticMap.Operation.ExecuteTool),
        chat.exists(_("gen_ai.operation.name") == "chat"),
        chat.exists(_("gen_ai.convention.version") == "1.37.0"),
        leaked == Left("genai-sensitive-attribute")
      )
    },
    test("OTLP 投影合并约定字段并丢掉 prompt") {
      val projected = GenAiSemanticMap.project(
        "agent.model.call",
        Map("agent.model" -> "gpt-test", "gen_ai.prompt" -> "secret")
      )
      assertTrue(
        projected.get("gen_ai.operation.name").contains("chat"),
        projected.get("gen_ai.convention.version").contains("1.37.0"),
        !projected.contains("gen_ai.prompt"),
        projected.get("agent.model").contains("gpt-test")
      )
    }
  )

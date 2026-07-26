package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.*
import zio.*
import zio.test.*

/** 验证 Agent Builder 的不可变组合、启动期策略对齐和敏感 metadata 拒绝。 */
object AgentDefinitionBuilderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentDefinitionBuilder")(
    test("构建 Provider-neutral 定义并规范化展示文本与 metadata") {
      for definition <- AgentDefinitionBuilder(AgentId("knowledge-agent"), "  知识助手  ")
          .withInstructions("  仅根据授权资料回答。  ")
          .withProvider(ProviderId("deepseek"))
          .withModel(ModelId("deepseek-chat"))
          .allowTools(List(ToolName("search_knowledge"), ToolName("search_knowledge")))
          .withMetadata(" version ", " v1 ")
          .buildFor(ToolPolicyConfig(allowedTools = Set(ToolName("search_knowledge"))))
      yield assertTrue(
        definition.name == "知识助手",
        definition.instructions == "仅根据授权资料回答。",
        definition.allowedTools == Set("search_knowledge"),
        definition.modelSettings.provider.contains("deepseek"),
        definition.modelSettings.model.contains("deepseek-chat"),
        definition.metadata == Map("version" -> "v1"),
        definition.instructionSet.exists(_.fingerprint.matches("[0-9a-f]{64}"))
      )
    },
    test("按 System 与 Developer 权威级别稳定编译指令并拒绝重复 ID") {
      val base = AgentDefinitionBuilder(AgentId("layered-agent"), "Layered")
        .withInstructions("保持事实准确。")
        .addSystemInstruction("safety.medical", "2026-07", "不得替代医生诊疗。")
        .addDeveloperInstruction("business.answer", "3", "回答必须给出引用。")
      for
        first     <- base.build
        second    <- base.build
        duplicate <- base
          .addDeveloperInstruction("safety.medical", "2", "重复 ID。")
          .build
          .exit
        messages = first.instructionSet.fold(Chunk.empty[AgentMessage])(_.messages)
      yield assertTrue(
        messages.map(_.role) == Chunk(MessageRole.System, MessageRole.Developer),
        messages.headOption.exists(_.text.contains("[instruction:agent.core@1]")),
        messages.headOption.exists(_.text.contains("[instruction:safety.medical@2026-07]")),
        messages.lastOption.exists(_.text.contains("[instruction:business.answer@3]")),
        first.instructionSet.map(_.fingerprint) == second.instructionSet.map(_.fingerprint),
        duplicate.isFailure
      )
    },
    test("缺少指令时返回 typed configuration error 而不是构造半成品") {
      for exit <- AgentDefinitionBuilder(AgentId("invalid-agent"), "Invalid").build.exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.flatMap(_.failureOption).exists(_.category == ErrorCategory.Configuration)
      )
    },
    test("Agent 工具超出全局执行白名单时在启动阶段失败") {
      for
        exit <- AgentDefinitionBuilder(AgentId("tool-drift"), "工具漂移")
          .withInstructions("使用工具。")
          .allowTool(ToolName("publish_article"))
          .buildFor(ToolPolicyConfig(allowedTools = Set(ToolName("search_article"))))
          .exit
        message = exit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(
        exit.isFailure,
        message.contains("publish_article"),
        !message.contains("search_article")
      )
    },
    test("metadata 拒绝密钥字段和控制字符，模型参数拒绝非有限值") {
      val secret = AgentDefinitionBuilder(AgentId("secret-agent"), "Secret")
        .withInstructions("test")
        .withMetadata("api_key", "must-not-be-stored")
        .build
        .exit
      val invalidModel = AgentDefinitionBuilder(AgentId("model-agent"), "Model")
        .withInstructions("test")
        .withModelSettings(ModelSettings(temperature = Some(Double.NaN)))
        .build
        .exit
      for
        secretExit <- secret
        modelExit  <- invalidModel
      yield assertTrue(
        secretExit.isFailure,
        modelExit.isFailure,
        !secretExit.causeOption
          .flatMap(_.failureOption)
          .map(_.message)
          .getOrElse("")
          .contains("must-not-be-stored")
      )
    }
  )

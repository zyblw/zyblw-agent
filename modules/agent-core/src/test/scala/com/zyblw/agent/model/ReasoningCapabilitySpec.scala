package com.zyblw.agent.model

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object ReasoningCapabilitySpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("Reasoning capability")(
    test("explicit effort must be declared by the selected model") {
      val request = ChatRequest(
        Chunk(AgentMessage.user("solve")),
        settings = ModelSettings(provider = Some("test"), reasoningEffort = Some(ReasoningEffort.High))
      )
      for
        unsupported <- CapabilityValidator.validate(request, ModelCapabilities(thinking = true)).exit
        supported   <- CapabilityValidator
          .validate(
            request,
            ModelCapabilities(
              thinking = true,
              reasoningEfforts = Set(ReasoningEffort.Low, ReasoningEffort.High)
            )
          )
          .exit
      yield assertTrue(unsupported.isFailure, supported.isSuccess)
    }
  )

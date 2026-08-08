package com.zyblw.agent.core

import zio.test.*

object ModelHttpFailureSpec extends ZIOSpecDefault:
  def spec: Spec[Any, Nothing] = suite("ModelHttpFailure")(
    test("HTTP 状态映射为稳定分类且保留正确重试语义") {
      val cases = List(
        (400, ErrorCategory.Validation, false),
        (401, ErrorCategory.Authentication, false),
        (403, ErrorCategory.Authorization, false),
        (407, ErrorCategory.Authentication, false),
        (408, ErrorCategory.Timeout, true),
        (409, ErrorCategory.Conflict, true),
        (429, ErrorCategory.RateLimit, true),
        (500, ErrorCategory.Unavailable, true),
        (529, ErrorCategory.Unavailable, true)
      )

      assertTrue(cases.forall { case (status, category, retryable) =>
        val error = AgentError.ModelHttpFailure("stub", status)
        error.category == category && error.retryable == retryable
      })
    },
    test("只保留低基数 Provider code,不把正文或凭据带进错误与诊断") {
      val safe   = AgentError.ModelHttpFailure("stub", 401, Some("invalid_api_key"))
      val unsafe = AgentError.ModelHttpFailure("stub", 401, Some("key=sk-secret response body"))

      assertTrue(
        safe.providerCode.contains("invalid_api_key"),
        safe.message.contains("invalid_api_key"),
        safe.diagnostic.get("providerCode").contains("invalid_api_key"),
        unsafe.providerCode.isEmpty,
        !unsafe.message.contains("sk-secret"),
        !unsafe.diagnostic.values.exists(_.contains("sk-secret"))
      )
    }
  )

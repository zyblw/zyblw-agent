package com.zyblw.agent.mcp

import com.zyblw.agent.core.AgentError
import zio.test.*

/** MCP 日期版本分类不依赖 transport；它决定客户端会不会进入握手。 */
object McpProtocolVersionSpec extends ZIOSpecDefault:

  def spec = suite("McpProtocolVersion")(
    test("只把已测试的 2025-11-25 标为 Supported") {
      assertTrue(
        McpProtocolVersion.classify(McpProtocolVersion.Stable2025_11_25) == McpProtocolSupport.Supported,
        McpProtocolVersion.supported == Set(McpProtocolVersion.Stable2025_11_25)
      )
    },
    test("识别 2026-07-28 为已知但不支持的无状态修订") {
      McpProtocolVersion.classify(McpProtocolVersion.Known2026_07_28) match
        case McpProtocolSupport.KnownUnsupported(code, reason) =>
          assertTrue(code == "unsupported_stateless_revision", reason.contains("stateless"))
        case other => assertTrue(other.isInstanceOf[McpProtocolSupport.KnownUnsupported])
    },
    test("未知版本保持通用 fail-closed") {
      val version = McpProtocolVersion("2024-11-05")
      assertTrue(McpProtocolVersion.classify(version) == McpProtocolSupport.Unknown)
    },
    test("拒绝错误包含稳定错误码且不回显任意 payload") {
      val known   = McpProtocolVersion.rejection("initialize", McpProtocolVersion.Known2026_07_28)
      val unknown = McpProtocolVersion.rejection("initialize", McpProtocolVersion("draft"))
      assertTrue(
        known match
          case AgentError.ExternalProtocolFailure(
                "mcp",
                "initialize",
                message,
                Some("unsupported_stateless_revision"),
                false,
                _
              ) =>
            message.contains("2026-07-28") && !message.contains("{")
          case _ => false
        ,
        unknown match
          case AgentError.ExternalProtocolFailure(
                "mcp",
                "initialize",
                message,
                Some("unsupported_version"),
                false,
                _
              ) =>
            message.contains("draft")
          case _ => false
      )
    }
  )

package com.zyblw.agent.workspace

import com.zyblw.agent.composition.{
  ApprovalPolicyFingerprint,
  ApprovalSubject,
  AuthorizationFingerprint,
  ExecutionEnvironmentId,
  ToolContractFingerprint
}
import com.zyblw.agent.core.*
import com.zyblw.agent.execution.*
import com.zyblw.agent.tools.*
import java.nio.file.Path
import zio.*
import zio.json.ast.Json
import zio.test.*

/** MCP sandbox 适配器不能变宽，也不能让远端工具声明绕过本地审批主体。 */
object McpSandboxEnvironmentSpec extends ZIOSpecDefault:
  private val writeTool: RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition("write", "写入", Json.Obj("type" -> Json.Str("object")), None)
    val metadata   = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  private val oci = OciSandboxConfig(
    runtimeExecutable = Path.of("/usr/bin/docker"),
    imageDigest = "example.local/sandbox@sha256:" + "ab" * 32,
    workspaceRoot = Path.of("/tmp/zyblw-workspace")
  )

  def spec = suite("McpSandboxEnvironment")(
    test("workspace 与 OCI 都映射为 mcp-sandbox，且不能收窄后变宽") {
      val fromWs  = McpSandboxEnvironment.fromWorkspace(WorkspacePolicy())
      val fromOci = McpSandboxEnvironment.fromOci(oci)
      for
        child <- fromWs.narrow(
          fromWs.permissions.copy(filesystem = FilesystemAccess.Workspace("/workspace", writable = false))
        )
        wider <- child.narrow(PermissionProfile.host).exit
      yield assertTrue(
        fromWs.id == ExecutionEnvironmentId.mcpSandbox,
        fromOci.id == ExecutionEnvironmentId.mcpSandbox,
        fromWs.permissions == fromOci.permissions,
        !fromWs.permissions.permits(PermissionProfile.host),
        wider.isFailure
      )
    },
    test("MCP 工具的本地 metadata 仍决定审批，远端声明不能换成宿主审批主体") {
      val call   = ToolCall("c1", "write", Json.Obj("path" -> Json.Str("notes/a.md")))
      val policy = ToolPolicyConfig(allowedTools = Set(ToolName("write")))
      val local  = ApprovalSubject.of(
        call,
        writeTool.metadata,
        ToolContractFingerprint.registered(writeTool),
        ApprovalPolicyFingerprint.of(policy, ToolName("write")),
        AuthorizationFingerprint.of(RunContext())
      )
      val sandboxEnv = McpSandboxEnvironment.fromWorkspace(WorkspacePolicy())
      val sandboxed  = ApprovalSubject.of(
        call,
        writeTool.metadata,
        ToolContractFingerprint.registered(writeTool),
        ApprovalPolicyFingerprint.of(policy, ToolName("write")),
        AuthorizationFingerprint.of(RunContext()),
        sandboxEnv.id,
        sandboxEnv.permissions
      )
      assertTrue(
        writeTool.metadata.risk == ToolRisk.ApprovalWrite,
        local.driftFrom(sandboxed) == List("environment", "permissions"),
        sandboxed.environment == ExecutionEnvironmentId.mcpSandbox
      )
    }
  )

package com.zyblw.agent.execution

import com.zyblw.agent.composition.ExecutionEnvironmentId
import com.zyblw.agent.core.AgentError
import zio.*
import zio.test.*

/** 受约束执行：子 scope 只能收窄，环境身份进入审批主体。 */
object ExecutionEnvironmentSpec extends ZIOSpecDefault:
  def spec = suite("ExecutionEnvironment")(
    test("宿主权限允许收窄到 DenyAll，不允许从 DenyAll 变宽") {
      val host    = PermissionProfile.host
      val denyAll = PermissionProfile.denyAll
      val sandbox = PermissionProfile(
        filesystem = FilesystemAccess.Workspace("/workspace", writable = true),
        network = NetworkAccess.DenyAll,
        process = ProcessAccess.DenyAll,
        secrets = SecretAccess.DenyAll
      )
      val readOnly = sandbox.copy(filesystem = FilesystemAccess.Workspace("/workspace", writable = false))
      assertTrue(
        host.permits(denyAll),
        host.permits(sandbox),
        sandbox.permits(readOnly),
        !readOnly.permits(sandbox),
        !denyAll.permits(host),
        !sandbox.permits(PermissionProfile.host)
      )
    },
    test("Local.narrow 拒绝变宽") {
      for
        local <- ZIO.succeed(LocalExecutionEnvironment.default)
        same  <- local.narrow(PermissionProfile.host)
        child <- local.narrow(PermissionProfile.denyAll)
        wider <- child.narrow(PermissionProfile.host).exit
      yield assertTrue(
        local.id == ExecutionEnvironmentId.local,
        same.permissions == PermissionProfile.host,
        child.permissions == PermissionProfile.denyAll,
        wider.isFailure,
        wider.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.InvalidConfiguration])
      )
    },
    test("MCP sandbox 身份与 local 不同，因此不会复用宿主审批") {
      val sandbox = ConstrainedExecutionEnvironment(
        ExecutionEnvironmentId.mcpSandbox,
        PermissionProfile(
          filesystem = FilesystemAccess.Workspace("/workspace", writable = true),
          network = NetworkAccess.DenyAll,
          process = ProcessAccess.DenyAll,
          secrets = SecretAccess.DenyAll
        )
      )
      assertTrue(
        sandbox.id != ExecutionEnvironmentId.local,
        sandbox.id == ExecutionEnvironmentId.mcpSandbox,
        !sandbox.permissions.permits(PermissionProfile.host)
      )
    },
    test("权限剖面指纹与 JSON 字段顺序无关") {
      val left = PermissionProfile(
        filesystem = FilesystemAccess.Workspace("/workspace", writable = true),
        network = NetworkAccess.Allowlist(Chunk("a.example", "b.example")),
        process = ProcessAccess.DenyAll,
        secrets = SecretAccess.Named(Chunk("db", "api"))
      )
      val right = PermissionProfile(
        secrets = SecretAccess.Named(Chunk("api", "db")),
        process = ProcessAccess.DenyAll,
        network = NetworkAccess.Allowlist(Chunk("b.example", "a.example")),
        filesystem = FilesystemAccess.Workspace("/workspace", writable = true)
      )
      assertTrue(left.fingerprint == right.fingerprint, left.fingerprint.length == 64)
    }
  )

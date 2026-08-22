package com.zyblw.agent.workspace

import com.zyblw.agent.composition.ExecutionEnvironmentId
import com.zyblw.agent.execution.*

/** 把现有 MCP workspace / OCI sandbox 映射为受限执行环境。
  *
  * Kernel 只认识身份与权限剖面；本适配器留在 `agent-mcp`，不把 Docker/K8s 实现送进 Runtime。装配时由 Host 把返回值放进
  * `RuntimeExtensions.environment`。默认 Local 不得进入 `extensionIds`，否则历史 Run 全部 Incompatible。
  */
object McpSandboxEnvironment:
  /** OCI 与 LocalWorkspace 共同使用的容器内根标签。 */
  val workspaceRootLabel: String = "/workspace"

  /** MCP workspace 文件访问：只允许该根目录，无宿主网络/进程/secret 解引用。
    *
    * 字节/条目配额仍由 [[WorkspacePolicy]] 在 Workspace 实现里强制，不复制进 PermissionProfile。
    */
  def fromWorkspace(policy: WorkspacePolicy, writable: Boolean = true): ExecutionEnvironment =
    val _ = policy.maxFileBytes
    constrained(writable)

  /** OCI sandbox：`--network none`、只挂载 `/workspace`、不继承宿主进程。镜像 digest 与资源上限仍由 OCI 配置强制。 */
  def fromOci(config: OciSandboxConfig): ExecutionEnvironment =
    val _ = config.imageDigest
    constrained(writable = true)

  def constrained(writable: Boolean): ExecutionEnvironment =
    ConstrainedExecutionEnvironment(
      ExecutionEnvironmentId.mcpSandbox,
      PermissionProfile(
        filesystem = FilesystemAccess.Workspace(workspaceRootLabel, writable),
        network = NetworkAccess.DenyAll,
        process = ProcessAccess.DenyAll,
        secrets = SecretAccess.DenyAll
      )
    )

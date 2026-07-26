# Workspace 与 OCI Sandbox

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

`zyblw-agent-mcp` 中的 `workspace` package 把“业务文件访问”和“不可信进程执行”拆成两个不同信任边界：

- `LocalWorkspace` 只负责受限文件读写，不执行代码，也不等于安全沙箱。
- `OciSandboxExecutor` 通过 Docker/Podman 兼容 OCI CLI 启动一次性容器，执行模型经权限、审批和工具策略允许的命令；
  `SandboxSessionLauncher` 为 stdio MCP 提供受 Scope 管理的双向会话。
- `SandboxExecutor.disabled` 是默认 Layer；业务未显式装配 OCI 实现时，任何命令都会被拒绝。

这种默认拒绝很重要：模型可以提出动作，但不能因为某个依赖被引入就自动获得宿主命令执行权限。

## 一、LocalWorkspace

### 安全约束

`WorkspacePath` 在构造时拒绝：

- 绝对路径；
- `.`、`..`、空路径段；
- Windows 反斜杠；
- NUL 字符。

`LocalWorkspace` 在每次操作时还会：

- 把目标约束在配置根目录内；
- 使用 `NOFOLLOW_LINKS` 检查文件属性；
- 拒绝根目录和已有路径段中的符号链接；
- 只读取普通文件；
- 对单文件、Workspace 总容量和单次列举条数设置上限；
- 使用同目录临时文件和原子 move 写入，防止读到半个文件；
- 只删除普通文件或空目录，不递归删除目录树。

```scala
import com.zyblw.agent.workspace.*
import java.nio.file.Path
import zio.*

val workspace = LocalWorkspace(
  Path.of("/srv/zyblw-agent/workspaces/run-123"),
  WorkspacePolicy(
    maxFileBytes = 8L * 1024 * 1024,
    maxTotalBytes = 128L * 1024 * 1024,
    maxEntries = 10_000,
    allowDelete = true
  )
)

val save: IO[com.zyblw.agent.core.AgentError, Unit] =
  workspace.write(
    WorkspacePath("reports/answer.md"),
    Chunk.fromArray("有引用的结果".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
    overwrite = false
  )
```

`LocalWorkspace` 防御来自模型和远端工具的路径输入，但不能抵御同时控制宿主机文件系统的恶意本地进程造成的
symlink TOCTOU 竞态。不可信代码仍必须进入容器或更强隔离环境。

## 二、OCI Sandbox

### 默认安全策略

`OciSandboxExecutor` 生成的命令不经过 shell，并固定使用以下策略：

- 镜像必须是 `repository@sha256:<64 hex>`，并设置 `--pull never`；
- `--network none` 与 `--ipc none`；
- 根文件系统 `--read-only`，只有 `/workspace` 是显式读写 bind mount；
- `/tmp` 是带容量上限且 `noexec,nosuid,nodev` 的 tmpfs；
- `--cap-drop ALL` 与 `no-new-privileges=true`；
- 非 root 数值 `uid:gid`；
- 内存、swap、CPU、PID、打开文件数、墙钟时间和 stdout+stderr 总字节预算；
- 临时容器 `--rm`，并通过 `--init` 回收容器内孤儿进程；
- 不继承宿主环境，只使用部署配置和本次命令明确允许的变量；
- secret 值不进入 argv，只通过 `--env KEY` 和 `ProcessBuilder.environment` 传递。

```scala
import com.zyblw.agent.workspace.*
import java.nio.file.Path
import zio.*

val config = OciSandboxConfig(
  runtimeExecutable = Path.of("/usr/bin/docker"),
  imageDigest = "registry.example/zyblw/sandbox@sha256:<真实的64位摘要>",
  workspaceRoot = Path.of("/srv/zyblw-agent/workspaces/run-123"),
  // 若 Docker CLI 确实需要 HOME/DOCKER_HOST，应由可信部署配置显式注入；不要复制整个 sys.env。
  runtimeEnvironment = Map.empty,
  containerUser = "65532:65532",
  limits = OciSandboxLimits(
    memoryBytes = 512L * 1024 * 1024,
    cpus = BigDecimal("1.0"),
    pids = 128,
    tmpfsBytes = 64L * 1024 * 1024,
    maxCommandTimeout = 5.minutes,
    maxCapturedOutputBytes = 4 * 1024 * 1024
  )
)

val program = for
  executor <- ZIO.service[SandboxExecutor]
  result   <- executor.execute(
                SandboxCommand(
                  executable = "/usr/local/bin/report-tool",
                  arguments = Chunk("--input", "source.json"),
                  workingDirectory = WorkspacePath("work"),
                  environment = Map("BUSINESS_API_TOKEN" -> "由 Secret Manager 注入"),
                  timeout = 30.seconds,
                  maxOutputBytes = 1024 * 1024
                )
              )
yield result

val runnable = program.provide(
  SandboxProcessRunner.live,
  OciSandboxExecutor.layer(config)
)
```

工作目录必须预先存在于 `workspaceRoot`，且从根到工作目录不得包含 symlink。容器内 executable 必须是绝对路径，
从而不会依赖容器 PATH 解析。`DOCKER_*`、`PODMAN_*`、`CONTAINER_*`、`XDG_*`、`PATH`、`HOME` 等会影响宿主 CLI
的名称不能由单次命令注入。

### ZIO 生命周期语义

真实进程运行器使用 `ZIO.scoped` 管理 `Process`：

1. `ProcessBuilder` 直接接收 argv，不执行 shell 展开；
2. 两个阻塞 Fiber 并行排空 stdout/stderr，避免管道背压死锁；
3. 两条流共享一个输出预算，超限后继续排空但不再保留，并返回 `truncated=true`；
4. 正常退出保留真实 exit code；
5. 墙钟超时返回可重试的 `ExternalProtocolFailure(code=timeout)`；
6. 调用 Fiber 被取消时，中断会向下传播，并先请求终止、再按宽限期强制结束进程；
7. 错误不包含 argv、环境值或 stdout/stderr，避免把 secret 投影到日志与 telemetry。

## 三、部署与威胁边界

容器是重要隔离层，但不是无限强的安全边界：

- Docker daemon 或 Podman runtime 本身必须由可信基础设施管理；不要把 Docker socket 挂进容器。
- 建议使用 rootless runtime；但 Docker 官方说明 rootless 的 cgroup 资源限制依赖 cgroup v2 和 systemd，部署验收必须
  实际确认限制生效，而不是只看到 CLI 参数。
- `--network none` 仍保留容器 loopback，但没有外部网络接口。本版不伪装支持域名/IP allowlist；真正受控联网需要独立
  egress proxy、DNS/IP 重绑定防护、TLS 身份和审计。
- 摘要固定只阻止镜像漂移，不证明镜像可信。生产还应执行签名验证、SBOM/漏洞扫描和 registry 准入。
- 框架依赖运行时默认 seccomp；生产可进一步用 AppArmor/SELinux、只读宿主挂载或 gVisor/Kata/微虚拟机增强隔离。
- `/workspace` 是刻意保留的写边界；每个 Run/tenant 应使用独立目录和宿主权限，不得共享全局业务目录。
- stdout/stderr 可能包含业务敏感内容。runner 不记录正文，但调用方在展示、持久化或遥测前仍必须脱敏。

stdio MCP 已通过独立 `SandboxProcessSession`/`SandboxSessionLauncher` 接入同一 OCI hardening 参数，并与宿主 stdio 路径
共用协议 framing。这里仍不意味着真实容器部署已经完成安全验收：rootless/cgroup、生效的 seccomp/LSM、镜像签名、恶意
package、SIGKILL 和容器逃逸必须在实际 Docker/Podman 环境单独演练。

## 四、验证

```bash
sbt "mcp/testFull"
```

测试覆盖路径穿越、symlink 逃逸、原子写入、覆盖策略、配额、安全 OCI argv、secret 不进入 argv、保留环境变量拒绝、
并行输出排空、合计输出截断、真实 JDK 慢进程超时，以及 MCP scoped session 的协议调用与 Scope 回收。默认测试不依赖 Docker；真实 rootless/cgroup/镜像/容器逃逸和
SIGKILL 演练仍应作为独立的部署环境门禁。

## 五、官方依据

- [Docker run reference](https://docs.docker.com/reference/cli/docker/container/run/)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)
- [Docker none network driver](https://docs.docker.com/engine/network/drivers/none/)
- [Docker rootless tips and cgroup limitations](https://docs.docker.com/engine/security/rootless/tips/)
- [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec)

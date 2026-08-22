package com.zyblw.agent.execution

import com.zyblw.agent.composition.{CanonicalDigest, ExecutionEnvironmentId}
import com.zyblw.agent.core.AgentError
import zio.*
import zio.json.*

/** 文件系统权限。`HostUnrestricted` 只描述「与 JVM 进程相同」，不是授权绕过 ToolPolicy。 */
enum FilesystemAccess derives JsonCodec:
  case DenyAll
  case HostUnrestricted
  case Workspace(rootLabel: String, writable: Boolean)

object FilesystemAccess:
  def permits(parent: FilesystemAccess, child: FilesystemAccess): Boolean =
    (parent, child) match
      case (FilesystemAccess.DenyAll, FilesystemAccess.DenyAll) => true
      case (FilesystemAccess.DenyAll, _)                        => false
      case (_, FilesystemAccess.DenyAll)                        => true
      case (FilesystemAccess.HostUnrestricted, _)               => true
      case (
            FilesystemAccess.Workspace(root, writable),
            FilesystemAccess.Workspace(childRoot, childWritable)
          ) =>
        root == childRoot && (writable || !childWritable)
      case (FilesystemAccess.Workspace(_, _), _) => false

/** 网络权限。Allowlist 只能是父 Allowlist 的子集，或从 HostUnrestricted 收窄而来。 */
enum NetworkAccess derives JsonCodec:
  case DenyAll
  case HostUnrestricted
  case Allowlist(hosts: Chunk[String])

object NetworkAccess:
  def permits(parent: NetworkAccess, child: NetworkAccess): Boolean =
    (parent, child) match
      case (NetworkAccess.DenyAll, NetworkAccess.DenyAll)                              => true
      case (NetworkAccess.DenyAll, _)                                                  => false
      case (_, NetworkAccess.DenyAll)                                                  => true
      case (NetworkAccess.HostUnrestricted, _)                                         => true
      case (NetworkAccess.Allowlist(parentHosts), NetworkAccess.Allowlist(childHosts)) =>
        childHosts.toSet.subsetOf(parentHosts.toSet)
      case (NetworkAccess.Allowlist(_), _) => false

enum ProcessAccess derives JsonCodec:
  case DenyAll, HostUnrestricted

object ProcessAccess:
  def permits(parent: ProcessAccess, child: ProcessAccess): Boolean =
    (parent, child) match
      case (ProcessAccess.DenyAll, ProcessAccess.DenyAll)          => true
      case (ProcessAccess.DenyAll, ProcessAccess.HostUnrestricted) => false
      case (ProcessAccess.HostUnrestricted, _)                     => true

enum SecretAccess derives JsonCodec:
  case DenyAll
  case HostUnrestricted
  case Named(refs: Chunk[String])

object SecretAccess:
  def permits(parent: SecretAccess, child: SecretAccess): Boolean =
    (parent, child) match
      case (SecretAccess.DenyAll, SecretAccess.DenyAll)                    => true
      case (SecretAccess.DenyAll, _)                                       => false
      case (_, SecretAccess.DenyAll)                                       => true
      case (SecretAccess.HostUnrestricted, _)                              => true
      case (SecretAccess.Named(parentRefs), SecretAccess.Named(childRefs)) =>
        childRefs.toSet.subsetOf(parentRefs.toSet)
      case (SecretAccess.Named(_), _) => false

/** 一次执行环境绑定的权限剖面。子 scope 只能收窄，变宽必须由受信 Runtime 显式授予新的环境。 */
final case class PermissionProfile(
    filesystem: FilesystemAccess = FilesystemAccess.HostUnrestricted,
    network: NetworkAccess = NetworkAccess.HostUnrestricted,
    process: ProcessAccess = ProcessAccess.HostUnrestricted,
    secrets: SecretAccess = SecretAccess.HostUnrestricted
) derives JsonCodec:
  def permits(child: PermissionProfile): Boolean =
    FilesystemAccess.permits(filesystem, child.filesystem) &&
      NetworkAccess.permits(network, child.network) &&
      ProcessAccess.permits(process, child.process) &&
      SecretAccess.permits(secrets, child.secrets)

  /** 进入审批主体与组合指纹的稳定摘要；不含 secret 值。Allowlist / Named 与书写顺序无关。 */
  def fingerprint: String =
    canonical.toJsonAST.toOption
      .map(json => CanonicalDigest.sha256(CanonicalDigest.canonicalize(json).toJson))
      .getOrElse(throw IllegalStateException("PermissionProfile 无法编码为 JSON"))

  private def canonical: PermissionProfile =
    val sortedNetwork = network match
      case NetworkAccess.Allowlist(hosts) =>
        NetworkAccess.Allowlist(
          Chunk.fromIterable(hosts.map(_.trim).filter(_.nonEmpty).toList.distinct.sorted)
        )
      case other => other
    val sortedSecrets = secrets match
      case SecretAccess.Named(refs) =>
        SecretAccess.Named(Chunk.fromIterable(refs.map(_.trim).filter(_.nonEmpty).toList.distinct.sorted))
      case other => other
    copy(network = sortedNetwork, secrets = sortedSecrets)

object PermissionProfile:
  val host: PermissionProfile    = PermissionProfile()
  val hostFingerprint: String    = host.fingerprint
  val denyAll: PermissionProfile = PermissionProfile(
    FilesystemAccess.DenyAll,
    NetworkAccess.DenyAll,
    ProcessAccess.DenyAll,
    SecretAccess.DenyAll
  )

/** 工具副作用发生的约束执行环境。Kernel 只认识身份与权限剖面；Docker/K8s/远程执行器是后续 adapter。 */
trait ExecutionEnvironment:
  def id: ExecutionEnvironmentId
  def permissions: PermissionProfile

  /** 派生更窄的子环境；变宽失败。 */
  def narrow(child: PermissionProfile): IO[AgentError.InvalidConfiguration, ExecutionEnvironment]

/** 现行默认：工具在宿主 JVM 内直接执行，除 ToolPolicy / 审批外没有额外隔离。 */
final class LocalExecutionEnvironment(val permissions: PermissionProfile = PermissionProfile.host)
    extends ExecutionEnvironment:
  val id: ExecutionEnvironmentId = ExecutionEnvironmentId.local

  def narrow(child: PermissionProfile): IO[AgentError.InvalidConfiguration, ExecutionEnvironment] =
    if permissions.permits(child) then ZIO.succeed(LocalExecutionEnvironment(child))
    else ZIO.fail(AgentError.InvalidConfiguration(s"执行环境 ${id.value} 的权限只能收窄，不能变宽"))

object LocalExecutionEnvironment:
  val default: ExecutionEnvironment = LocalExecutionEnvironment()

/** 非 `local` 的受限执行器，例如 MCP workspace / OCI sandbox。身份进入审批主体，环境变化会使历史批准失效。 */
final class ConstrainedExecutionEnvironment(
    val id: ExecutionEnvironmentId,
    val permissions: PermissionProfile
) extends ExecutionEnvironment:
  def narrow(child: PermissionProfile): IO[AgentError.InvalidConfiguration, ExecutionEnvironment] =
    if permissions.permits(child) then ZIO.succeed(ConstrainedExecutionEnvironment(id, child))
    else ZIO.fail(AgentError.InvalidConfiguration(s"执行环境 ${id.value} 的权限只能收窄，不能变宽"))

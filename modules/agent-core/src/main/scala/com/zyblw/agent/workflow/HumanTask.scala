package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import java.time.{Duration, Instant}
import zio.ZIO

/** 人工任务是 Workflow Signal 的受控子集：名称固定 `human.<taskType>`，不能用用户正文当 signal 名。 */
object HumanTask:
  val Prefix: String = "human."

  def signalName(taskType: String): Either[String, WorkflowSignalName] =
    val normalized = Option(taskType).fold("")(_.trim)
    if normalized.isEmpty || normalized.contains('.') || normalized.length > 140 then
      Left("HumanTask.taskType 必须是 1..140 位且不含点")
    else WorkflowSignalName.fromString(Prefix + normalized)

  def await(taskType: String, deadline: Instant): Either[String, WorkflowWaitRequest] =
    signalName(taskType).map(name => WorkflowWaitRequest(WorkflowWaitCondition.Signal(name), deadline))

  def isHuman(name: WorkflowSignalName): Boolean =
    name.value.startsWith(Prefix)

  /** 定义期固化 taskType；resume 只接受同名 human signal 或 deadline。 */
  def node[S](
      nodeId: NodeId,
      taskType: String,
      ttl: Duration,
      onApproved: (S, WorkflowSignalValue) => S,
      onTimeout: S => S,
      expectedAuthorization: Option[com.zyblw.agent.composition.AuthorizationFingerprint] = None
  ): Either[String, WorkflowNode[Any, S]] =
    if ttl.isZero || ttl.isNegative then Left("HumanTask.ttl 必须为正")
    else
      signalName(taskType).map { expected =>
        new WorkflowNode[Any, S]:
          val id: NodeId = nodeId

          def execute(state: S, context: WorkflowContext): ZIO[Any, WorkflowError, NodeOutcome[S]] =
            context.wakeup match
              case Some(WorkflowWakeup.SignalReceived(_, value)) if value.name == expected =>
                expectedAuthorization match
                  case Some(required) if value.authorization != required =>
                    ZIO.fail(AgentError.WorkflowFailed(nodeId.value, "human-task-authorization-mismatch"))
                  case _ =>
                    ZIO.succeed(NodeOutcome.Succeeded(onApproved(state, value)))
              case Some(WorkflowWakeup.DeadlineElapsed(_, _)) =>
                ZIO.succeed(NodeOutcome.Succeeded(onTimeout(state)))
              case Some(WorkflowWakeup.SignalReceived(_, _)) =>
                ZIO.fail(AgentError.WorkflowFailed(nodeId.value, "human-task-signal-mismatch"))
              case None =>
                zio.Clock.instant.flatMap { now =>
                  ZIO
                    .fromEither(HumanTask.await(taskType, now.plus(ttl)))
                    .mapError(message => AgentError.WorkflowFailed(nodeId.value, message))
                    .map(request => NodeOutcome.Awaiting(state, request))
                }
      }

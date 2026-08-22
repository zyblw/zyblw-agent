package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunCommandPayload
import zio.*
import zio.test.*

/** Steer/FollowUp 与 ControlCommand 分离；Active Goal 不因 Steer 改变。 */
object InteractionStoreSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("InteractionStore")(
    test("Steer/FollowUp/UserMessage 不是 Cancel/Recover/Approval/Retry") {
      val interaction = InteractionKind.values.map(_.toString).toSet
      val control     = Set("Start", "Recover", "ResumeApproval", "Cancel", "Retry")
      assertTrue(
        interaction == Set("Steer", "FollowUp", "UserMessage"),
        interaction.intersect(control).isEmpty,
        RunCommandPayload.Start.commandType == "Start",
        RunCommandPayload.Recover.commandType == "Recover",
        RunCommandPayload.Cancel(None).commandType == "Cancel",
        RunCommandPayload.Retry("ops").commandType == "Retry"
      )
    },
    test("追加 Steer 不改变 Goal 状态，也不会变成控制命令") {
      (for
        store         <- ZIO.service[HarnessStore]
        goalId        <- GoalId.random
        interactionId <- InteractionId.random
        saved         <- store.saveGoal(0L, Goal(goalId, ThreadId("thread-a"), "整理引用", GoalStatus.Active))
        missing       <- store
          .appendInteraction(
            InteractionInput(
              InteractionId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
              GoalId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
              InteractionKind.FollowUp,
              "再查桂枝汤"
            )
          )
          .either
        first <- store.appendInteraction(
          InteractionInput(interactionId, goalId, InteractionKind.Steer, "先列出处再翻译")
        )
        followId <- InteractionId.random
        second   <- store.appendInteraction(
          InteractionInput(followId, goalId, InteractionKind.FollowUp, "补一条煎服法")
        )
        userMessageId <- InteractionId.random
        third         <- store.appendInteraction(
          InteractionInput(userMessageId, goalId, InteractionKind.UserMessage, "最终按表格输出")
        )
        latest  <- store.listInteractions(goalId, beforeSequence = None, limit = 2)
        earlier <- store.listInteractions(goalId, beforeSequence = Some(third.sequence), limit = 2)
        invalid <- store.listInteractions(goalId, beforeSequence = None, limit = 0).exit
        loaded  <- store.getGoal(goalId)
      yield assertTrue(
        saved.status == GoalStatus.Active,
        loaded.contains(saved),
        first.sequence == 1L,
        second.sequence == 2L,
        latest.map(_.kind) == Chunk(InteractionKind.FollowUp, InteractionKind.UserMessage),
        earlier.map(_.kind) == Chunk(InteractionKind.Steer, InteractionKind.FollowUp),
        invalid.isFailure,
        missing == Left(
          AgentError.HarnessNotFound("goal", "00000000-0000-0000-0000-000000000002")
        )
      )).provideLayer(HarnessStore.inMemory)
    }
  )

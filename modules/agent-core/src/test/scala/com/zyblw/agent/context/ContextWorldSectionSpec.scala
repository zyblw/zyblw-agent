package com.zyblw.agent.context

import zio.*
import zio.json.*
import zio.test.*

/** world-state section 差量只优化重复发送；Secret 永不进入模型可见正文。 */
object ContextWorldSectionSpec extends ZIOSpecDefault:
  def spec = suite("ContextWorldSections")(
    test("显式可信 stateful delta 下指纹相同则 Unchanged 且不渲染正文") {
      val snapshot = ContextSectionSnapshot.of("goal", "learn chinese medicine")
      val plan     = ContextWorldSections.plan(Chunk(snapshot.cursor), Chunk(snapshot), omitUnchanged = true)
      assertTrue(
        plan.decisions == Chunk(ContextSectionDecision.Unchanged("goal", snapshot.fingerprint)),
        plan.messages.isEmpty,
        plan.cursors == Chunk(snapshot.cursor)
      )
    },
    test("无状态请求默认重复渲染完整快照") {
      val snapshot = ContextSectionSnapshot.of("goal", "must remain visible")
      val plan     = ContextWorldSections.plan(Chunk(snapshot.cursor), Chunk(snapshot))
      assertTrue(
        plan.decisions == Chunk(ContextSectionDecision.Rendered("goal", snapshot.fingerprint)),
        plan.messages.exists(_.text.contains("must remain visible"))
      )
    },
    test("新 section 或指纹变化则 Rendered") {
      val first  = ContextSectionSnapshot.of("goal", "learn")
      val second = ContextSectionSnapshot.of("goal", "learn harder")
      val absent = ContextWorldSections.plan(Chunk.empty, Chunk(first))
      val delta  = ContextWorldSections.plan(Chunk(first.cursor), Chunk(second))
      assertTrue(
        absent.decisions == Chunk(ContextSectionDecision.Rendered("goal", first.fingerprint)),
        absent.messages.exists(_.text.contains("learn")),
        delta.decisions == Chunk(ContextSectionDecision.Rendered("goal", second.fingerprint)),
        delta.messages.exists(_.text.contains("learn harder")),
        !delta.messages.exists(_.text.contains("learn\n"))
      )
    },
    test("Secret 被抑制，游标丢失时 Unknown 必须重新渲染") {
      val secret = ContextSectionSnapshot.of(
        "vault",
        "hunter2",
        ContextPayloadSensitivity.Secret
      )
      val public     = ContextSectionSnapshot.of("goal", "visible")
      val suppressed = ContextWorldSections.plan(Chunk.empty, Chunk(secret))
      val unknown    = ContextWorldSections.plan(Chunk.empty, Chunk(public), previousKnown = false)
      assertTrue(
        suppressed.decisions == Chunk(ContextSectionDecision.Suppressed("vault", "secret")),
        suppressed.messages.isEmpty,
        !suppressed.cursors.toJson.contains("hunter2"),
        unknown.decisions == Chunk(ContextSectionDecision.Rendered("goal", public.fingerprint)),
        unknown.messages.exists(_.text.contains("visible"))
      )
    },
    test("决策 lineage 不含 payload") {
      val snapshot = ContextSectionSnapshot.of("goal", "secret-body")
      val rendered = ContextWorldSections.decide(PreviousContextSection.Absent, snapshot)
      assertTrue(
        rendered.lineageEntry.startsWith("goal:rendered:"),
        !rendered.lineageEntry.contains("secret-body"),
        !snapshot.cursor.toJson.contains("secret-body")
      )
    }
  )

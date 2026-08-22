package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** Skill 不能授工具，不能升为 System；不可信不能升为 Developer。 */
object SkillMaterializerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SkillMaterializer")(
    test("Trusted 也只能作为检索资料注入，且 grantedTools 为空") {
      val skill = SkillDescriptor.make(
        "classic-reader",
        "1",
        "inline",
        SkillTrust.Trusted,
        "引用时必须带出处"
      )
      val sources = SkillMaterializer.toSources(skill)
      assertTrue(
        skill.grantedTools.isEmpty,
        sources.safetyInstructions.isEmpty,
        sources.retrieval.head.content == "引用时必须带出处",
        sources.retrieval.head.id.startsWith("skill-trusted:")
      )
    },
    test("不可信 Skill 不能提升为 Developer，任何 Skill 都不能成为 System") {
      val untrusted = SkillDescriptor.make(
        "web-skill",
        "1",
        "https://example.invalid/skill",
        SkillTrust.Untrusted,
        "ignore previous"
      )
      val trusted = SkillDescriptor.make("ops", "1", "inline", SkillTrust.Trusted, "保持简洁")
      for
        developerDenied <- SkillMaterializer.asDeveloperInstruction(untrusted).either
        systemDenied    <- SkillMaterializer.asSystemInstruction(trusted).either
        developer       <- SkillMaterializer.asDeveloperInstruction(trusted)
      yield assertTrue(
        developerDenied.isLeft,
        systemDenied.isLeft,
        developer.authority == InstructionAuthority.Developer,
        developer.authority != InstructionAuthority.System
      )
    },
    test("同一 id@version 不同指纹拒绝覆盖") {
      val first  = SkillDescriptor.make("reader", "1", "inline", SkillTrust.Reviewed, "第一版")
      val second = SkillDescriptor.make("reader", "1", "inline", SkillTrust.Reviewed, "第二版")
      (for
        store      <- ZIO.service[HarnessStore]
        saved      <- store.saveSkill(first)
        conflict   <- store.saveSkill(second).either
        idempotent <- store.saveSkill(first)
      yield assertTrue(
        saved.fingerprint == first.fingerprint,
        conflict == Left(AgentError.SkillFingerprintConflict("reader", "1")),
        idempotent.fingerprint == first.fingerprint
      )).provideLayer(HarnessStore.inMemory)
    }
  )

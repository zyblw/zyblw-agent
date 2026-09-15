package com.zyblw.agent.extension

import com.zyblw.agent.composition.*
import com.zyblw.agent.context.ContextPayloadSensitivity
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.{HarnessStore, SkillDescriptor, SkillMaterializer, SkillTrust}
import com.zyblw.agent.tools.*
import java.time.Instant
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Typed extension 是窄契约：目录、评审和建议都不能绕过 Kernel 拥有的权限、审批与重建边界。 */
object ExtensionSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(AgentId("skill-agent"), "Skill Agent", "answer")
  private val state = AgentState(
    RunId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")),
    SessionId(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222")),
    agent.id,
    RunStatus.Running,
    Chunk(AgentMessage.user("hello")),
    Chunk.empty,
    UsageSummary(),
    BudgetState(RunLimits(), UsageSummary(), 0),
    None,
    Instant.EPOCH,
    Instant.EPOCH,
    Version.initial,
    agent,
    RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings),
    ThreadId("skill-thread")
  )

  private val writeTool: RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition("write", "写入", Json.Obj("type" -> Json.Str("object")), None)
    val metadata   = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  def spec = suite("Typed Extension API")(
    test("Skill 目录不含正文，也不能授予工具") {
      val skill = SkillDescriptor.make(
        "draft",
        "1",
        "file://skills/draft.md",
        SkillTrust.Trusted,
        "you may write files"
      )
      val provider = SkillProvider.static("team-skills", Chunk(skill))
      for
        entries <- provider.catalog
        loaded  <- provider.load("draft", "1")
        asDev   <- SkillMaterializer.asDeveloperInstruction(loaded)
        asSys   <- SkillMaterializer.asSystemInstruction(loaded).exit
      yield assertTrue(
        entries == Chunk(SkillCatalogEntry.of(skill)),
        !entries.toJson.contains("you may write files"),
        entries.forall(_.grantedTools.isEmpty),
        loaded.grantedTools.isEmpty,
        asDev.authority == InstructionAuthority.Developer,
        asSys.isFailure
      )
    },
    test("重复扩展身份在装配期失败") {
      val first  = ToolProvider.static("tools", List(writeTool))
      val second = ToolProvider.static("tools", List(writeTool))
      assertTrue(
        scala.util.Try(RuntimeExtensions(toolProviders = Chunk(first, second))).isFailure
      )
    },
    test("ExtensionInput 与评审结果都不携带工具参数正文") {
      val call    = ToolCall("c1", "write", Json.Obj("secret" -> Json.Str("hunter2")))
      val subject = ApprovalSubject.of(
        call,
        writeTool.metadata,
        ToolContractFingerprint.registered(writeTool),
        ApprovalPolicyFingerprint
          .of(ToolPolicyConfig(allowedTools = Set(ToolName("write"))), ToolName("write")),
        AuthorizationFingerprint.of(RunContext(tenantId = Some("t-1")))
      )
      val input = ExtensionInput(
        RunId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")),
        AgentId("ext"),
        AuthorizationFingerprint.of(RunContext(tenantId = Some("t-1"))),
        None
      )
      val review = ApprovalReview.RecommendAllow("looks safe")
      assertTrue(
        !subject.toJson.contains("hunter2"),
        !input.toJson.contains("hunter2"),
        review != ApprovalReview.Abstain
      )
    },
    test("Skill 目录签名随条目与 trust 变化，且不含正文") {
      val first  = SkillDescriptor.make("draft", "1", "file://skills/draft.md", SkillTrust.Reviewed, "body-a")
      val second =
        SkillDescriptor.make("other", "1", "file://skills/other.md", SkillTrust.Untrusted, "body-b")
      val a = SkillCatalogSignature.fingerprint(Chunk(SkillCatalogEntry.of(first)))
      val b =
        SkillCatalogSignature.fingerprint(Chunk(SkillCatalogEntry.of(first), SkillCatalogEntry.of(second)))
      val c =
        SkillCatalogSignature.fingerprint(Chunk(SkillCatalogEntry.of(second), SkillCatalogEntry.of(first)))
      assertTrue(a != b, b == c, a.matches("[0-9a-f]{64}"))
    },
    test("Skill 目录 section 不含正文") {
      val skill = SkillDescriptor.make(
        "draft",
        "1",
        "file://skills/draft.md",
        SkillTrust.Trusted,
        "you may write files"
      )
      val section = SkillCatalogSection.snapshot(Chunk(SkillCatalogEntry.of(skill)))
      assertTrue(
        section.id == "skill-catalog",
        section.sensitivity == ContextPayloadSensitivity.Metadata,
        section.payload.contains("draft@1"),
        !section.payload.contains("you may write files")
      )
    },
    test("SkillContextContributor 只按宿主选择加载正文且不授予工具") {
      val selected = SkillDescriptor.make(
        "selected",
        "1",
        "file://skills/selected.md",
        SkillTrust.Reviewed,
        "selected body"
      )
      val unselected = SkillDescriptor.make(
        "unselected",
        "1",
        "file://skills/unselected.md",
        SkillTrust.Trusted,
        "must stay unloaded"
      )
      val provider    = SkillProvider.static("team-skills", Chunk(selected, unselected))
      val contributor = SkillContextContributor(Chunk(provider), Chunk("selected" -> "1"))
      contributor.contribute(state, agent).map { sources =>
        assertTrue(
          sources.sections.length == 1,
          sources.sections.head.payload.contains("selected@1"),
          sources.sections.head.payload.contains("unselected@1"),
          !sources.sections.head.payload.contains("selected body"),
          sources.retrieval.map(_.content) == Chunk("selected body"),
          sources.safetyInstructions.isEmpty,
          agent.allowedTools.isEmpty,
          selected.grantedTools.isEmpty
        )
      }
    },
    test("SkillHostPolicy 拒绝不在 allowlist 或 trust 策略外的激活") {
      val skill = SkillDescriptor.make(
        "draft",
        "1",
        "file://skills/draft.md",
        SkillTrust.Untrusted,
        "untrusted body"
      )
      val provider = SkillProvider.static("team-skills", Chunk(skill))
      val denied   = SkillContextContributor(
        Chunk(provider),
        Chunk("draft" -> "1"),
        SkillHostPolicy(acceptedTrust = Set(SkillTrust.Reviewed, SkillTrust.Trusted))
      )
      val allowed = SkillContextContributor(
        Chunk(provider),
        Chunk("draft" -> "1"),
        SkillHostPolicy(allowedSourceIds = Set("draft@1"))
      )
      for
        rejected <- denied.contribute(state, agent).exit
        sources  <- allowed.contribute(state, agent)
      yield assertTrue(
        rejected.isFailure,
        sources.retrieval.map(_.content) == Chunk("untrusted body")
      )
    },
    test("HarnessSkillProvider 按无正文目录加载并核对指纹") {
      val skill = SkillDescriptor.make(
        "harness",
        "2",
        "db://skills/harness/2",
        SkillTrust.Reviewed,
        "durable skill body"
      )
      (for
        store  <- ZIO.service[HarnessStore]
        _      <- store.saveSkill(skill)
        loaded <- HarnessSkillProvider(store, Chunk(SkillCatalogEntry.of(skill))).load("harness", "2")
      yield assertTrue(loaded == skill)).provideLayer(HarnessStore.inMemory)
    },
    test("ToolProvider 合并目录时拒绝重名") {
      val left  = ToolProvider.static("left", List(writeTool))
      val right = ToolProvider.static("right", List(writeTool))
      ToolProvider.registry(Chunk(left, right)).exit.map { exit =>
        val message = exit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
        assertTrue(exit.isFailure, message.contains("write"))
      }
    }
  )

package com.zyblw.agent.extension

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.{HarnessStore, SkillDescriptor, SkillMaterializer, SkillTrust}
import zio.*

/** 宿主对 Skill 激活的显式策略。目录指纹仍必须匹配；本策略只决定哪些目录项可以被加载为 Retrieval。 */
final case class SkillHostPolicy(
    allowedSourceIds: Set[String] = Set.empty,
    acceptedTrust: Set[SkillTrust] = Set(SkillTrust.Untrusted, SkillTrust.Reviewed, SkillTrust.Trusted)
):
  require(acceptedTrust.nonEmpty, "SkillHostPolicy.acceptedTrust 不能为空")

  def allows(entry: SkillCatalogEntry): Boolean =
    (allowedSourceIds.isEmpty || allowedSourceIds.contains(entry.sourceId)) && acceptedTrust.contains(
      entry.trust
    )

/** 把 typed SkillProvider 目录和宿主显式选择的 Skill 投影到 Context。
  *
  * 目录只进入 Metadata world-state section；正文只经 SkillMaterializer 进入 Retrieval。该贡献者不依赖 ToolRegistry， 也不能修改
  * AgentDefinition.allowedTools。
  */
final class SkillContextContributor(
    providers: Chunk[SkillProvider],
    activeSkills: Chunk[(String, String)],
    policy: SkillHostPolicy = SkillHostPolicy()
) extends ContextContributor:
  val id               = "skill-provider"
  override val version = "1"

  def contribute(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources] =
    val _ = (state, definition)
    (for
      catalogs <- ZIO.foreach(providers)(provider => provider.catalog.map(entries => provider -> entries))
      indexed  <- indexCatalogs(catalogs)
      loaded   <- ZIO.foreach(Chunk.fromIterable(activeSkills.toList.distinct)) { key =>
        for
          selected <- ZIO
            .fromOption(indexed.get(key))
            .orElseFail(AgentError.InvalidConfiguration(s"宿主选择的 Skill ${key._1}@${key._2} 不在目录中"))
          (provider, entry) = selected
          _ <- ZIO
            .fail(AgentError.InvalidConfiguration(s"Skill ${entry.sourceId} 未被宿主 allowlist 或 trust 策略允许"))
            .unless(policy.allows(entry))
          skill <- provider.load(key._1, key._2)
          _     <- ZIO
            .fail(AgentError.InvalidConfiguration(s"Skill ${entry.sourceId} 正文与目录身份或指纹不一致"))
            .unless(SkillCatalogEntry.of(skill) == entry)
        yield skill
      }
      entries = Chunk.fromIterable(indexed.values.map(_._2).toList.sortBy(_.sourceId))
    yield loaded.foldLeft(ContextSources(sections = Chunk(SkillCatalogSection.snapshot(entries)))) {
      (sources, skill) => merge(sources, SkillMaterializer.toSources(skill))
    }).mapError(error => AgentError.ContextBuildFailed(s"Skill 上下文解析失败: ${error.message}"))

  private def indexCatalogs(
      catalogs: Chunk[(SkillProvider, Chunk[SkillCatalogEntry])]
  ): IO[AgentError.InvalidConfiguration, Map[(String, String), (SkillProvider, SkillCatalogEntry)]] =
    ZIO.foldLeft(catalogs)(Map.empty[(String, String), (SkillProvider, SkillCatalogEntry)]) {
      case (indexed, (provider, entries)) =>
        ZIO.foldLeft(entries)(indexed) { (current, entry) =>
          val key = entry.id -> entry.version
          current.get(key) match
            case Some(_) =>
              ZIO.fail(AgentError.InvalidConfiguration(s"Skill 目录重复定义 ${entry.sourceId}"))
            case None => ZIO.succeed(current.updated(key, provider -> entry))
        }
    }

  private def merge(left: ContextSources, right: ContextSources): ContextSources =
    ContextSources(
      memories = left.memories ++ right.memories,
      retrieval = left.retrieval ++ right.retrieval,
      safetyInstructions = left.safetyInstructions ++ right.safetyInstructions,
      existingSummary = right.existingSummary.orElse(left.existingSummary),
      sections = left.sections ++ right.sections
    )

object SkillContextContributor:
  def apply(
      providers: Chunk[SkillProvider],
      activeSkills: Chunk[(String, String)] = Chunk.empty,
      policy: SkillHostPolicy = SkillHostPolicy()
  ): SkillContextContributor =
    new SkillContextContributor(providers, activeSkills, policy)

/** 使用宿主持有的无正文目录，把 HarnessStore 适配成按需 SkillProvider。
  *
  * 目录必须来自受控配置或独立 catalog read model；load 后会再次核对完整目录身份，避免 Store 正文与部署目录漂移。
  */
final class HarnessSkillProvider(
    store: HarnessStore,
    entries: Chunk[SkillCatalogEntry],
    id: String = "harness-skills",
    version: String = "1"
) extends SkillProvider:
  val descriptor           = ExtensionDescriptor(ExtensionKind.Skills, id, version)
  private val catalogByKey = entries.map(entry => (entry.id, entry.version) -> entry).toMap
  require(catalogByKey.size == entries.length, "Harness Skill 目录不能重复 id@version")

  def catalog: UIO[Chunk[SkillCatalogEntry]] =
    ZIO.succeed(Chunk.fromIterable(catalogByKey.values.toList.sortBy(_.sourceId)))

  def load(id: String, version: String): IO[AgentError, SkillDescriptor] =
    for
      expected <- ZIO
        .fromOption(catalogByKey.get(id -> version))
        .orElseFail(AgentError.InvalidConfiguration(s"Skill $id@$version 不在 Harness 目录中"))
      loaded <- store
        .getSkill(id, version)
        .someOrFail(AgentError.InvalidConfiguration(s"HarnessStore 缺少 Skill $id@$version"))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration(s"HarnessStore Skill ${expected.sourceId} 与目录指纹不一致"))
        .unless(SkillCatalogEntry.of(loaded) == expected)
    yield loaded

object HarnessSkillProvider:
  def apply(
      store: HarnessStore,
      entries: Chunk[SkillCatalogEntry],
      id: String = "harness-skills",
      version: String = "1"
  ): HarnessSkillProvider =
    new HarnessSkillProvider(store, entries, id, version)

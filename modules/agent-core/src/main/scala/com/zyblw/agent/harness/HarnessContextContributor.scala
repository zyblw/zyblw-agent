package com.zyblw.agent.harness

import com.zyblw.agent.artifacts.ArtifactReference
import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import zio.*

/** 把 Goal / Plan / Skill / Interaction 注入模型上下文。不修改 Kernel，不授予工具，不把 Skill 或 Steer 写成 System。 */
final class HarnessContextContributor(
    store: HarnessStore,
    goalId: GoalId,
    planId: Option[PlanId] = None,
    skills: Chunk[(String, String)] = Chunk.empty
) extends ContextContributor:
  val id               = "harness"
  override val version = "2"

  def contribute(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources] =
    val _ = (state, definition)
    (for
      goal         <- store.getGoal(goalId)
      plan         <- ZIO.foreach(planId)(store.getPlan)
      loadedSkills <- ZIO.foreach(skills) { case (skillId, version) =>
        store.getSkill(skillId, version)
      }
      loadedInteractions <- store.listInteractions(goalId, beforeSequence = None, limit = 16)
    yield
      val goalSources = goal.toList.map { current =>
        ContextSources(
          memories = Chunk(ContextMemory("goal", current.objective, 1.0)),
          retrieval = artifactDocuments(s"goal:${current.id.asString}", current.artifacts)
        )
      }
      val planSources = plan.flatten.toList.map { current =>
        val todos = current.todos.map { todo =>
          ContextDocument(
            id = s"todo:${todo.id.asString}",
            content = s"${todo.status}: ${todo.title}",
            source = s"plan://${current.id.asString}"
          )
        }
        val planArtifacts = artifactDocuments(s"plan:${current.id.asString}", current.artifacts)
        val todoArtifacts =
          current.todos.flatMap(todo => artifactDocuments(s"todo:${todo.id.asString}", todo.artifacts))
        ContextSources(retrieval = todos ++ planArtifacts ++ todoArtifacts)
      }
      val skillSources       = loadedSkills.flatten.map(SkillMaterializer.toSources)
      val interactionSources =
        if loadedInteractions.isEmpty then Nil
        else
          List(
            ContextSources(retrieval = loadedInteractions.map { item =>
              ContextDocument(
                id = s"interaction:${item.kind}:${item.id.asString}",
                content = s"${item.kind}: ${item.body}",
                source = s"interaction://${item.kind.toString.toLowerCase}/${item.id.asString}"
              )
            })
          )
      (goalSources ++ planSources ++ skillSources ++ interactionSources).foldLeft(ContextSources()) {
        (left, right) =>
          ContextSources(
            memories = left.memories ++ right.memories,
            retrieval = left.retrieval ++ right.retrieval,
            safetyInstructions = left.safetyInstructions ++ right.safetyInstructions,
            existingSummary = right.existingSummary.orElse(left.existingSummary),
            sections = left.sections ++ right.sections
          )
      }
    ).mapError(error => AgentError.ContextBuildFailed(s"Harness 上下文解析失败: ${error.message}"))

  /** 只把低敏引用元数据送入 Context；没有 ArtifactStore 依赖，因此不可能在此加载正文或二进制。 */
  private def artifactDocuments(owner: String, references: Chunk[ArtifactReference]): Chunk[ContextDocument] =
    references.zipWithIndex.map { case (reference, index) =>
      ContextDocument(
        id = s"artifact:$owner:$index:${reference.sha256.take(16)}",
        content = s"Artifact reference only: name=${reference.name.value};version=${reference.version};" +
          s"mediaType=${reference.mediaType};bytes=${reference.byteSize};sha256=${reference.sha256}",
        source = s"artifact://${reference.name.value}#v${reference.version}"
      )
    }

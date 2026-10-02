package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 树导航器接口：决定在当前层级的候选子节点中，哪些分支值得深入探索。 */
trait TreeNavigator:
  def selectBranches(
      query: String,
      currentPath: Chunk[String],
      frontier: Chunk[StructureNode],
      maxSelect: Int
  ): IO[RetrievalError, Chunk[String]]

object TreeNavigator:
  /** 基于标题和节点摘要关键词匹配的确定性导航器（作为离线兜底或轻量导航）。 */
  val deterministic: TreeNavigator = new TreeNavigator:
    def selectBranches(
        query: String,
        currentPath: Chunk[String],
        frontier: Chunk[StructureNode],
        maxSelect: Int
    ): UIO[Chunk[String]] =
      val _           = currentPath
      val queryTokens = SimpleChineseLexicalProcessor.query(query).split("\\s+").filter(_.nonEmpty).toSet
      if queryTokens.isEmpty then ZIO.succeed(frontier.take(maxSelect).map(_.section.id))
      else
        val scored = frontier.map { node =>
          val text =
            SimpleChineseLexicalProcessor.document(node.section.title + " " + node.summary.getOrElse(""))
          val score = queryTokens.count(text.contains)
          node.section.id -> score
        }
        val selected = scored
          .sortBy(-_._2)
          .take(maxSelect)
          .map(_._1)
        ZIO.succeed(selected)

/** 有界树推理搜索器输出。 */
final case class TreeSearchResult(
    selectedNodeIds: Chunk[String],
    visitedNodeCount: Int,
    depthReached: Int,
    degraded: Boolean = false,
    budgetLimited: Boolean = false
)

/** 有界树推理搜索器（Bounded Reasoning Tree Search）。
  *
  * 模仿人类阅读目录寻找目标章节的过程：自顶向下由导航器进行 Beam Search 剪枝， 强制所有选择必须在当前候选前沿内，严格遵守深度、访问节点数、调用次数和超时预算。
  */
object BoundedTreeSearch:
  def search(
      query: String,
      snapshot: StructureSnapshot,
      navigator: TreeNavigator = TreeNavigator.deterministic,
      budget: TreeSearchBudget = TreeSearchBudget()
  ): IO[RetrievalError, TreeSearchResult] =
    final case class State(
        ids: Chunk[String],
        visited: Int,
        depth: Int,
        calls: Int,
        degraded: Boolean,
        limited: Boolean
    )
    def choose(
        frontier: Chunk[StructureNode],
        path: Chunk[String]
    ): IO[RetrievalError, (Chunk[String], Boolean)] =
      navigator.selectBranches(query, path, frontier, budget.beamWidth).either.flatMap {
        case Right(ids) =>
          val allowed = frontier.map(_.section.id).toSet
          val valid   = ids.filter(allowed.contains).distinct.take(budget.beamWidth)
          if valid.nonEmpty then
            ZIO.succeed(valid -> (ids.length > budget.beamWidth || ids.exists(id => !allowed.contains(id))))
          else
            TreeNavigator.deterministic.selectBranches(query, path, frontier, budget.beamWidth).map(_ -> true)
        case Left(_) =>
          TreeNavigator.deterministic.selectBranches(query, path, frontier, budget.beamWidth).map(_ -> true)
      }
    def loop(state: State): IO[RetrievalError, State] =
      val pending = state.ids.flatMap(snapshot.children).distinctBy(_.section.id)
      if pending.isEmpty then ZIO.succeed(state)
      else if state.depth >= budget.maxDepth || state.calls >= budget.maxModelCalls || state.visited >= budget.maxVisitedNodes
      then ZIO.succeed(state.copy(limited = true))
      else
        val frontier = pending.take(budget.maxVisitedNodes - state.visited)
        choose(
          frontier,
          Chunk.fromIterable(
            frontier
              .flatMap(_.section.parentId)
              .distinct
              .map(id => id + ": " + snapshot.breadcrumbs(id).mkString(" > "))
          )
        ).flatMap { case (ids, degraded) =>
          // Keep reached leaves in the beam rather than losing them when another branch continues.
          val leaves = state.ids.filter(id => snapshot.children(id).isEmpty)
          loop(
            State(
              (leaves ++ ids).distinct.take(budget.beamWidth),
              state.visited + frontier.length,
              state.depth + 1,
              state.calls + 1,
              state.degraded || degraded,
              state.limited || frontier.length < pending.length
            )
          )
        }
    val action = for
      _ <- ZIO.fromEither(snapshot.validate).mapError(AgentError.RetrievalFailed(_))
      roots = snapshot.rootNodes.take(budget.maxVisitedNodes)
      chosen <- choose(roots, Chunk.empty)
      state <- loop(State(chosen._1, roots.length, 0, 1, chosen._2, roots.length < snapshot.rootNodes.length))
    yield TreeSearchResult(state.ids, state.visited, state.depth, state.degraded, state.limited)
    action
      .timeoutFail(AgentError.RetrievalFailed("Tree search timeout"))(budget.timeout)
      .catchAll(_ =>
        ZIO.succeed(
          TreeSearchResult(snapshot.rootNodes.take(budget.beamWidth).map(_.section.id), 0, 0, degraded = true)
        )
      )

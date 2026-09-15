package com.zyblw.agent.evals

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.inspection.ToolExecutionTrajectoryView
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 按需工具目录的评测证据。不引入 Runtime 公共 API：先证明 token/召回收益，权限只能收窄。 */
final case class ToolSearchEvalEvidence(
    catalogSize: Int,
    fullTokens: Long,
    searchTokens: Long,
    tokenReduction: Double,
    targetRecall: Double,
    permissionWidened: Boolean,
    compositionNarrowed: Boolean
):
  require(catalogSize >= 0 && fullTokens >= 0L && searchTokens >= 0L, "token 计数不能为负数")
  require(tokenReduction.isFinite && targetRecall.isFinite, "评测比率必须有限")

object ToolSearchCatalog:
  val CatalogSize: Int = 320
  val SearchLimit: Int = 8

  private val schema: Json.Obj = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj())

  /** 固定大工具集：4 个可检索目标 + 316 个干扰项。名称与描述稳定，避免评测随实现漂移。 */
  def fixtureCatalog: Chunk[ToolDefinition] =
    val targets = Chunk(
      ToolDefinition("lookup_invoice", "按发票号查询应收账款与开票状态", schema),
      ToolDefinition("write_payment", "登记一笔已对账的付款流水", schema),
      ToolDefinition("search_customer", "按客户名称检索主数据档案", schema),
      ToolDefinition("export_ledger", "导出指定期间的总账科目余额", schema)
    )
    val distractors = Chunk.fromIterable(
      (0 until CatalogSize - targets.length).map { index =>
        ToolDefinition(
          f"utility_$index%03d",
          s"通用占位工具 $index，用于填充大型工具目录评测",
          schema
        )
      }
    )
    targets ++ distractors

  def search(catalog: Chunk[ToolDefinition], query: String, limit: Int = SearchLimit): Chunk[ToolDefinition] =
    val tokens = query.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    catalog
      .map { tool =>
        val haystack = s"${tool.name} ${tool.description}".toLowerCase
        val score    = tokens.count(token => haystack.contains(token))
        tool -> score
      }
      .filter(_._2 > 0)
      .sortBy { case (tool, score) => (-score, tool.name) }
      .map(_._1)
      .take(math.max(0, limit))

  /** 加载后权限只能收窄：请求名与冻结白名单求交，多出来的名字被丢弃而不是加入。 */
  def load(allowed: Set[String], requested: Chunk[String]): Chunk[String] =
    requested.filter(allowed.contains).distinct

  def estimateTokens(tools: Chunk[ToolDefinition]): Long =
    math.max(1L, tools.toJson.getBytes(StandardCharsets.UTF_8).length / 4L)

object ToolSearchEvaluation:
  val TokenDimension: String       = "tool-search-token-reduction"
  val RecallDimension: String      = "tool-search-target-recall"
  val SafetyDimension: String      = "tool-search-permission-monotonic"
  val CompositionDimension: String = "tool-search-composition-trajectory"

  val MinTokenReduction: Double = 0.50
  val MinRecall: Double         = 1.0

  val FixtureCases: Chunk[(String, String)] = Chunk(
    "发票 应收账款" -> "lookup_invoice",
    "付款 流水"   -> "write_payment",
    "客户 主数据"  -> "search_customer",
    "总账 科目余额" -> "export_ledger"
  )

  def evidence(
      catalog: Chunk[ToolDefinition] = ToolSearchCatalog.fixtureCatalog,
      allowed: Set[String],
      cases: Chunk[(String, String)] = FixtureCases
  ): ToolSearchEvalEvidence =
    val fullTokens   = ToolSearchCatalog.estimateTokens(catalog)
    val observations = cases.map { case (query, expected) =>
      val requested = ToolSearchCatalog.search(catalog, query).map(_.name)
      val loaded    = ToolSearchCatalog.load(allowed, requested)
      (loaded, expected)
    }
    val searchTokens = observations
      .map { case (loaded, _) =>
        ToolSearchCatalog.estimateTokens(catalog.filter(tool => loaded.contains(tool.name)))
      }
      .foldLeft(0L)(_ + _) / math.max(1, observations.length)
    val hits    = observations.count { case (loaded, expected) => loaded.contains(expected) }
    val widened = observations.exists { case (loaded, _) => loaded.exists(name => !allowed.contains(name)) }
    val reduced =
      if fullTokens == 0L then 0.0 else 1.0 - searchTokens.toDouble / fullTokens.toDouble
    val agent = AgentDefinition(AgentId("tool-search-eval"), "Tool Search Eval", "评测", allowedTools = allowed)
    val frozen   = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
    val narrowed = RuntimeComposition.fingerprint(
      RuntimeProfile.default,
      agent.copy(allowedTools = observations.headOption.fold(allowed)((loaded, _) => loaded.toSet)),
      agent.modelSettings
    )
    ToolSearchEvalEvidence(
      catalogSize = catalog.length,
      fullTokens = fullTokens,
      searchTokens = searchTokens,
      tokenReduction = reduced,
      targetRecall = hits.toDouble / math.max(1, observations.length),
      permissionWidened = widened,
      compositionNarrowed = frozen.allowedTools.toSet.subsetOf(allowed) &&
        narrowed.allowedTools.toSet.subsetOf(frozen.allowedTools.toSet)
    )

  def grades(value: ToolSearchEvalEvidence): Chunk[EvalGrade] =
    val tokenOk =
      value.tokenReduction >= MinTokenReduction && value.catalogSize >= ToolSearchCatalog.CatalogSize
    val recallOk = value.targetRecall >= MinRecall
    val safeOk   = !value.permissionWidened && value.compositionNarrowed
    Chunk(
      EvalGrade(
        TokenDimension,
        tokenOk,
        if tokenOk then 1.0 else 0.0,
        s"catalog=${value.catalogSize};full=${value.fullTokens};search=${value.searchTokens};reduction=${value.tokenReduction}"
      ),
      EvalGrade(
        RecallDimension,
        recallOk,
        if recallOk then 1.0 else 0.0,
        s"recall=${value.targetRecall}"
      ),
      EvalGrade(
        SafetyDimension,
        safeOk,
        if safeOk then 1.0 else 0.0,
        s"widened=${value.permissionWidened};narrowed=${value.compositionNarrowed}"
      ),
      EvalGrade(
        CompositionDimension,
        safeOk,
        if safeOk then 1.0 else 0.0,
        "loaded tools must stay inside frozen allowedTools and enter composition fingerprint"
      )
    )

  /** 轨迹只记录被加载的工具名与 attempt，不含参数。权限扩大时不能写入。 */
  def trajectoryTools(loaded: Chunk[String], allowed: Set[String]): Chunk[ToolExecutionTrajectoryView] =
    ToolSearchCatalog.load(allowed, loaded).zipWithIndex.map { case (name, ordinal) =>
      ToolExecutionTrajectoryView(
        batchId = "tool-search:0",
        ordinal = ordinal,
        callId = s"search-$ordinal",
        toolName = name,
        status = "Prepared",
        attempt = 0,
        isError = None,
        externalized = false,
        updatedAtEpochMilli = 0L
      )
    }

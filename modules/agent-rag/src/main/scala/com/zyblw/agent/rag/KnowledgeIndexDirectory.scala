package com.zyblw.agent.rag

import com.zyblw.agent.admin.CursorTime
import com.zyblw.agent.core.*
import zio.*

/** 知识索引清单目录的排序游标。
  *
  * 目录按 `(updatedAt DESC, documentId DESC, indexVersion DESC)` 稳定排序，因此翻页必须使用 keyset 游标而不是 OFFSET：摄入与退役会持续改写
  * `updatedAt`，OFFSET 会让同一份清单重复出现或被整页跳过。
  *
  * `indexVersion` 必须进入游标。发布新版本时旧版本被置为 `Superseded`、新版本被置为 `Ready`，两者的 `updatedAt` 来自同一个事务时间点，只用
  * `(updatedAt, documentId)` 无法区分它们，翻页会在同一份文档的两个版本之间丢行。
  *
  * 游标只包含时间、文档 ID 与版本号，不含租户、权限或正文，可以安全地出现在 URL 中。
  *
  * 时间戳使用微秒而不是毫秒，理由见 [[com.zyblw.agent.admin.CursorTime]]：游标精度低于排序列精度会让翻页静默丢行， 而“旧版本转 Superseded、新版本转
  * Ready”恰好会在同一微秒写入两行，是最容易触发该缺陷的路径。
  *
  * @param updatedAtEpochMicro
  *   上一页最后一条清单的更新时间，精度与排序列一致
  * @param documentId
  *   上一页最后一条清单的业务文档 ID
  * @param indexVersion
  *   上一页最后一条清单的索引版本
  */
final case class KnowledgeIndexCursor(updatedAtEpochMicro: Long, documentId: String, indexVersion: Long):
  /** 编码为可放入查询参数的不透明文本。 */
  def encoded: String = s"$updatedAtEpochMicro:$documentId:$indexVersion"

object KnowledgeIndexCursor:
  /** 解析客户端回传的游标；非法值返回安全校验消息而不是抛异常。
    *
    * documentId 允许包含 `:`，因此按第一个和最后一个分隔符切分，而不是简单地按分隔符拆成三段。
    */
  def decode(value: String): Either[String, KnowledgeIndexCursor] =
    val invalid    = Left(s"非法知识索引游标: $value")
    val firstColon = value.indexOf(':')
    val lastColon  = value.lastIndexOf(':')
    if firstColon <= 0 || lastColon <= firstColon then invalid
    else
      val documentId = value.substring(firstColon + 1, lastColon).trim
      val micros     = value.substring(0, firstColon).toLongOption
      val version    = value.substring(lastColon + 1).toLongOption
      (micros, version) match
        case (Some(updatedAt), Some(indexVersion)) if documentId.nonEmpty && indexVersion > 0L =>
          Right(KnowledgeIndexCursor(updatedAt, documentId, indexVersion))
        case _ => invalid

/** 一页知识索引清单。 */
final case class KnowledgeIndexPage(
    items: Chunk[KnowledgeIndexManifest],
    nextCursor: Option[KnowledgeIndexCursor],
    hasMore: Boolean
)

/** 知识索引清单的只读目录 SPI。
  *
  * 它与 `KnowledgeIndexStore` 分开，而不是给后者增加一个 `list` 抽象方法：`KnowledgeIndexStore` 是已发布 trait，
  * 增加抽象方法会让所有外部实现无法编译。职责也不同——`KnowledgeIndexStore` 是摄入路径的权威读写协议，只按 `(tenant, document)`
  * 或幂等键定位；目录是管理面的扫描投影，需要过滤、排序和分页。把扫描能力塞进发布协议 还会诱使摄入代码用扫描代替按键读取。
  *
  * 实现必须保证：结果按 `(updatedAt DESC, documentId DESC, indexVersion DESC)` 稳定排序，`limit` 被收敛到
  * `KnowledgeIndexDirectory.MaxLimit`，且只返回 manifest——正文与向量不属于管理列表。
  */
trait KnowledgeIndexDirectory:
  /** 按可选租户分页列出索引清单。
    *
    * @param tenantId
    *   限定租户；None 表示跨租户查看，调用方必须已经持有管理 scope
    * @param limit
    *   单页条数，实现必须收敛到 `KnowledgeIndexDirectory.MaxLimit`
    * @param cursor
    *   上一页返回的 keyset 游标
    */
  def list(
      tenantId: Option[TenantId],
      limit: Int,
      cursor: Option[KnowledgeIndexCursor]
  ): IO[RetrievalError, KnowledgeIndexPage]

object KnowledgeIndexDirectory:
  /** 未指定时的单页条数。 */
  val DefaultLimit: Int = 50

  /** 单页硬上限；管理台翻页优于一次拉取整个知识库的清单。 */
  val MaxLimit: Int = 200

  /** 收敛到合法范围的单页条数。 */
  def boundedLimit(limit: Int): Int = limit.max(1).min(MaxLimit)

  /** 尚未接入可枚举清单来源时的显式空实现。
    *
    * 它明确返回空页而不是报错，使只装配了自定义 `KnowledgeIndexStore` 的部署仍然可以挂载管理路由；代价是管理台的 文档列表会**永远为空**。需要真实清单的部署必须装配
    * [[inMemoryKnowledge]] 或 PostgreSQL Adapter——沉默的空 列表比一个 500 更容易被误读成"知识库里没有文档"，因此这一点由 scaladoc
    * 与管理台能力声明共同交代，而不是靠 运行时错误提示。
    */
  val empty: ULayer[KnowledgeIndexDirectory] = ZLayer.succeed(
    new KnowledgeIndexDirectory:
      def list(
          tenantId: Option[TenantId],
          limit: Int,
          cursor: Option[KnowledgeIndexCursor]
      ): IO[RetrievalError, KnowledgeIndexPage] =
        ZIO.succeed(KnowledgeIndexPage(Chunk.empty, None, hasMore = false))
  )

  /** 从任意清单快照来源构造目录，过滤、排序与分页在内存中完成。
    *
    * 它适合测试、单进程开发和清单数量有限的嵌入式部署。清单规模超出单机内存时必须改用把过滤和 keyset 条件下推到 数据库的 Adapter，否则每次翻页都会加载全部清单。
    *
    * @param snapshots
    *   返回当前全部索引清单的读取效果
    */
  def fromSnapshots(snapshots: IO[RetrievalError, Chunk[KnowledgeIndexManifest]]): KnowledgeIndexDirectory =
    new KnowledgeIndexDirectory:
      def list(
          tenantId: Option[TenantId],
          limit: Int,
          cursor: Option[KnowledgeIndexCursor]
      ): IO[RetrievalError, KnowledgeIndexPage] =
        snapshots.map { manifests =>
          val ordered = manifests
            .filter(manifest => tenantId.forall(_ == manifest.build.key.tenantId))
            .sorted(using descending)
          val remaining = cursor.fold(ordered)(value => ordered.filter(after(value)))
          val window    = remaining.take(boundedLimit(limit))
          val hasMore   = remaining.length > window.length
          KnowledgeIndexPage(window, window.lastOption.filter(_ => hasMore).map(cursorOf), hasMore)
        }

  /** 在内存索引实现之上构造目录；两者共享同一份 manifest 状态。 */
  def inMemory(store: InMemoryKnowledgeIndexStore): KnowledgeIndexDirectory = fromSnapshots(store.manifests)

  /** 本地开发与测试的同源组合层。
    *
    * 同一个实例同时承担版本化发布、检索快照和管理目录，因此 `KnowledgeIndexer` 激活的新版本会立刻同时出现在 `Retriever` 的候选集和管理台的文档列表里。生产部署应改用
    * PostgreSQL Adapter 获得相同的服务形状。
    */
  val inMemoryKnowledge: ULayer[KnowledgeIndexStore & VectorStore & KnowledgeIndexDirectory] =
    ZLayer.fromZIOEnvironment(
      InMemoryKnowledgeIndexStore.make.map(store =>
        ZEnvironment[KnowledgeIndexStore](store)
          .add[VectorStore](store)
          .add[KnowledgeIndexDirectory](inMemory(store))
      )
    )

  /** 排序键；与 SQL `ORDER BY updated_at DESC, document_id DESC, index_version DESC` 等价。 */
  private def sortKey(manifest: KnowledgeIndexManifest): (Long, String, Long) =
    (CursorTime.epochMicro(manifest.updatedAt), manifest.build.key.documentId, manifest.build.version)

  private val descending: Ordering[KnowledgeIndexManifest] =
    Ordering.by[KnowledgeIndexManifest, (Long, String, Long)](sortKey).reverse

  private def cursorOf(manifest: KnowledgeIndexManifest): KnowledgeIndexCursor =
    KnowledgeIndexCursor(
      CursorTime.epochMicro(manifest.updatedAt),
      manifest.build.key.documentId,
      manifest.build.version
    )

  /** keyset 游标判定：在降序序列中严格位于游标之后。 */
  private def after(cursor: KnowledgeIndexCursor)(manifest: KnowledgeIndexManifest): Boolean =
    Ordering[(Long, String, Long)].lt(
      sortKey(manifest),
      (cursor.updatedAtEpochMicro, cursor.documentId, cursor.indexVersion)
    )

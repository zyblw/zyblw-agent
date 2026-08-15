/**
 * `/api/v1/admin/**` 的 TypeScript 线格式契约。
 *
 * 每个类型都与 `com.zyblw.agent.admin` 里对应的 Scala case class 一一对应，字段名和可空性必须完全一致。
 * 这里刻意不做任何"前端更好用"的改名或结构调整：一旦线格式与视图模型分叉，后端改字段时 TypeScript 编译器
 * 就再也发现不了问题，而 400/500 只会在运行时以空白面板的形式出现。需要展示层结构时在组件里派生，不要改这里。
 *
 * 时间统一是 epoch 毫秒数字，与 Scala 侧一致。金额是字符串（BigDecimal），因为 IEEE 754 双精度会在跨语言
 * 客户端产生尾数误差。
 */

/** 后端统一错误体；`category` 是稳定分类，`message` 只在 `safeToExpose` 时包含领域信息。 */
export interface ErrorResponse {
  category: string;
  message: string;
}

/** 外部可观测系统的深链配置，由后端下发而不是前端硬编码。 */
export interface ObservabilityLinks {
  langfuseBaseUrl?: string | null;
  langfuseProjectId?: string | null;
  grafanaBaseUrl?: string | null;
  grafanaDashboardUid?: string | null;
  otlpTracesEndpoint?: string | null;
  /** traceId 推导规则；`run-id-hex` 表示 traceId 是 RunId 去掉连字符后的 32 位十六进制。 */
  traceIdDerivation: string;
}

/**
 * 后端实际装配了哪些管理能力。
 *
 * 前端据此决定显示哪些页签。没有它就只能靠对每个端点试探性请求看是否 404 来推断能力，那既慢又会在
 * 服务端日志里制造一批无意义的错误。
 */
export interface AdminCapabilitiesView {
  apiVersion: number;
  runDirectory: boolean;
  runEventStream: boolean;
  runtimeConfig: boolean;
  queueOps: boolean;
  knowledge: boolean;
  evalTrends: boolean;
  models: boolean;
  observability: ObservabilityLinks;
}

/** Run 目录列表项的低敏用量摘要。 */
export interface RunDirectoryUsage {
  modelCalls: number;
  toolCalls: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cachedInputTokens: number;
  reasoningOutputTokens: number;
  /** BigDecimal 字符串，不要 parseFloat 后再显示。 */
  estimatedCost: string;
}

/** Run 目录列表项；只含元数据，不含用户输入、模型输出或工具参数。 */
export interface RunSummaryView {
  runId: string;
  agentId: string;
  sessionId: string;
  threadId?: string | null;
  status: string;
  steps: number;
  awaitingApproval: boolean;
  pendingApprovalToolName?: string | null;
  pendingApprovalRisk?: string | null;
  tenantId?: string | null;
  userId?: string | null;
  usage: RunDirectoryUsage;
  createdAtEpochMilli: number;
  updatedAtEpochMilli: number;
  stateVersion: number;
  lastEventSequence: number;
}

/** 一页 Run 目录结果；`nextCursor` 是不透明 keyset 游标。 */
export interface RunDirectoryPage {
  items: RunSummaryView[];
  nextCursor?: string | null;
  hasMore: boolean;
}

/** 按状态聚合的部署总览。 */
export interface RunDirectoryOverview {
  capturedAtEpochMilli: number;
  totalRuns: number;
  countsByStatus: Record<string, number>;
  awaitingApproval: number;
}

/** 管理事件中的审批元数据；跨租户视图故意不含审批原因。 */
export interface AdminApprovalEventView {
  approvalId: string;
  toolName: string;
  risk: string;
  requestedAtEpochMilli: number;
}

/** 管理事件中的工具进度；不含 arguments 或 ToolResult。 */
export interface AdminToolProgressView {
  callId?: string | null;
  toolName?: string | null;
  batchIndex?: number | null;
}

/** Context 预算计数；不含 Prompt、Memory 或 RAG 正文。 */
export interface AdminContextUsageView {
  estimatedTokens: number;
  droppedMessages: number;
  truncatedToolResults: number;
  droppedMemories: number;
  droppedRetrieval: number;
  rotSignalCodes: string[];
}

/** `/admin/runs/{runId}/events/stream` 的低敏 SSE data。 */
export interface AdminRunEventView {
  eventId: string;
  runId: string;
  sequence: number;
  eventType: string;
  atEpochMilli: number;
  status?: string | null;
  step?: number | null;
  category?: string | null;
  stage?: string | null;
  stateVersion?: number | null;
  usage?: RunDirectoryUsage | null;
  approval?: AdminApprovalEventView | null;
  tool?: AdminToolProgressView | null;
  context?: AdminContextUsageView | null;
}

/** Run 目录查询条件；与后端查询参数一一对应。 */
export interface RunDirectoryQuery {
  tenantId?: string;
  agentId?: string;
  statuses?: string[];
  awaitingApproval?: boolean;
  updatedAfter?: number;
  updatedBefore?: number;
  cursor?: string;
  limit?: number;
}

/**
 * 一项配置在被覆盖后何时真正生效。
 *
 * 管理台必须如实展示这个边界，否则一个保存成功的操作会让运维误以为限制已经收紧。
 */
export type RuntimeSettingApplies = 'Immediate' | 'NextRun' | 'Restart';

/**
 * 可在运行时安全覆盖的有界配置集合。
 *
 * 全部字段可选：缺失表示"沿用部署基线"，而不是"设为默认值"。因此删除一项覆盖与从未设置过它完全等价，
 * 提交时必须真正省略该键，不能传 `null` 或 `0`。
 */
export interface RuntimeOverrides {
  toolAllowedTools?: string[];
  toolDeniedTools?: string[];
  toolDefaultTimeoutMillis?: number;
  toolMaxResultBytes?: number;
  toolApprovalPolicy?: 'never' | 'risk-based' | 'always';
  toolMaxCallsPerRun?: number;
  toolMaxCallsPerStep?: number;
  retrievalTopK?: number;
  retrievalMinimumScore?: number;
  rerankEnabled?: boolean;
  modelProvider?: string;
  modelName?: string;
  modelTemperature?: number;
  modelMaxOutputTokens?: number;
}

/** 覆盖模型工作点的四个键；模型页提交时需要整体替换而不是逐个合并。 */
export const MODEL_OVERRIDE_KEYS = [
  'modelProvider',
  'modelName',
  'modelTemperature',
  'modelMaxOutputTokens',
] as const satisfies readonly (keyof RuntimeOverrides)[];

/** 单个配置项：同时给出基线、覆盖与生效值，让运维看出"这个值为什么是现在这样"。 */
export interface RuntimeSettingField {
  key: string;
  baselineValue: string;
  overrideValue?: string | null;
  effectiveValue: string;
  applies: RuntimeSettingApplies;
  sensitive: boolean;
}

/** 配置页的完整只读快照。 */
export interface RuntimeConfigView {
  overrideVersion: number;
  overrideUpdatedBy: string;
  overrideReason: string;
  overrideUpdatedAtEpochMilli: number;
  fields: RuntimeSettingField[];
  overrides: RuntimeOverrides;
}

/** 一次覆盖写入的审计记录。 */
export interface RuntimeOverrideRecord {
  version: number;
  overrides: RuntimeOverrides;
  updatedBy: string;
  reason: string;
  updatedAtEpochMilli: number;
}

/** 配置写入请求；`expectedVersion` 必填，用于乐观锁。 */
export interface RuntimeConfigUpdateRequest {
  expectedVersion: number;
  overrides: RuntimeOverrides;
  reason: string;
}

/**
 * 一个已注册 Provider 的凭据状态。
 *
 * 只有"就位与否"和"来自哪个引用"两个字段，永远没有值。管理台需要回答的问题是"切到这个 Provider 会不会因为
 * 缺凭据而全线失败"，回答它不需要看到 Key，因此界面上也不应存在任何展示、输入或存储 Key 的位置。
 */
export interface ModelCredentialStatus {
  present: boolean;
  /** 凭据来源的可展示引用，例如 `env:DEEPSEEK_API_KEY`；不含值。 */
  reference: string;
}

/** 模型能力位；与 Scala 侧的 `ModelCapabilitiesView` 一一对应。 */
export interface ModelCapabilitiesView {
  toolCalls: boolean;
  parallelToolCalls: boolean;
  strictToolSchema: boolean;
  specificToolChoice: boolean;
  vision: boolean;
  thinking: boolean;
  streaming: boolean;
  usageReporting: boolean;
  maxInputTokens?: number | null;
  maxOutputTokens?: number | null;
}

/** 单价；金额是 BigDecimal 字符串，直接展示，不要 parseFloat 后再格式化。 */
export interface ModelPriceView {
  inputPerMillionTokens: string;
  outputPerMillionTokens: string;
  cachedInputPerMillionTokens?: string | null;
  currency: string;
}

/** 目录中的一个可选模型。 */
export interface ModelOptionView {
  provider: string;
  model: string;
  displayName: string;
  protocol: string;
  capabilities: ModelCapabilitiesView;
  isDefaultProvider: boolean;
  /** `false` 表示模型名只是该 Provider 的部署默认值，能力回退到 Provider 级推断。 */
  declaredModel: boolean;
  credential: ModelCredentialStatus;
  /** `null` / 缺失表示价格表未覆盖它，费用估算为零。 */
  price?: ModelPriceView | null;
}

/**
 * 向量化模型的只读描述。
 *
 * `switchable` 后端恒为 false，`immutableReason` 是后端给运维的完整解释，必须原样展示：这条约束的理由
 * （维度被迁移固定、既有向量只能与生成它的模型比较）由后端持有，前端复述一份只会在两处逐渐分叉。
 */
export interface EmbeddingModelView {
  provider: string;
  model: string;
  dimension: number;
  indexDimension?: number | null;
  switchable: boolean;
  immutableReason: string;
}

/** 模型页所需的完整快照。 */
export interface ModelCatalogView {
  options: ModelOptionView[];
  defaultProvider: string;
  /** `null` / 缺失表示沿用各 Agent 自己的定义，而不是"没有模型"。 */
  effectiveProvider?: string | null;
  effectiveModel?: string | null;
  embedding?: EmbeddingModelView | null;
  /** `null` / 缺失表示未声明价格表，成本估算恒为零。 */
  priceCurrency?: string | null;
  pricedOptionCount: number;
}

/** 连通性探活请求；`model` 缺失表示使用该 Provider 的默认模型。 */
export interface ModelProbeRequest {
  provider: string;
  model?: string;
}

/**
 * 连通性探活结果。
 *
 * 刻意不含模型输出正文：探活只证明"凭据有效、路由可达、能力协商通过"。因此界面上不能出现任何暗示能看到
 * 回答的措辞。
 */
export interface ModelProbeResult {
  provider: string;
  model: string;
  succeeded: boolean;
  latencyMillis: number;
  inputTokens: number;
  outputTokens: number;
  failureCode?: string | null;
}

/** 目录里出现的 Provider 顺序：默认 Provider 优先，其余按名称排序。 */
export function providersOf(catalog: ModelCatalogView | undefined): string[] {
  if (!catalog) return [];
  const names = Array.from(new Set(catalog.options.map((option) => option.provider)));
  return names.sort((left, right) => {
    if (left === catalog.defaultProvider) return -1;
    if (right === catalog.defaultProvider) return 1;
    return left.localeCompare(right);
  });
}

/** 队列聚合快照。 */
export interface QueueSnapshotView {
  capturedAtEpochMilli: number;
  queuedCommands: number;
  dispatchableRuns: number;
  leasedRuns: number;
  expiredLeases: number;
  deadLetterCommands: number;
  oldestDispatchableAgeMillis?: number | null;
}

/** 死信命令的运维视图；不含命令正文。 */
export interface DeadLetterCommandView {
  commandId: string;
  runId: string;
  commandType: string;
  attempt: number;
  manualRetryCount: number;
  lastFailure?: string | null;
  createdAtEpochMilli: number;
  updatedAtEpochMilli: number;
}

/** 一次死信重排的结果。 */
export interface CommandRetryResult {
  commandId: string;
  runId: string;
  status: string;
  manualRetryCount: number;
}

/** 知识索引版本的管理视图。 */
export interface KnowledgeDocumentView {
  tenantId: string;
  documentId: string;
  indexVersion: number;
  ingestionId: string;
  sourceUri: string;
  contentHash: string;
  status: string;
  active: boolean;
  chunkCount: number;
  permissions: string[];
  embeddingProvider: string;
  embeddingModel: string;
  embeddingDimension: number;
  indexingStrategy: string;
  failureCode?: string | null;
  createdAtEpochMilli: number;
  updatedAtEpochMilli: number;
  extractionMode?: string | null;
  extractionMethod?: string | null;
  extractionQuality?: string | null;
  extractionFallbackUsed?: boolean | null;
}

/** 一页知识索引清单。 */
export interface KnowledgeDocumentPage {
  items: KnowledgeDocumentView[];
  nextCursor?: string | null;
  hasMore: boolean;
}

/** 页面内几何位置，供在 PDF 上绘制高亮框。 */
export interface KnowledgeOriginView {
  pageNumber: number;
  blockId?: string | null;
  left?: number | null;
  top?: number | null;
  right?: number | null;
  bottom?: number | null;
  pageWidth?: number | null;
  pageHeight?: number | null;
}

/** 命中 chunk 的结构谱系与定位信息。 */
export interface KnowledgeChunkView {
  chunkId: string;
  documentId: string;
  sourceUri: string;
  tenantId: string;
  permissions: string[];
  indexVersion: number;
  text: string;
  textTruncated: boolean;
  headingPath: string[];
  parentId?: string | null;
  previousChunkId?: string | null;
  nextChunkId?: string | null;
  ordinal?: number | null;
  origins: KnowledgeOriginView[];
}

/**
 * 单条检索命中及其可解释信号。
 *
 * `signals` 是检索链各阶段留下的原始分数（向量余弦、全文 rank、RRF 名次、重排分数等）。管理台据此判断
 * 一次不理想的召回是向量不准、分词不对，还是重排把正确结果压了下去。键集合随检索实现变化，不要写死。
 */
export interface KnowledgeRetrievalHitView {
  chunk: KnowledgeChunkView;
  score: number;
  signals: Record<string, number>;
}

/** 检索沙盒返回的引用。 */
export interface KnowledgeCitationView {
  id: string;
  sourceUri: string;
  excerpt: string;
  score: number;
  pageNumbers: number[];
}

/**
 * 检索沙盒请求。
 *
 * `tenantId` 与 `permissions` 必须显式给出而不是沿用操作者身份：沙盒的价值正是"以某个业务主体的权限视角
 * 复现一次检索"，用管理员自己的权限去查会让 ACL 问题永远无法复现。
 */
export interface KnowledgeRetrieveRequest {
  query: string;
  tenantId: string;
  permissions?: string[];
  limit?: number;
  rerank?: boolean;
  expandContext?: boolean;
}

/** 检索沙盒结果。 */
export interface KnowledgeRetrievalResult {
  elapsedMillis: number;
  hits: KnowledgeRetrievalHitView[];
  citations: KnowledgeCitationView[];
  embeddingProvider: string;
  embeddingModel: string;
  embeddingDimension: number;
  rerankApplied: boolean;
  contextExpanded: boolean;
}

/** 退役某个知识索引版本的请求。 */
export interface KnowledgeRetireRequest {
  tenantId: string;
  expectedActiveVersion: number;
}

/**
 * 异步摄入任务的生命周期。
 *
 * 阶段与后端 `KnowledgeIndexStore` 的 begin→stage→activate 协议对齐，因此进度条对应真实索引状态机，
 * 而不是一个凭时间推进的假动画。
 */
export type IngestionJobStatus =
  | 'Queued'
  | 'Loading'
  | 'Chunking'
  | 'Embedding'
  | 'Staging'
  | 'Activating'
  | 'Completed'
  | 'Failed';

/** 摄入任务已到达终态；到达后应停止轮询。 */
export const INGESTION_TERMINAL_STATUSES: readonly IngestionJobStatus[] = ['Completed', 'Failed'];

/** 摄入任务的管理视图。 */
export interface IngestionJobView {
  jobId: string;
  tenantId: string;
  sourceUri: string;
  fileName: string;
  mediaType: string;
  status: IngestionJobStatus;
  progressPercent: number;
  documentId?: string | null;
  indexVersion?: number | null;
  chunkCount?: number | null;
  failureCode?: string | null;
  submittedBy: string;
  createdAtEpochMilli: number;
  updatedAtEpochMilli: number;
}

/** 一条评测趋势线的身份。 */
export interface EvalSuiteIdentityView {
  kind: string;
  suiteId: string;
  datasetId: string;
  datasetVersion: string;
}

/**
 * 趋势线上的一个数据点。
 *
 * `dimensionScores` 与 `dimensionGates` 分开，因为发布门禁的判定依据是布尔结果而不是分数：一个维度可以
 * 分数很高但仍然没通过硬门禁，把两者混在一起展示会误导。
 */
export interface EvalTrendPointView {
  evaluationId: string;
  harnessVersion: string;
  commitSha?: string | null;
  provider?: string | null;
  model?: string | null;
  finishedAtEpochMilli: number;
  passed: boolean;
  passRate: number;
  caseCount: number;
  dimensionScores: Record<string, number>;
  dimensionGates: Record<string, boolean>;
}

/** 一条趋势线的完整历史，按时间升序。 */
export interface EvalTrendSeries {
  identity: EvalSuiteIdentityView;
  points: EvalTrendPointView[];
}

/** 构造趋势线的稳定列表 key / URL 参数标识。 */
export function evalSuiteKey(identity: EvalSuiteIdentityView): string {
  return `${identity.kind}/${identity.suiteId}/${identity.datasetId}/${identity.datasetVersion}`;
}

/**
 * 按后端约定推导某个 Run 的 trace 标识。
 *
 * 规则与 `agent-opentelemetry` 一致：traceId 是 RunId 去掉连字符后的 32 位小写十六进制。因此 Run 列表
 * 可以直接链接到 trace，响应里不需要额外存一份 traceId。推导规则本身由后端下发，规则不匹配时返回 null
 * 而不是猜一个可能打不开的链接。
 */
export function traceIdForRun(links: ObservabilityLinks, runId: string): string | null {
  if (links.traceIdDerivation !== 'run-id-hex') return null;
  const hex = runId.replace(/-/g, '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(hex) ? hex : null;
}

/** 构造某个 Run 的 Langfuse trace 深链；未配置 Langfuse 时返回 null。 */
export function langfuseTraceUrl(links: ObservabilityLinks, runId: string): string | null {
  const base = links.langfuseBaseUrl?.replace(/\/+$/, '');
  const traceId = traceIdForRun(links, runId);
  if (!base || !traceId) return null;
  return links.langfuseProjectId
    ? `${base}/project/${links.langfuseProjectId}/traces/${traceId}`
    : `${base}/trace/${traceId}`;
}

/** 构造 Grafana 面板深链；未配置时返回 null。 */
export function grafanaDashboardUrl(links: ObservabilityLinks, runId?: string): string | null {
  const base = links.grafanaBaseUrl?.replace(/\/+$/, '');
  if (!base || !links.grafanaDashboardUid) return null;
  const url = `${base}/d/${links.grafanaDashboardUid}`;
  const traceId = runId ? traceIdForRun(links, runId) : null;
  return traceId ? `${url}?var-traceId=${traceId}` : url;
}

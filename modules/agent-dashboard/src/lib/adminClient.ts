/**
 * `/api/v1/admin/**` 的类型化 HTTP 客户端。
 *
 * 这一层只做四件事：拼 URL、带上凭据、把非 2xx 变成结构化异常、按契约类型解码。它刻意不含缓存、重试或
 * 状态管理——那些属于 React Query。把它们混进来会让"这次请求为什么没发出去"变成一个需要同时理解两套
 * 机制才能回答的问题。
 *
 * 认证遵循框架约定：`agent-zio-http` 不自带认证中间件，身份由宿主的 `AgentRequestContextResolver` 解析。
 * 因此客户端只负责透传一个 Bearer token，不假设它是 JWT、opaque token 还是网关注入的头。
 */

import type {
  AdminCapabilitiesView,
  CommandRetryResult,
  DeadLetterCommandView,
  ErrorResponse,
  EvalSuiteIdentityView,
  EvalTrendSeries,
  IngestionJobView,
  KnowledgeDocumentPage,
  KnowledgeRetireRequest,
  KnowledgeRetrievalResult,
  KnowledgeRetrieveRequest,
  ModelCatalogView,
  ModelProbeRequest,
  ModelProbeResult,
  QueueSnapshotView,
  RunDirectoryOverview,
  RunDirectoryPage,
  RunDirectoryQuery,
  RuntimeConfigUpdateRequest,
  RuntimeConfigView,
  RuntimeOverrideRecord,
} from '@/types/admin';

/** 管理 API 的基础路径；与 `AgentHttpProtocol.BasePath` 加上管理子面一致。 */
const ADMIN_BASE = '/api/v1/admin';

/**
 * 后端返回的错误。
 *
 * 保留 HTTP 状态码和后端的稳定 `category`，让 UI 能区分"你没权限"、"版本冲突需要重新加载"和"服务端故障"。
 * 只显示 message 会把这三种完全不同的处置方式压成同一句红字。
 */
export class AdminApiError extends Error {
  readonly status: number;
  readonly category: string;

  constructor(status: number, category: string, message: string) {
    super(message);
    this.name = 'AdminApiError';
    this.status = status;
    this.category = category;
  }

  /** 授权不足；UI 应提示补充管理 scope 而不是重试。 */
  get isForbidden(): boolean {
    return this.status === 401 || this.status === 403;
  }

  /** 乐观锁冲突；UI 必须重新加载后再提交，重试同一份请求只会再次失败。 */
  get isConflict(): boolean {
    return this.status === 409;
  }

  /** 后端未装配该能力。 */
  get isMissingCapability(): boolean {
    return this.status === 404;
  }
}

/** 客户端连接配置。 */
export interface AdminClientConfig {
  /** 后端根地址，例如 `http://localhost:8080`；末尾斜杠会被规范化。 */
  baseUrl: string;
  /** 可选 Bearer token；由宿主的认证方案决定其含义。 */
  token?: string;
  /**
   * `bearer` 由控制台直接发送 token；`host-session` 复用同源宿主的 HttpOnly Cookie/BFF，浏览器不接触 JWT。
   */
  authMode?: 'bearer' | 'host-session';
}

/** 已按 SSE framing 解码的一条消息；`data` 仍是服务端 JSON 字符串。 */
export interface AdminSseMessage {
  id?: string;
  event: string;
  data: string;
}

export interface RunEventStreamOptions {
  afterSequence?: number;
  signal: AbortSignal;
  onMessage: (message: AdminSseMessage) => void;
}

/** 规范化根地址，避免 `//api/v1` 这类双斜杠路径在某些网关上 404。 */
function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '');
}

/** 组装请求头；无 token 时不发送空 Authorization，那会让部分网关直接拒绝。 */
function headers(config: AdminClientConfig, json: boolean): HeadersInit {
  const result: Record<string, string> = { Accept: 'application/json' };
  if (json) result['Content-Type'] = 'application/json';
  if (config.authMode === 'host-session') result['X-ZYBLW-CSRF'] = '1';
  else if (config.token) result.Authorization = `Bearer ${config.token}`;
  return result;
}

/** 宿主会话模式必须显式携带同源 Cookie；Bearer 模式保持既有跨域管理台行为。 */
function credentials(config: AdminClientConfig): RequestCredentials | undefined {
  return config.authMode === 'host-session' ? 'same-origin' : undefined;
}

/**
 * 解码响应。
 *
 * 非 2xx 一律抛 `AdminApiError`。解析错误体本身失败时退回状态码文本，因为网关返回的 502 HTML 页面
 * 也必须变成一个可显示的错误，而不是一个 JSON 解析异常。
 */
async function decode<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!response.ok) {
    let category = `http-${response.status}`;
    let message = text.slice(0, 500) || response.statusText;
    try {
      const parsed = JSON.parse(text) as ErrorResponse;
      if (parsed?.category) category = parsed.category;
      if (parsed?.message) message = parsed.message;
    } catch {
      // 保留原始文本片段：网关错误页没有框架错误体，但对排查依然有价值。
    }
    throw new AdminApiError(response.status, category, message);
  }
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

/** 发起一次请求；网络层失败也统一成 `AdminApiError`，避免调用方处理两种异常形态。 */
async function request<T>(
  config: AdminClientConfig,
  path: string,
  init: RequestInit & { json?: unknown } = {},
): Promise<T> {
  const { json, ...rest } = init;
  const hasJsonBody = json !== undefined;
  try {
    const response = await fetch(`${normalizeBaseUrl(config.baseUrl)}${path}`, {
      ...rest,
      credentials: credentials(config),
      headers: { ...headers(config, hasJsonBody), ...(rest.headers ?? {}) },
      body: hasJsonBody ? JSON.stringify(json) : rest.body,
    });
    return await decode<T>(response);
  } catch (error) {
    if (error instanceof AdminApiError) throw error;
    throw new AdminApiError(0, 'network', error instanceof Error ? error.message : '无法连接到后端');
  }
}

/** 消费任意 chunk 边界的 SSE 文本；支持 CRLF、多行 data、注释 heartbeat 和 UTF-8 跨 chunk 字符。 */
async function consumeSse(
  response: Response,
  signal: AbortSignal,
  onMessage: (message: AdminSseMessage) => void,
): Promise<void> {
  if (!response.body) {
    throw new AdminApiError(0, 'stream-unavailable', '浏览器或网关没有提供可读取的事件流');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  function dispatch(block: string) {
    let id: string | undefined;
    let event = 'message';
    const data: string[] = [];

    for (const line of block.split(/\r\n|\r|\n/)) {
      if (!line || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'id' && !value.includes('\u0000')) id = value;
      else if (field === 'event') event = value || 'message';
      else if (field === 'data') data.push(value);
    }

    if (data.length > 0) onMessage({ id, event, data: data.join('\n') });
  }

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });

      let separator = buffer.match(/\r\n\r\n|\n\n|\r\r/);
      while (separator?.index !== undefined) {
        dispatch(buffer.slice(0, separator.index));
        buffer = buffer.slice(separator.index + separator[0].length);
        separator = buffer.match(/\r\n\r\n|\n\n|\r\r/);
      }

      if (done) break;
    }
  } finally {
    reader.releaseLock();
  }
}

/** 构造查询串；跳过 undefined 与空字符串，避免发送 `?tenantId=` 这种后端会当成合法空值的参数。 */
function queryString(params: Record<string, string | number | boolean | undefined | string[]>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value) if (item !== '') search.append(key, item);
    } else {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
}

/** 管理 API 的完整调用面。 */
export const adminApi = {
  /** 探测后端装配了哪些管理能力，以及外部观测系统的深链配置。 */
  capabilities(config: AdminClientConfig): Promise<AdminCapabilitiesView> {
    return request(config, `${ADMIN_BASE}/capabilities`);
  },

  /** 分页查询 Run 目录。 */
  runs(config: AdminClientConfig, query: RunDirectoryQuery): Promise<RunDirectoryPage> {
    return request(
      config,
      `${ADMIN_BASE}/runs${queryString({
        tenantId: query.tenantId,
        agentId: query.agentId,
        status: query.statuses,
        awaitingApproval: query.awaitingApproval ? 'true' : undefined,
        updatedAfter: query.updatedAfter,
        updatedBefore: query.updatedBefore,
        cursor: query.cursor,
        limit: query.limit,
      })}`,
    );
  },

  /** 读取按状态聚合的部署总览。 */
  runsOverview(config: AdminClientConfig, tenantId?: string): Promise<RunDirectoryOverview> {
    return request(config, `${ADMIN_BASE}/runs/overview${queryString({ tenantId })}`);
  },

  /**
   * 读取单 Run 的低敏耐久 SSE。
   *
   * 使用 fetch 而不是 EventSource，因为管理 token 必须放在 Authorization header，不能出现在 URL。调用方负责取消、
   * 重连和保存最后确认的 sequence；本方法只实现协议 framing 与结构化 HTTP 错误。
   */
  async streamRunEvents(
    config: AdminClientConfig,
    runId: string,
    options: RunEventStreamOptions,
  ): Promise<void> {
    const requestHeaders: Record<string, string> = {
      ...headers(config, false) as Record<string, string>,
      Accept: 'text/event-stream',
    };
    if (options.afterSequence !== undefined) {
      requestHeaders['Last-Event-ID'] = String(options.afterSequence);
    }

    try {
      const response = await fetch(
        `${normalizeBaseUrl(config.baseUrl)}${ADMIN_BASE}/runs/${encodeURIComponent(runId)}/events/stream`,
        { headers: requestHeaders, signal: options.signal, credentials: credentials(config) },
      );
      if (!response.ok) await decode<never>(response);
      await consumeSse(response, options.signal, options.onMessage);
    } catch (error) {
      if (options.signal.aborted) throw error;
      if (error instanceof AdminApiError) throw error;
      throw new AdminApiError(0, 'network', error instanceof Error ? error.message : '事件流连接失败');
    }
  },

  /** 读取有效配置快照（基线 + 覆盖 + 生效值）。 */
  config(config: AdminClientConfig): Promise<RuntimeConfigView> {
    return request(config, `${ADMIN_BASE}/config`);
  },

  /** 以乐观锁写入配置覆盖；版本不匹配返回 409。 */
  updateConfig(config: AdminClientConfig, body: RuntimeConfigUpdateRequest): Promise<RuntimeConfigView> {
    return request(config, `${ADMIN_BASE}/config`, { method: 'PUT', json: body });
  },

  /** 读取配置变更审计历史。 */
  configHistory(config: AdminClientConfig, limit = 20): Promise<RuntimeOverrideRecord[]> {
    return request(config, `${ADMIN_BASE}/config/history${queryString({ limit })}`);
  },

  /** 读取已注册模型目录；只读不触网，因此只要求 `agent:admin:read`。 */
  models(config: AdminClientConfig): Promise<ModelCatalogView> {
    return request(config, `${ADMIN_BASE}/models`);
  },

  /**
   * 对一个已注册组合执行一次最小连通性探活。
   *
   * 会向 Provider 发一次真实调用并产生真实费用，因此需要 `agent:admin:debug`，且只能由显式点击触发。
   * 响应不含模型输出正文：探活只证明凭据有效、路由可达、能力协商通过。
   */
  probeModel(config: AdminClientConfig, body: ModelProbeRequest): Promise<ModelProbeResult> {
    return request(config, `${ADMIN_BASE}/models/probe`, { method: 'POST', json: body });
  },

  /** 读取队列聚合快照。 */
  queue(config: AdminClientConfig): Promise<QueueSnapshotView> {
    return request(config, `${ADMIN_BASE}/ops/queue`);
  },

  /** 列出等待人工处理的死信命令。 */
  deadLetters(config: AdminClientConfig, limit = 50): Promise<DeadLetterCommandView[]> {
    return request(config, `${ADMIN_BASE}/ops/dead-letters${queryString({ limit })}`);
  },

  /** 把一条死信命令重新排队。 */
  retryDeadLetter(config: AdminClientConfig, commandId: string): Promise<CommandRetryResult> {
    return request(config, `${ADMIN_BASE}/ops/dead-letters/${encodeURIComponent(commandId)}/retry`, {
      method: 'POST',
    });
  },

  /** 分页列出知识索引版本清单。 */
  knowledgeDocuments(
    config: AdminClientConfig,
    params: { tenantId?: string; limit?: number; cursor?: string } = {},
  ): Promise<KnowledgeDocumentPage> {
    return request(config, `${ADMIN_BASE}/knowledge/documents${queryString(params)}`);
  },

  /** 执行一次真实检索。该操作会调用 Embedding Provider 并产生费用，需要 `agent:admin:debug`。 */
  knowledgeRetrieve(
    config: AdminClientConfig,
    body: KnowledgeRetrieveRequest,
  ): Promise<KnowledgeRetrievalResult> {
    return request(config, `${ADMIN_BASE}/knowledge/retrieve`, { method: 'POST', json: body });
  },

  /** 退役某个文档当前 Active 的索引版本。 */
  knowledgeRetire(
    config: AdminClientConfig,
    documentId: string,
    body: KnowledgeRetireRequest,
  ): Promise<void> {
    return request(config, `${ADMIN_BASE}/knowledge/documents/${encodeURIComponent(documentId)}/retire`, {
      method: 'POST',
      json: body,
    });
  },

  /**
   * 提交异步摄入任务，立即返回任务视图。
   *
   * 文件字节作为原始请求体发送、元数据放查询参数，与后端一致：走 multipart 会引入一个解析器，走 Base64
   * 会把二进制放大三分之一。返回 202 后由调用方轮询 `ingestionJob`。
   */
  submitIngestion(
    config: AdminClientConfig,
    params: {
      fileName: string;
      tenantId: string;
      mediaType?: string;
      permissions?: string[];
      extractionMode?: string;
    },
    content: Blob,
  ): Promise<IngestionJobView> {
    return request(
      config,
      `${ADMIN_BASE}/knowledge/ingestions${queryString({
        fileName: params.fileName,
        tenantId: params.tenantId,
        mediaType: params.mediaType,
        permissions: params.permissions,
        extractionMode: params.extractionMode,
      })}`,
      {
        method: 'POST',
        headers: {
          'Content-Type': params.mediaType || content.type || 'application/octet-stream',
        },
        body: content,
      },
    );
  },

  /**
   * 列出摄入任务。
   *
   * 单任务查询端点没有绑定：清单本身按"是否仍有非终态任务"自适应轮询，已经覆盖了提交后跟踪进度的场景，
   * 再留一个无人调用的单任务方法只会让读者以为存在两条不同的进度来源。
   */
  ingestionJobs(
    config: AdminClientConfig,
    params: { tenantId?: string; limit?: number } = {},
  ): Promise<IngestionJobView[]> {
    return request(config, `${ADMIN_BASE}/knowledge/ingestions${queryString(params)}`);
  },

  /** 列出本部署声明跟踪的评测趋势线。 */
  evalSuites(config: AdminClientConfig): Promise<EvalSuiteIdentityView[]> {
    return request(config, `${ADMIN_BASE}/evals/suites`);
  },

  /** 读取一条趋势线的历史数据点。 */
  evalTrend(
    config: AdminClientConfig,
    identity: EvalSuiteIdentityView,
    limit = 50,
  ): Promise<EvalTrendSeries> {
    return request(config, `${ADMIN_BASE}/evals/trend${queryString({ ...identity, limit })}`);
  },
};

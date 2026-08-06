'use client';

/**
 * 管理 API 的 React Query 绑定。
 *
 * 所有 query key 都带上 `baseUrl`，因为运维会在同一个标签页里切换环境。不带地址的 key 会让预发的缓存在
 * 切到生产后被当成生产数据显示——一个足以导致误操作的错误。
 *
 * 授权失败和能力缺失不重试：401/403 重试只会刷日志，404 表示后端根本没装配该能力，再试一次也不会出现。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminApiError, adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import {
  INGESTION_TERMINAL_STATUSES,
  evalSuiteKey,
  type EvalSuiteIdentityView,
  type ModelProbeRequest,
  type RunDirectoryQuery,
  type RuntimeConfigUpdateRequest,
} from '@/types/admin';

/** 队列与摄入任务是活动数据，值班界面需要它自己动起来，而不是等人按刷新。 */
const LIVE_REFETCH_MS = 5_000;

/** Run 目录与知识清单变化较慢，用更长的间隔换取更少的数据库扫描。 */
const SLOW_REFETCH_MS = 20_000;

/** 不该重试的错误：授权不足、能力缺失、乐观锁冲突都需要人介入而不是再发一次。 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof AdminApiError) {
    if (error.isForbidden || error.isMissingCapability || error.isConflict) return false;
  }
  return failureCount < 2;
}

/**
 * 探测后端能力；页签可见性和深链配置都依赖它，因此缓存较久且失败不静默。
 *
 * 未提供凭据时由调用方禁用：能力探测同样要求 `agent:admin:read`，在没有 token 的情况下发起它只会得到一个
 * 401，并让引导页面上多出一条与"你还没填凭据"无关的错误。
 */
export function useCapabilities(enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['capabilities', config.baseUrl],
    queryFn: () => adminApi.capabilities(config),
    retry: shouldRetry,
    staleTime: 60_000,
    enabled,
  });
}

/** 分页查询 Run 目录；`enabled` 让调用方在能力缺失时避免无意义请求。 */
export function useRuns(query: RunDirectoryQuery, enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['runs', config.baseUrl, query],
    queryFn: () => adminApi.runs(config, query),
    retry: shouldRetry,
    refetchInterval: SLOW_REFETCH_MS,
    enabled,
  });
}

/** 状态聚合总览。 */
export function useRunsOverview(tenantId: string | undefined, enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['runs-overview', config.baseUrl, tenantId ?? null],
    queryFn: () => adminApi.runsOverview(config, tenantId),
    retry: shouldRetry,
    refetchInterval: SLOW_REFETCH_MS,
    enabled,
  });
}

/** 有效配置快照。 */
export function useRuntimeConfig(enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['config', config.baseUrl],
    queryFn: () => adminApi.config(config),
    retry: shouldRetry,
    enabled,
  });
}

/** 配置变更审计历史。 */
export function useConfigHistory(limit = 20, enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['config-history', config.baseUrl, limit],
    queryFn: () => adminApi.configHistory(config, limit),
    retry: shouldRetry,
    enabled,
  });
}

/**
 * 写入配置覆盖。
 *
 * 成功后同时失效配置与历史缓存。这里不做乐观更新：一次配置写入可能被后端校验拒绝或撞上版本冲突，
 * 先在界面上显示成功再回滚，会让运维在最不该怀疑的地方产生怀疑。
 */
export function useUpdateRuntimeConfig() {
  const { config } = useConnection();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: RuntimeConfigUpdateRequest) => adminApi.updateConfig(config, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['config', config.baseUrl] });
      void client.invalidateQueries({ queryKey: ['config-history', config.baseUrl] });
      // 模型目录里的 effectiveProvider / effectiveModel 由同一份覆盖派生，因此配置写入也必须让它失效，
      // 否则模型页会在切换成功后继续显示旧的生效组合。
      void client.invalidateQueries({ queryKey: ['models', config.baseUrl] });
    },
  });
}

/**
 * 已注册模型目录。
 *
 * 目录同时是模型切换表单的取值来源和写入前的合法性依据，因此缓存较久但不禁用重新获取：另一个副本重启后
 * 注册的 Provider 集合可能变化，一个永不过期的目录会让下拉框继续提供已经不可路由的组合。
 */
export function useModelCatalog(enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['models', config.baseUrl],
    queryFn: () => adminApi.models(config),
    retry: shouldRetry,
    staleTime: 60_000,
    enabled,
  });
}

/**
 * 模型连通性探活。
 *
 * 与检索沙盒同类，用 mutation 而不是 query：每次探活都向 Provider 发一次真实调用并产生真实费用，而 query
 * 会因为窗口重新获得焦点、组件重挂载或缓存过期而自动重发。
 */
export function useProbeModel() {
  const { config } = useConnection();
  return useMutation({
    mutationFn: (body: ModelProbeRequest) => adminApi.probeModel(config, body),
  });
}

/** 队列快照。 */
export function useQueueSnapshot(enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['queue', config.baseUrl],
    queryFn: () => adminApi.queue(config),
    retry: shouldRetry,
    refetchInterval: LIVE_REFETCH_MS,
    enabled,
  });
}

/** 死信清单。 */
export function useDeadLetters(limit = 50, enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['dead-letters', config.baseUrl, limit],
    queryFn: () => adminApi.deadLetters(config, limit),
    retry: shouldRetry,
    refetchInterval: LIVE_REFETCH_MS,
    enabled,
  });
}

/** 重排死信；成功后同时刷新清单与队列快照，因为积压计数会随之变化。 */
export function useRetryDeadLetter() {
  const { config } = useConnection();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (commandId: string) => adminApi.retryDeadLetter(config, commandId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['dead-letters', config.baseUrl] });
      void client.invalidateQueries({ queryKey: ['queue', config.baseUrl] });
    },
  });
}

/** 知识索引清单。 */
export function useKnowledgeDocuments(
  params: { tenantId?: string; limit?: number; cursor?: string },
  enabled = true,
) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['knowledge-documents', config.baseUrl, params],
    queryFn: () => adminApi.knowledgeDocuments(config, params),
    retry: shouldRetry,
    refetchInterval: SLOW_REFETCH_MS,
    enabled,
  });
}

/**
 * 检索沙盒。
 *
 * 用 mutation 而不是 query：每次检索都调用 Embedding Provider 并产生真实费用，而 query 会因为窗口重新
 * 获得焦点、组件重挂载或缓存过期而自动重发。一个按 topK 调试召回的界面绝不能有隐式重发。
 */
export function useKnowledgeRetrieve() {
  const { config } = useConnection();
  return useMutation({
    mutationFn: (body: Parameters<typeof adminApi.knowledgeRetrieve>[1]) =>
      adminApi.knowledgeRetrieve(config, body),
  });
}

/** 退役索引版本。 */
export function useRetireDocument() {
  const { config } = useConnection();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { documentId: string; tenantId: string; expectedActiveVersion: number }) =>
      adminApi.knowledgeRetire(config, input.documentId, {
        tenantId: input.tenantId,
        expectedActiveVersion: input.expectedActiveVersion,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['knowledge-documents', config.baseUrl] });
    },
  });
}

/** 提交摄入任务。 */
export function useSubmitIngestion() {
  const { config } = useConnection();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      params: Parameters<typeof adminApi.submitIngestion>[1];
      content: Blob;
    }) => adminApi.submitIngestion(config, input.params, input.content),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['ingestion-jobs', config.baseUrl] });
    },
  });
}

/**
 * 摄入任务列表。
 *
 * 只要还有任务处于非终态就继续轮询；全部到达终态后停止。这比固定间隔轮询更好：摄入通常几分钟才发生一次，
 * 让一个空闲的管理页面每 3 秒打一次数据库没有意义。
 */
export function useIngestionJobs(params: { tenantId?: string; limit?: number } = {}, enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['ingestion-jobs', config.baseUrl, params],
    queryFn: () => adminApi.ingestionJobs(config, params),
    retry: shouldRetry,
    refetchInterval: (query) => {
      const jobs = query.state.data;
      if (!jobs) return false;
      const active = jobs.some((job) => !INGESTION_TERMINAL_STATUSES.includes(job.status));
      return active ? 3_000 : false;
    },
    enabled,
  });
}

/** 跟踪的评测套件。 */
export function useEvalSuites(enabled = true) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['eval-suites', config.baseUrl],
    queryFn: () => adminApi.evalSuites(config),
    retry: shouldRetry,
    staleTime: 60_000,
    enabled,
  });
}

/** 单条趋势线历史。 */
export function useEvalTrend(identity: EvalSuiteIdentityView | undefined, limit = 50) {
  const { config } = useConnection();
  return useQuery({
    queryKey: ['eval-trend', config.baseUrl, identity ? evalSuiteKey(identity) : null, limit],
    queryFn: () => adminApi.evalTrend(config, identity as EvalSuiteIdentityView, limit),
    retry: shouldRetry,
    enabled: identity !== undefined,
  });
}

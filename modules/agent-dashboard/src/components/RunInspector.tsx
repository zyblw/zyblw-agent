'use client';

/**
 * 现代智能体运行目录与追踪器 (Run Directory & Trace Inspector - Clean Light Palette)。
 *
 * 行业最佳实践 (LangSmith / Langfuse / Dify / Stripe)：
 * 1. 指标看板：总览、待审批告警、挂起等待、活跃运行、失败异常。
 * 2. 多维检索：按租户、Agent、审批状态与生命周期状态多路过滤，基于 keyset 游标无损翻页。
 * 3. Master-Detail 侧滑抽屉：点击任一行右侧滑出全景调试抽屉，包含即时审批工坊 (HITL)、实时事件流 (SSE)、Token/费用账本及架构元数据。
 */

import React, { useMemo, useState } from 'react';
import {
  Bot,
  ChevronLeft,
  ChevronRight,
  Clock,
  ExternalLink,
  Filter,
  X,
} from 'lucide-react';
import { RunEventStream } from '@/components/RunEventStream';
import { useRuns, useRunsOverview } from '@/lib/queries';
import { useConnection } from '@/lib/connection';
import { useToast } from '@/lib/toast';
import { adminApi } from '@/lib/adminClient';
import {
  langfuseTraceUrl,
  traceIdForRun,
  type AdminCapabilitiesView,
  type RunSummaryView,
} from '@/types/admin';
import {
  formatCost,
  formatCount,
  formatInstant,
  formatRelative,
  runStatusTone,
} from '@/lib/format';
import { encodeFlag, encodeList, useDebouncedUrlValue, useUrlState } from '@/lib/urlState';
import {
  Badge,
  Button,
  CopyableId,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  StatCard,
  TextInput,
} from '@/components/ui';

/** 与后端 `RunStatus` 一致的过滤选项。 */
const STATUS_OPTIONS = [
  'Created',
  'Running',
  'WaitingForApproval',
  'Suspended',
  'Completed',
  'Failed',
  'Cancelled',
  'TimedOut',
  'BudgetExceeded',
];

export function RunInspector({ capabilities }: { capabilities: AdminCapabilitiesView | undefined }) {
  const url = useUrlState();
  const [tenantDraft, setTenantDraft, tenantId] = useDebouncedUrlValue('runTenant');
  const [agentDraft, setAgentDraft, agentId] = useDebouncedUrlValue('runAgent');
  const statusKey = url.get('runStatus');
  const statuses = useMemo(() => statusKey.split(',').filter(Boolean), [statusKey]);
  const awaitingOnly = url.getFlag('runAwaiting');
  const selectedRunId = url.get('runId');

  const filterKey = `${tenantId}|${agentId}|${statuses.join(',')}|${awaitingOnly}`;
  const [paging, setPaging] = useState<{ key: string; stack: (string | undefined)[] }>({
    key: filterKey,
    stack: [undefined],
  });
  const cursorStack = paging.key === filterKey ? paging.stack : [undefined];
  const cursor = cursorStack[cursorStack.length - 1];

  const query = useMemo(
    () => ({
      tenantId: tenantId || undefined,
      agentId: agentId || undefined,
      statuses: statuses.length > 0 ? statuses : undefined,
      awaitingApproval: awaitingOnly || undefined,
      cursor,
      limit: 25,
    }),
    [tenantId, agentId, statuses, awaitingOnly, cursor],
  );

  const runs = useRuns(query);
  const overview = useRunsOverview(tenantId || undefined);

  function toggleStatus(status: string) {
    const next = statuses.includes(status)
      ? statuses.filter((item) => item !== status)
      : [...statuses, status];
    url.set({ runStatus: encodeList(next) });
  }

  const items = runs.data?.items ?? [];
  const selected = items.find((run) => run.runId === selectedRunId) ?? null;

  return (
    <div className="relative space-y-6 p-6">
      {/* 顶部运行健康度与指标总览 */}
      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-5">
        <StatCard
          label="运行总数 (Total Runs)"
          value={formatCount(overview.data?.totalRuns)}
          hint={overview.data ? `采样于 ${formatRelative(overview.data.capturedAtEpochMilli)}` : undefined}
        />
        <StatCard
          label="等待审批 (HITL)"
          value={formatCount(overview.data?.awaitingApproval)}
          tone={overview.data && overview.data.awaitingApproval > 0 ? 'warn' : 'neutral'}
          hint="需要人工介入授权"
        />
        <StatCard
          label="异步挂起 (Suspended)"
          value={formatCount(overview.data?.awaitingSignal)}
          tone={overview.data && (overview.data.awaitingSignal ?? 0) > 0 ? 'warn' : 'neutral'}
          hint="等信号 / 人工输入 / 定时器"
        />
        <StatCard
          label="活跃运行 (Running)"
          value={formatCount(overview.data?.countsByStatus?.Running ?? 0)}
          tone="good"
          hint="进行中推理流水"
        />
        <StatCard
          label="失败异常 (Failed)"
          value={formatCount(overview.data?.countsByStatus?.Failed ?? 0)}
          tone={(overview.data?.countsByStatus?.Failed ?? 0) > 0 ? 'danger' : 'neutral'}
          hint="执行报错或超预算阻断"
        />
      </div>

      <ErrorBanner error={overview.error} context="读取 Run 总览指标" />

      {/* 主面板：Run 目录与列表 */}
      <Panel
        title="Run 目录"
        description="按 (更新时间, RunId) 稳定倒序排列，使用 keyset 游标翻页"
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              disabled={cursorStack.length <= 1}
              onClick={() => setPaging({ key: filterKey, stack: cursorStack.slice(0, -1) })}
            >
              <ChevronLeft className="h-3.5 w-3.5" /> 上一页
            </Button>
            <Button
              variant="secondary"
              disabled={!runs.data?.hasMore || !runs.data?.nextCursor}
              onClick={() =>
                setPaging({ key: filterKey, stack: [...cursorStack, runs.data?.nextCursor ?? undefined] })
              }
            >
              下一页 <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        }
      >
        {/* 筛选工具栏 */}
        <div className="mb-4 space-y-3 rounded-lg border border-slate-200 bg-slate-50/60 p-3.5">
          <div className="grid gap-3 md:grid-cols-[1fr_1fr_auto]">
            <TextInput
              label="租户空间"
              value={tenantDraft}
              onChange={setTenantDraft}
              placeholder="留空表示跨租户"
            />
            <TextInput
              label="智能体 (Agent)"
              value={agentDraft}
              onChange={setAgentDraft}
              placeholder="留空表示全部 Agent"
            />
            <div className="flex items-end pb-1.5">
              <label className="flex items-center gap-2 text-xs font-medium text-slate-700 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={awaitingOnly}
                  onChange={(event) => url.set({ runAwaiting: encodeFlag(event.target.checked) })}
                  className={`rounded border-slate-300 text-indigo-600 ${FOCUS_RING}`}
                />
                仅显示待审批
              </label>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5 pt-2 border-t border-slate-200/80">
            <div className="flex items-center gap-1 text-xs text-slate-500 mr-1 font-medium">
              <Filter className="h-3.5 w-3.5" />
              <span>状态过滤:</span>
            </div>
            {STATUS_OPTIONS.map((status) => {
              const active = statuses.includes(status);
              return (
                <button
                  key={status}
                  type="button"
                  aria-pressed={active}
                  onClick={() => toggleStatus(status)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium ring-1 ring-inset transition ${FOCUS_RING} ${
                    active
                      ? runStatusTone(status)
                      : 'text-slate-600 ring-slate-200 bg-white hover:bg-slate-50 hover:text-slate-900'
                  }`}
                >
                  {status}
                </button>
              );
            })}
          </div>
        </div>

        <ErrorBanner error={runs.error} context="查询 Run 目录" />

        {/* 列表渲染 */}
        {runs.isPending ? (
          <LoadingRows rows={6} />
        ) : items.length === 0 ? (
          <EmptyState
            title="没有匹配的 Run"
            reason="若刚接入框架，请确认宿主装配的是 PostgreSQL 的 RunDirectory 适配器；仅使用内存 Store 时该列表将始终为空。"
          />
        ) : (
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full text-left text-xs">
              <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                <tr>
                  <th className="py-2.5 px-3 font-semibold">Run ID</th>
                  <th className="py-2.5 px-3 font-semibold">Agent</th>
                  <th className="py-2.5 px-3 font-semibold">状态</th>
                  <th className="py-2.5 px-3 font-semibold text-right">步数</th>
                  <th className="py-2.5 px-3 font-semibold text-right">总 Token</th>
                  <th className="py-2.5 px-3 font-semibold text-right">预估费用</th>
                  <th className="py-2.5 px-3 font-semibold">租户</th>
                  <th className="py-2.5 px-3 font-semibold">更新时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((run) => {
                  const isSelected = run.runId === selectedRunId;
                  return (
                    <tr
                      key={run.runId}
                      tabIndex={0}
                      onClick={() => url.set({ runId: run.runId })}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          url.set({ runId: run.runId });
                        }
                      }}
                      className={`cursor-pointer transition hover:bg-slate-50 ${FOCUS_RING} ${
                        isSelected
                          ? 'bg-indigo-50/80 font-medium text-slate-900 ring-1 ring-inset ring-indigo-200'
                          : 'text-slate-700'
                      }`}
                    >
                      <td className="py-2.5 px-3">
                        <CopyableId value={run.runId} label="Run ID" truncate={8} />
                      </td>
                      <td className="py-2.5 px-3 text-slate-900">
                        <div className="flex items-center gap-1.5 font-medium">
                          <Bot className="h-3.5 w-3.5 text-indigo-600 shrink-0" />
                          <span className="font-mono">{run.agentId}</span>
                        </div>
                      </td>
                      <td className="py-2.5 px-3">
                        <div className="flex items-center gap-1 flex-wrap">
                          <Badge className={runStatusTone(run.status)}>{run.status}</Badge>
                          {run.awaitingApproval && (
                            <Badge className="text-amber-800 bg-amber-50 ring-amber-600/25">
                              审批
                            </Badge>
                          )}
                          {run.awaitingSignal && (
                            <Badge className="text-violet-700 bg-violet-50 ring-violet-600/20">
                              {run.suspensionKind ?? '挂起'}
                            </Badge>
                          )}
                        </div>
                      </td>
                      <td className="py-2.5 px-3 tabular-nums text-slate-600 text-right">{run.steps}</td>
                      <td className="py-2.5 px-3 tabular-nums text-slate-800 text-right font-mono">
                        {formatCount(run.usage.totalTokens)}
                      </td>
                      <td className="py-2.5 px-3 tabular-nums text-emerald-700 text-right font-mono font-medium">
                        {formatCost(run.usage.estimatedCost)}
                      </td>
                      <td className="py-2.5 px-3 text-slate-500 font-mono">{run.tenantId ?? '—'}</td>
                      <td className="py-2.5 px-3 text-slate-500">{formatRelative(run.updatedAtEpochMilli)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {/* 选中 Run 时的全景侧滑抽屉 (Master-Detail Slide-Over Drawer) */}
      {selected && (
        <RunDetailDrawer
          run={selected}
          capabilities={capabilities}
          onClose={() => url.set({ runId: undefined })}
        />
      )}
    </div>
  );
}

/** 现代 Master-Detail 侧滑详情抽屉 */
function RunDetailDrawer({
  run,
  capabilities,
  onClose,
}: {
  run: RunSummaryView;
  capabilities: AdminCapabilitiesView | undefined;
  onClose: () => void;
}) {
  const { notify } = useToast();
  const url = useUrlState();
  const traceUrl = capabilities ? langfuseTraceUrl(capabilities.observability, run.runId) : null;
  const traceId = capabilities ? traceIdForRun(capabilities.observability, run.runId) : null;

  return (
    <>
      {/* 半透明遮罩 */}
      <div
        className="fixed inset-0 z-40 bg-slate-900/30 backdrop-blur-xs transition-opacity"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* 抽屉正文 */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Run ${run.runId} 详情`}
        className="fixed inset-y-0 right-0 z-50 flex w-full max-w-4xl flex-col border-l border-slate-200 bg-white shadow-2xl animate-in slide-in-from-right duration-200"
      >
        {/* 抽屉顶部标题栏 */}
        <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 bg-slate-50/80">
          <div className="flex items-center gap-3 min-w-0">
            <div className="flex flex-col min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  运行详情
                </span>
                <Badge className={runStatusTone(run.status)}>{run.status}</Badge>
              </div>
              <div className="mt-1 flex items-center gap-2 text-xs">
                <CopyableId value={run.runId} label="Run ID" />
                <span className="text-slate-400">·</span>
                <span className="font-mono text-slate-800 font-semibold">{run.agentId}</span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => {
                const evalFixture = {
                  caseId: `run-${run.runId.slice(0, 8)}`,
                  agentId: run.agentId,
                  status: run.status,
                  steps: run.steps,
                  usage: run.usage,
                  capturedAtEpochMilli: Date.now(),
                };
                navigator.clipboard?.writeText(JSON.stringify(evalFixture, null, 2));
                notify('info', '已捕获评测用例', '已提取该 Run 的元数据并复制到剪贴板，可直接沉淀入 agent-evals');
              }}
              className={`inline-flex items-center gap-1 rounded-md border border-indigo-200 bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-700 hover:bg-indigo-100 transition shadow-xs ${FOCUS_RING}`}
            >
              📥 捕获为评测用例
            </button>
            <button
              type="button"
              onClick={() => {
                url.set({ tab: 'inspect', runId: run.runId });
              }}
              className={`inline-flex items-center gap-1 rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-700 hover:bg-blue-100 transition shadow-xs ${FOCUS_RING}`}
            >
              🔍 穿透架构检查
            </button>
            {traceUrl && (
              <a
                href={traceUrl}
                target="_blank"
                rel="noreferrer"
                className={`inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 transition shadow-xs ${FOCUS_RING}`}
              >
                Langfuse <ExternalLink className="h-3 w-3" />
              </a>
            )}
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 transition"
              aria-label="关闭详情抽屉"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {/* 抽屉主体内容区 */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6 custom-scrollbar bg-slate-50/50">
          {/* HITL 审批干预卡片 */}
          {run.awaitingApproval && (
            <ApprovalInterventionCard run={run} onComplete={() => url.set({ runId: run.runId })} />
          )}

          {/* 挂起状态横幅 */}
          {run.awaitingSignal && (
            <div className="rounded-xl border border-violet-200 bg-violet-50/80 p-4 text-xs text-violet-900 shadow-xs">
              <div className="font-semibold text-violet-950 flex items-center gap-1.5">
                <Clock className="h-4 w-4 text-violet-600" /> 等待外部推进 (Suspension)
              </div>
              <div className="mt-1.5 text-violet-800 leading-relaxed">
                挂起原因: <Mono className="font-bold text-violet-900">{run.suspensionKind ?? '未知'}</Mono>
                {run.suspensionDeadlineEpochMilli
                  ? ` · 截止时间: ${formatInstant(run.suspensionDeadlineEpochMilli)}`
                  : ' · 无截止时间限制'}
              </div>
            </div>
          )}

          {/* 实时执行事件流与瀑布流 (Trace & Events) */}
          {capabilities?.runEventStream && <RunEventStream key={run.runId} run={run} />}

          {/* Token 与费用核算账本 */}
          <Panel
            title="Token 与费用账本 (Usage & Cost Ledger)"
            description="LLM Provider 细分计量、缓存命中收益与推理 Token 统计"
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <div className="rounded-lg border border-slate-200 bg-white p-3.5 shadow-xs">
                <span className="text-[11px] font-medium text-slate-500">模型调用 / 工具调用</span>
                <div className="mt-1 flex items-baseline gap-1.5 font-mono text-base font-semibold text-slate-900">
                  <span>{formatCount(run.usage.modelCalls)} 次</span>
                  <span className="text-xs text-slate-400">/</span>
                  <span className="text-blue-600">{formatCount(run.usage.toolCalls)} 次</span>
                </div>
              </div>
              <div className="rounded-lg border border-slate-200 bg-white p-3.5 shadow-xs">
                <span className="text-[11px] font-medium text-slate-500">输入 / 输出 Token</span>
                <div className="mt-1 flex items-baseline gap-1.5 font-mono text-base font-semibold text-slate-900">
                  <span className="text-indigo-600">{formatCount(run.usage.inputTokens)}</span>
                  <span className="text-xs text-slate-400">/</span>
                  <span className="text-violet-600">{formatCount(run.usage.outputTokens)}</span>
                </div>
              </div>
              <div className="rounded-lg border border-slate-200 bg-white p-3.5 shadow-xs">
                <span className="text-[11px] font-medium text-slate-500">缓存命中 / 写入 Token</span>
                <div className="mt-1 flex items-baseline gap-1.5 font-mono text-base font-semibold text-slate-900">
                  <span className="text-emerald-600">{formatCount(run.usage.cachedInputTokens)}</span>
                  <span className="text-xs text-slate-400">/</span>
                  <span className="text-slate-600">{formatCount(run.usage.cacheWriteInputTokens)}</span>
                </div>
              </div>
              <div className="rounded-lg border border-slate-200 bg-white p-3.5 shadow-xs">
                <span className="text-[11px] font-medium text-slate-500">深度推理 / 预估总费用</span>
                <div className="mt-1 flex items-baseline gap-1.5 font-mono text-base font-semibold text-slate-900">
                  <span className="text-amber-600">{formatCount(run.usage.reasoningOutputTokens)}</span>
                  <span className="text-xs text-slate-400">/</span>
                  <span className="text-emerald-700 font-bold">{formatCost(run.usage.estimatedCost)}</span>
                </div>
              </div>
            </div>
          </Panel>

          {/* 会话上下文与运行元数据 */}
          <Panel
            title="会话与环境元数据 (Context & Metadata)"
            description="仅包含运维元数据；Prompt 提示词与工具传参请在业务系统安全查看"
          >
            <div className="grid gap-x-8 md:grid-cols-3">
              <div className="divide-y divide-slate-100">
                <Field label="Run ID">
                  <CopyableId value={run.runId} label="Run ID" className="text-slate-900" />
                </Field>
                <Field label="Trace ID">
                  {traceId ? (
                    <CopyableId value={traceId} label="trace ID" truncate={12} className="text-slate-900" />
                  ) : (
                    '—'
                  )}
                </Field>
                <Field label="Session ID">
                  <CopyableId value={run.sessionId} label="Session ID" className="text-slate-900" />
                </Field>
                <Field label="Thread ID">{run.threadId ?? '—'}</Field>
              </div>
              <div className="divide-y divide-slate-100">
                <Field label="Agent ID">{run.agentId}</Field>
                <Field label="租户 / 用户空间">
                  {run.tenantId ?? '—'} / {run.userId ?? '—'}
                </Field>
                <Field label="状态版本 (State Version)">{run.stateVersion}</Field>
                <Field label="最后事件序号 (Last Sequence)">{run.lastEventSequence}</Field>
              </div>
              <div className="divide-y divide-slate-100">
                <Field label="步数统计">{run.steps} 步</Field>
                <Field label="创建时间">{formatInstant(run.createdAtEpochMilli)}</Field>
                <Field label="更新时间">{formatInstant(run.updatedAtEpochMilli)}</Field>
                <Field label="相对更新">{formatRelative(run.updatedAtEpochMilli)}</Field>
              </div>
            </div>
          </Panel>
        </div>
      </div>
    </>
  );
}

/** 交互式审批干预控制卡片 (HITL Action Workbench) */
function ApprovalInterventionCard({
  run,
  onComplete,
}: {
  run: RunSummaryView;
  onComplete: () => void;
}) {
  const { config } = useConnection();
  const { notify } = useToast();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleDecision(decision: 'approve' | 'reject') {
    setSubmitting(true);
    try {
      await adminApi.approveRun(config, run.runId, decision, reason.trim() || undefined);
      notify(
        decision === 'approve' ? 'success' : 'info',
        decision === 'approve' ? '审批已通过' : '已驳回审批',
        `Run ${run.runId.slice(0, 8)} 已向底层命令队列提交决策，正在恢复推进`,
      );
      setReason('');
      onComplete();
    } catch (err) {
      notify(
        'error',
        '提交审批决定失败',
        err instanceof Error ? err.message : '请确认凭据具备 agent:admin:write 权限',
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50/80 p-4 text-xs shadow-xs">
      <div className="flex items-center gap-2 font-semibold text-amber-900">
        <span className="flex h-2.5 w-2.5 rounded-full bg-amber-500 animate-pulse" />
        等待人工审批决策 (Human-in-the-Loop)
      </div>
      <div className="mt-2 text-amber-800 leading-relaxed">
        目标工具: <Mono className="font-bold text-amber-950">{run.pendingApprovalToolName ?? '未知'}</Mono> · 风险等级:{' '}
        <Badge className="ml-1 text-amber-900 bg-amber-100 ring-amber-300">
          {run.pendingApprovalRisk ?? '未知'}
        </Badge>
      </div>
      <div className="mt-3.5 flex flex-col sm:flex-row gap-2.5 items-end">
        <div className="w-full sm:flex-1">
          <TextInput
            value={reason}
            onChange={setReason}
            placeholder="审批备注或驳回原因 (可选)"
            disabled={submitting}
          />
        </div>
        <div className="flex gap-2 shrink-0">
          <Button
            onClick={() => void handleDecision('approve')}
            disabled={submitting}
            variant="primary"
          >
            ✅ 批准执行 (Approve)
          </Button>
          <Button
            onClick={() => void handleDecision('reject')}
            disabled={submitting}
            variant="danger"
          >
            ❌ 驳回 (Reject)
          </Button>
        </div>
      </div>
    </div>
  );
}

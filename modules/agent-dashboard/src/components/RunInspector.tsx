'use client';

/**
 * Run 目录：状态总览、过滤、keyset 翻页与单个 Run 的元数据详情。
 *
 * 翻页使用后端返回的不透明游标而不是页号。Run 会在翻页过程中持续更新，基于 OFFSET 的分页会让同一条记录
 * 重复出现或被整页跳过——这在排查一个正在跑的批次时尤其危险。
 *
 * 这里只展示元数据。用户输入、模型输出和工具参数属于业务数据，跨租户的运维界面不应成为它们的导出通道。
 *
 * 过滤条件与选中的 Run 都放在 URL 里：值班交接时"你看一下这几个失败的 Run"必须是一个可以直接发出去的地址。
 */

import React, { useMemo, useState } from 'react';
import { CheckCircle2, ChevronLeft, ChevronRight, ExternalLink, Filter } from 'lucide-react';
import { RunEventStream } from '@/components/RunEventStream';
import { useRuns, useRunsOverview } from '@/lib/queries';
import {
  langfuseTraceUrl,
  traceIdForRun,
  type AdminCapabilitiesView,
  type RunSummaryView,
} from '@/types/admin';
import { formatCount, formatInstant, formatRelative, runStatusTone } from '@/lib/format';
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
const STATUS_OPTIONS = ['Running', 'AwaitingApproval', 'Succeeded', 'Failed', 'Cancelled'];

export function RunInspector({ capabilities }: { capabilities: AdminCapabilitiesView | undefined }) {
  const url = useUrlState();
  // 两个文本筛选走防抖：草稿驱动输入框，已提交值驱动查询与游标栈，否则每次击键都是一次跨租户目录扫描。
  const [tenantDraft, setTenantDraft, tenantId] = useDebouncedUrlValue('runTenant');
  const [agentDraft, setAgentDraft, agentId] = useDebouncedUrlValue('runAgent');
  // 状态集合以编码形式读入再派生成数组：直接用 `getList` 会在每次渲染产生一个新数组，让下游的 query key
  // 每帧都变，React Query 会因此不停重新获取。
  const statusKey = url.get('runStatus');
  const statuses = useMemo(() => statusKey.split(',').filter(Boolean), [statusKey]);
  const awaitingOnly = url.getFlag('runAwaiting');
  const selectedRunId = url.get('runId');

  // 游标栈：栈顶是当前页的游标，出栈即返回上一页。只保存 nextCursor 无法后退。
  //
  // 游标本身不进 URL：它是一个只对某一组过滤条件有效的不透明 keyset 令牌，分享一个带游标的地址会让对方从
  // 一个无法解释的位置开始看。栈与产生它的过滤条件一起保存，条件一变就在渲染时判定为失效并从头开始——
  // 这比在 effect 里监听过滤变化再清栈少一轮渲染，也不会出现"用旧游标查新过滤"的中间态。
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
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Run 总数" value={formatCount(overview.data?.totalRuns)} hint={
          overview.data ? `采样于 ${formatRelative(overview.data.capturedAtEpochMilli)}` : undefined
        } />
        <StatCard
          label="等待审批"
          value={formatCount(overview.data?.awaitingApproval)}
          tone={overview.data && overview.data.awaitingApproval > 0 ? 'warn' : 'neutral'}
          hint="需要人工决策"
        />
        <StatCard label="运行中" value={formatCount(overview.data?.countsByStatus?.Running ?? 0)} tone="good" />
        <StatCard
          label="失败"
          value={formatCount(overview.data?.countsByStatus?.Failed ?? 0)}
          tone={(overview.data?.countsByStatus?.Failed ?? 0) > 0 ? 'danger' : 'neutral'}
        />
      </div>

      <ErrorBanner error={overview.error} context="读取 Run 总览" />

      <Panel
        title="Run 目录"
        description="按 (更新时间, RunId) 稳定倒序排列，使用 keyset 游标翻页"
        actions={
          <>
            <Button
              variant="secondary"
              disabled={cursorStack.length <= 1}
              onClick={() => setPaging({ key: filterKey, stack: cursorStack.slice(0, -1) })}
            >
              <ChevronLeft className="h-3 w-3" /> 上一页
            </Button>
            <Button
              variant="secondary"
              disabled={!runs.data?.hasMore || !runs.data?.nextCursor}
              onClick={() =>
                setPaging({ key: filterKey, stack: [...cursorStack, runs.data?.nextCursor ?? undefined] })
              }
            >
              下一页 <ChevronRight className="h-3 w-3" />
            </Button>
          </>
        }
      >
        <div className="mb-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]">
          <TextInput
            label="租户"
            value={tenantDraft}
            onChange={setTenantDraft}
            placeholder="留空表示跨租户"
          />
          <TextInput
            label="Agent"
            value={agentDraft}
            onChange={setAgentDraft}
            placeholder="留空表示全部 Agent"
          />
          <label className="flex items-center gap-2 pb-1.5 text-xs text-slate-300">
            <input
              type="checkbox"
              checked={awaitingOnly}
              onChange={(event) => url.set({ runAwaiting: encodeFlag(event.target.checked) })}
              className={`rounded border-slate-700 bg-slate-950 ${FOCUS_RING}`}
            />
            仅显示待审批
          </label>
        </div>

        <div className="mb-3 flex flex-wrap items-center gap-1.5">
          <Filter className="h-3 w-3 text-slate-500" />
          {STATUS_OPTIONS.map((status) => (
            <button
              key={status}
              type="button"
              aria-pressed={statuses.includes(status)}
              onClick={() => toggleStatus(status)}
              className={`rounded-md px-2 py-0.5 text-xs ring-1 ring-inset transition ${FOCUS_RING} ${
                statuses.includes(status)
                  ? runStatusTone(status)
                  : 'text-slate-500 ring-slate-800 hover:text-slate-300'
              }`}
            >
              {status}
            </button>
          ))}
        </div>

        <ErrorBanner error={runs.error} context="查询 Run 目录" />

        {runs.isPending ? (
          <LoadingRows rows={6} />
        ) : items.length === 0 ? (
          <EmptyState
            title="没有匹配的 Run"
            reason="若刚接入框架，请确认宿主装配的是 PostgreSQL 的 RunDirectory 适配器；仅使用内存 Store 时该列表将始终为空。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-slate-500">
                <tr className="border-b border-slate-800">
                  <th className="py-2 pr-3 font-medium">Run</th>
                  <th className="py-2 pr-3 font-medium">Agent</th>
                  <th className="py-2 pr-3 font-medium">状态</th>
                  <th className="py-2 pr-3 font-medium">步数</th>
                  <th className="py-2 pr-3 font-medium">Token</th>
                  <th className="py-2 pr-3 font-medium">费用</th>
                  <th className="py-2 pr-3 font-medium">租户</th>
                  <th className="py-2 pr-3 font-medium">更新</th>
                </tr>
              </thead>
              <tbody>
                {items.map((run) => (
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
                    className={`cursor-pointer border-b border-slate-900 transition hover:bg-slate-900/60 ${FOCUS_RING} ${
                      run.runId === selectedRunId ? 'bg-slate-900' : ''
                    }`}
                  >
                    <td className="py-2 pr-3">
                      <CopyableId value={run.runId} label="Run ID" truncate={8} />
                    </td>
                    <td className="py-2 pr-3 text-slate-300">{run.agentId}</td>
                    <td className="py-2 pr-3">
                      <Badge className={runStatusTone(run.status)}>{run.status}</Badge>
                      {run.awaitingApproval && (
                        <Badge className="ml-1 text-amber-300 bg-amber-500/10 ring-amber-500/30">
                          审批
                        </Badge>
                      )}
                    </td>
                    <td className="py-2 pr-3 tabular-nums text-slate-400">{run.steps}</td>
                    <td className="py-2 pr-3 tabular-nums text-slate-400">
                      {formatCount(run.usage.totalTokens)}
                    </td>
                    <td className="py-2 pr-3 tabular-nums text-slate-400">{run.usage.estimatedCost}</td>
                    <td className="py-2 pr-3 text-slate-500">{run.tenantId ?? '—'}</td>
                    <td className="py-2 pr-3 text-slate-500">{formatRelative(run.updatedAtEpochMilli)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {selected && (
        <>
          <RunDetail run={selected} capabilities={capabilities} />
          {capabilities?.runEventStream && <RunEventStream key={selected.runId} run={selected} />}
        </>
      )}
    </div>
  );
}

/** 单个 Run 的元数据详情与外部 trace 深链。 */
function RunDetail({
  run,
  capabilities,
}: {
  run: RunSummaryView;
  capabilities: AdminCapabilitiesView | undefined;
}) {
  const traceUrl = capabilities ? langfuseTraceUrl(capabilities.observability, run.runId) : null;
  const traceId = capabilities ? traceIdForRun(capabilities.observability, run.runId) : null;

  return (
    <Panel
      title="Run 详情"
      description="仅元数据；输入、输出与工具参数需在业务侧按各自授权规则查看"
      actions={
        traceUrl ? (
          <a
            href={traceUrl}
            target="_blank"
            rel="noreferrer"
            className={`inline-flex items-center gap-1 rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800 ${FOCUS_RING}`}
          >
            在 Langfuse 中查看 trace <ExternalLink className="h-3 w-3" />
          </a>
        ) : (
          <Badge>未配置 Langfuse 深链</Badge>
        )
      }
    >
      <div className="grid gap-x-8 md:grid-cols-3">
        <div className="divide-y divide-slate-900">
          <Field label="Run ID">
            <CopyableId value={run.runId} label="Run ID" className="text-slate-200" />
          </Field>
          <Field label="Trace ID">
            {traceId ? (
              <CopyableId value={traceId} label="trace ID" truncate={12} className="text-slate-200" />
            ) : (
              '—'
            )}
          </Field>
          <Field label="Session">
            <CopyableId value={run.sessionId} label="Session ID" className="text-slate-200" />
          </Field>
          <Field label="Thread">{run.threadId ?? '—'}</Field>
          <Field label="Agent">{run.agentId}</Field>
          <Field label="状态">
            <Badge className={runStatusTone(run.status)}>{run.status}</Badge>
          </Field>
        </div>
        <div className="divide-y divide-slate-900">
          <Field label="模型调用">{formatCount(run.usage.modelCalls)}</Field>
          <Field label="工具调用">{formatCount(run.usage.toolCalls)}</Field>
          <Field label="输入 Token">{formatCount(run.usage.inputTokens)}</Field>
          <Field label="输出 Token">{formatCount(run.usage.outputTokens)}</Field>
          <Field label="缓存命中 Token">{formatCount(run.usage.cachedInputTokens)}</Field>
          <Field label="推理 Token">{formatCount(run.usage.reasoningOutputTokens)}</Field>
          <Field label="预估费用">{run.usage.estimatedCost}</Field>
        </div>
        <div className="divide-y divide-slate-900">
          <Field label="租户 / 用户">
            {run.tenantId ?? '—'} / {run.userId ?? '—'}
          </Field>
          <Field label="状态版本">{run.stateVersion}</Field>
          <Field label="最后事件序号">{run.lastEventSequence}</Field>
          <Field label="创建时间">{formatInstant(run.createdAtEpochMilli)}</Field>
          <Field label="更新时间">{formatInstant(run.updatedAtEpochMilli)}</Field>
        </div>
      </div>

      {run.awaitingApproval ? (
        <div className="mt-4 rounded-lg border border-amber-900/60 bg-amber-950/20 px-3 py-2 text-xs">
          <div className="font-medium text-amber-200">等待人工审批</div>
          <div className="mt-1 text-amber-300/80">
            工具 <Mono>{run.pendingApprovalToolName ?? '未知'}</Mono>，风险等级{' '}
            <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">
              {run.pendingApprovalRisk ?? '未知'}
            </Badge>
          </div>
          <div className="mt-1 text-amber-300/60">
            审批决定通过业务 Run API 提交，管理台不代替业务主体做出决策。
          </div>
        </div>
      ) : (
        <div className="mt-4 inline-flex items-center gap-1.5 text-xs text-slate-500">
          <CheckCircle2 className="h-3.5 w-3.5" /> 无待处理审批
        </div>
      )}
    </Panel>
  );
}

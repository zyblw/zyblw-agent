'use client';

/**
 * 安全态势：工具治理、审批强度与待审批积压。
 *
 * 这个页面不引入新的数据源，而是把已有的配置快照和 Run 目录按"安全"这一视角重新组织：值班人员想知道的
 * 是"现在有哪些工具能被调用、审批有多严、有多少 Run 卡在人工决策上"，而这三件事分散在配置页和运行页里。
 *
 * 它刻意只读。工具白名单和审批策略的修改入口只有配置页一处，避免同一项配置存在两个可写界面后，
 * 两处校验逻辑逐渐分叉。
 */

import React from 'react';
import { ArrowRight, Lock, ShieldAlert, ShieldCheck, Unlock } from 'lucide-react';
import { useRuns, useRuntimeConfig } from '@/lib/queries';
import { formatCount, formatRelative, runStatusTone } from '@/lib/format';
import {
  Badge,
  CopyableId,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Mono,
  Panel,
  StatCard,
} from '@/components/ui';

/** 从配置快照中按 key 取生效值。 */
function effective(fields: { key: string; effectiveValue: string }[] | undefined, key: string): string {
  return fields?.find((field) => field.key === key)?.effectiveValue ?? '—';
}

/** 把后端渲染的工具集合字符串解析回列表；`(空)` 是后端对空集合的稳定表示。 */
function parseToolSet(value: string): string[] {
  if (!value || value === '(空)' || value === '—') return [];
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

export function SecurityArtifacts({ onOpenConfig }: { onOpenConfig: () => void }) {
  const config = useRuntimeConfig();
  const approvals = useRuns({ awaitingApproval: true, limit: 25 });

  const fields = config.data?.fields;
  const allowed = parseToolSet(effective(fields, 'toolAllowedTools'));
  const denied = parseToolSet(effective(fields, 'toolDeniedTools'));
  const approvalPolicy = effective(fields, 'toolApprovalPolicy');
  const sensitiveOverrides =
    fields?.filter((field) => field.sensitive && field.overrideValue !== null && field.overrideValue !== undefined) ??
    [];

  return (
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="审批策略"
          value={<span className="text-base">{approvalPolicy}</span>}
          tone={approvalPolicy === 'never' ? 'danger' : approvalPolicy === 'always' ? 'good' : 'warn'}
          hint={
            approvalPolicy === 'never'
              ? '所有工具调用无需人工确认'
              : approvalPolicy === 'always'
                ? '每次工具调用都要求确认'
                : '按工具风险等级决定'
          }
        />
        <StatCard
          label="允许的工具"
          value={allowed.length === 0 ? '全部禁用' : formatCount(allowed.length)}
          tone={allowed.length === 0 ? 'danger' : 'neutral'}
          hint="白名单为空即禁用全部（fail-closed）"
        />
        <StatCard label="显式拒绝" value={formatCount(denied.length)} hint="黑名单优先于白名单" />
        <StatCard
          label="敏感项已被覆盖"
          value={formatCount(sensitiveOverrides.length)}
          tone={sensitiveOverrides.length > 0 ? 'warn' : 'good'}
          hint="偏离部署基线的安全配置"
        />
      </div>

      <ErrorBanner error={config.error} context="读取安全配置" />

      {sensitiveOverrides.length > 0 && (
        <div className="rounded-lg border border-amber-900/60 bg-amber-950/20 px-3 py-2 text-xs">
          <div className="flex items-center gap-1.5 font-medium text-amber-200">
            <ShieldAlert className="h-3.5 w-3.5" /> 有安全敏感配置正在以覆盖值运行
          </div>
          <div className="mt-1.5 space-y-1">
            {sensitiveOverrides.map((field) => (
              <div key={field.key} className="flex items-center gap-2 text-[11px] text-amber-300/80">
                <Mono>{field.key}</Mono>
                <span className="text-amber-300/50">{field.baselineValue}</span>
                <ArrowRight className="h-2.5 w-2.5" />
                <span className="font-medium">{field.effectiveValue}</span>
              </div>
            ))}
          </div>
          <button
            type="button"
            onClick={onOpenConfig}
            className={`mt-2 rounded text-[11px] text-amber-200 underline ${FOCUS_RING}`}
          >
            在配置页查看并修改
          </button>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="工具治理" description="决定模型能调用哪些工具；黑名单优先于白名单">
          {config.isPending ? (
            <LoadingRows rows={4} />
          ) : (
            <>
              <div className="mb-3">
                <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-emerald-300">
                  <Unlock className="h-3.5 w-3.5" /> 白名单（{allowed.length}）
                </div>
                {allowed.length === 0 ? (
                  <div className="rounded border border-rose-900/50 bg-rose-950/20 px-2 py-1.5 text-[11px] text-rose-300">
                    白名单为空：当前配置禁用全部工具。这是 fail-closed 语义下的合法状态，但如果并非有意，
                    模型将无法调用任何工具。
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-1">
                    {allowed.map((tool) => (
                      <Badge key={tool} className="text-emerald-300 bg-emerald-500/10 ring-emerald-500/30">
                        {tool}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>

              <div>
                <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-rose-300">
                  <Lock className="h-3.5 w-3.5" /> 黑名单（{denied.length}）
                </div>
                {denied.length === 0 ? (
                  <div className="text-[11px] text-slate-600">没有显式拒绝的工具。</div>
                ) : (
                  <div className="flex flex-wrap gap-1">
                    {denied.map((tool) => (
                      <Badge key={tool} className="text-rose-300 bg-rose-500/10 ring-rose-500/30">
                        {tool}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>

              <div className="mt-4 divide-y divide-slate-900">
                <Field label="单 Run 工具调用上限">{effective(fields, 'toolMaxCallsPerRun')}</Field>
                <Field label="单步工具调用上限">{effective(fields, 'toolMaxCallsPerStep')}</Field>
                <Field label="并行度">{effective(fields, 'toolMaxParallelism')}</Field>
                <Field label="单次执行超时">{effective(fields, 'toolDefaultTimeoutMillis')} ms</Field>
                <Field label="结果字节上限">{effective(fields, 'toolMaxResultBytes')}</Field>
              </div>
            </>
          )}
        </Panel>

        <Panel
          title="待审批积压"
          description="等待人工决策的 Run；审批决定通过业务 Run API 提交"
        >
          <ErrorBanner error={approvals.error} context="读取待审批 Run" />
          {approvals.isPending ? (
            <LoadingRows rows={4} />
          ) : (approvals.data?.items.length ?? 0) === 0 ? (
            <EmptyState
              title="没有等待审批的 Run"
              reason={
                approvalPolicy === 'never'
                  ? '当前审批策略为 never，因此不会产生审批请求。'
                  : undefined
              }
            />
          ) : (
            <div className="space-y-1.5">
              {approvals.data?.items.map((run) => (
                <div
                  key={run.runId}
                  className="rounded-lg border border-amber-900/40 bg-amber-950/10 px-3 py-2 text-xs"
                >
                  <div className="flex items-center gap-2">
                    <CopyableId value={run.runId} label="Run ID" truncate={8} />
                    <span className="text-slate-500">{run.agentId}</span>
                    <Badge className={runStatusTone(run.status)}>{run.status}</Badge>
                    <span className="ml-auto text-[11px] text-slate-500">
                      {formatRelative(run.updatedAtEpochMilli)}
                    </span>
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 text-[11px] text-slate-500">
                    <span>
                      工具 <Mono className="text-amber-300">{run.pendingApprovalToolName ?? '未知'}</Mono>
                    </span>
                    <Badge className="text-amber-300 bg-amber-500/10 ring-amber-500/30">
                      风险 {run.pendingApprovalRisk ?? '未知'}
                    </Badge>
                    <span>租户 {run.tenantId ?? '—'}</span>
                    <span>已执行 {run.steps} 步</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </div>

      <Panel title="管理面授权模型" description="管理接口一律要求显式 scope，缺失即拒绝">
        <div className="grid gap-3 md:grid-cols-3 text-xs">
          <ScopeCard
            scope="agent:admin:read"
            tone="text-sky-300 bg-sky-500/10 ring-sky-500/30"
            description="只读聚合：Run 目录、队列积压、有效配置快照、评测趋势。泄漏面最小，可以发给值班与监控。"
          />
          <ScopeCard
            scope="agent:admin:write"
            tone="text-amber-300 bg-amber-500/10 ring-amber-500/30"
            description="改变部署行为：工具白名单、审批策略、死信重排、索引退役。蕴含读权限，因为改配置前必须先看到当前配置。"
          />
          <ScopeCard
            scope="agent:admin:debug"
            tone="text-rose-300 bg-rose-500/10 ring-rose-500/30"
            description="产生真实 Provider 费用：检索沙盒与文档摄入。不被写权限蕴含，必须单独授予。"
          />
        </div>
        <p className="mt-3 flex items-start gap-1.5 text-[11px] text-slate-600">
          <ShieldCheck className="mt-0.5 h-3 w-3 shrink-0" />
          管理面看到的是整个部署而不是单个 Run 的所有者视角，因此不能复用业务侧「归属即可读」的规则。
          身份由宿主的认证层解析，框架不自带认证中间件。
        </p>
      </Panel>
    </div>
  );
}

function ScopeCard({ scope, tone, description }: { scope: string; tone: string; description: string }) {
  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-2">
      <Badge className={tone}>{scope}</Badge>
      <p className="mt-1.5 text-[11px] leading-relaxed text-slate-400">{description}</p>
    </div>
  );
}

'use client';

/**
 * 低敏运行检查：组合对照、ModelCall 账本和挂起/审批主体 (Clean Light Theme)。
 * 不展示 prompt、工具参数或记忆正文。
 */

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { formatCount, formatInstant } from '@/lib/format';
import { useUrlState } from '@/lib/urlState';
import { Badge, CopyableId, ErrorBanner, Panel, StatCard, TextInput } from '@/components/ui';
import {
  AlertTriangle,
  CheckCircle2,
  Layers,
  ShieldAlert,
  GitCompare,
} from 'lucide-react';
import type {
  AdminCompositionView,
  AdminModelCallView,
  AdminSuspensionView,
  CapabilityRef,
  CompositionFacetView,
} from '@/types/admin';

export function InspectPanel() {
  const { config } = useConnection();
  const url = useUrlState();
  const runId = url.get('runId') ?? '';

  const handleRunIdChange = (val: string) => {
    url.set({ runId: val.trim() || undefined });
  };

  const enabled = runId.trim().length >= 8;

  const composition = useQuery({
    queryKey: ['admin', 'composition', runId],
    queryFn: () => adminApi.runComposition(config, runId.trim()),
    enabled,
  });
  const calls = useQuery({
    queryKey: ['admin', 'model-calls', runId],
    queryFn: () => adminApi.runModelCalls(config, runId.trim()),
    enabled,
  });
  const suspension = useQuery({
    queryKey: ['admin', 'suspension', runId],
    queryFn: () => adminApi.runSuspension(config, runId.trim()),
    enabled,
  });

  // 统计概览
  const totalCalls = calls.data?.length ?? 0;
  const totalInputTokens = calls.data?.reduce((acc, c) => acc + c.inputTokens, 0) ?? 0;
  const totalOutputTokens = calls.data?.reduce((acc, c) => acc + c.outputTokens, 0) ?? 0;
  const isDrifted = composition.data?.composition.driftKind && composition.data.composition.driftKind !== 'compatible';
  const isSuspended = Boolean(suspension.data?.record);

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <Panel
        title="运行穿透与架构检查"
        description="只读低敏权威投影：装配组合冻结与现场漂移比对、不可篡改的模型调用账本、以及人机协同等待原因。"
      >
        <div className="max-w-xl">
          <TextInput
            label="Run ID"
            value={runId}
            onChange={handleRunIdChange}
            placeholder="输入或从 Run 列表透视传入 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
          />
        </div>
      </Panel>

      {enabled && (calls.data || composition.data || suspension.data) && (
        <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
          <StatCard
            label="模型调用总计"
            value={formatCount(totalCalls)}
            hint="不可篡改的账本记录"
          />
          <StatCard
            label="累积 Token 消耗"
            value={formatCount(totalInputTokens + totalOutputTokens)}
            hint={`输入 ${formatCount(totalInputTokens)} / 输出 ${formatCount(totalOutputTokens)}`}
          />
          <StatCard
            label="架构漂移评估"
            value={composition.data?.composition.driftKind ?? (composition.data?.composition.live ? '无漂移' : '未装配现场')}
            tone={isDrifted ? 'warn' : 'good'}
            hint="冻结状态 vs 现场进程"
          />
          <StatCard
            label="执行协同状态"
            value={isSuspended ? (suspension.data?.record?.approval ? '等待审批' : '协同挂起') : '正常推进/已结束'}
            tone={isSuspended ? 'warn' : 'neutral'}
            hint={suspension.data?.record ? `原因: ${suspension.data.record.kind}` : '无阻塞挂起'}
          />
        </div>
      )}

      {composition.error && <ErrorBanner error={composition.error} context="读取组合对照" />}
      {calls.error && <ErrorBanner error={calls.error} context="读取模型调用账本" />}
      {suspension.error && <ErrorBanner error={suspension.error} context="读取挂起投影" />}

      {composition.data && <CompositionCard view={composition.data} />}
      {calls.data && <ModelCallTable views={calls.data} />}
      {suspension.data && <SuspensionCard view={suspension.data} />}
    </div>
  );
}

function CompositionCard({ view }: { view: AdminCompositionView }) {
  const { frozen, live, driftKind, changedFields } = view.composition;
  const driftTone =
    driftKind === 'incompatible'
      ? 'text-rose-700 bg-rose-50 ring-rose-600/20'
      : driftKind === 'requires-revalidation'
        ? 'text-amber-800 bg-amber-50 ring-amber-600/25'
        : driftKind
          ? 'text-emerald-700 bg-emerald-50 ring-emerald-600/20'
          : 'text-slate-600 bg-slate-100 ring-slate-200';

  const hasSecurityDrift = changedFields.some((f) => f.securityRelevant);

  return (
    <Panel
      title="智能体装配与环境组合比对"
      description="创建时不可变冻结的装配指纹与当前进程现场对照；绝不臆造不存在的兼容性承诺。"
    >
      <div className="mb-4 flex flex-wrap items-center gap-2 text-xs">
        <span className="text-slate-500 font-medium flex items-center gap-1.5">
          <GitCompare className="h-3.5 w-3.5 text-indigo-600" /> 漂移判定:
        </span>
        <Badge className={driftTone}>{driftKind ?? '现场组合未装配'}</Badge>
        {changedFields.map((field) => (
          <Badge
            key={`${field.kind}:${field.field}`}
            className={
              field.securityRelevant
                ? 'text-rose-700 bg-rose-50 ring-rose-600/20'
                : 'text-slate-700 bg-slate-100 ring-slate-200'
            }
          >
            {field.securityRelevant && '⚠️ '}
            {field.kind}/{field.field}
          </Badge>
        ))}
      </div>

      {hasSecurityDrift && (
        <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50/80 px-4 py-3 text-xs text-amber-900 flex items-start gap-2.5 shadow-xs">
          <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5 text-amber-600" />
          <div>
            <div className="font-semibold text-amber-950">检测到安全关键属性变更</div>
            <div className="text-amber-800 mt-0.5">
              当前进程的环境标识、权限范围或工具校验和与 Run 创建时存在不一致，恢复或重试将受到严格隔离。
            </div>
          </div>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <FacetCard title="冻结装配 (Run 创建时)" facet={frozen} isFrozen />
        {live ? (
          <FacetCard title="现场装配 (当前运行进程)" facet={live} isFrozen={false} />
        ) : (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-200 bg-slate-50/50 p-6 text-center text-xs text-slate-500">
            <Layers className="h-8 w-8 text-slate-400 mb-2" />
            <p className="font-medium text-slate-700">宿主未装配现场组合读出口</p>
            <p className="mt-1">无法判定当前进程是否已经发生版本或环境漂移。</p>
          </div>
        )}
      </div>
    </Panel>
  );
}

function FacetCard({
  title,
  facet,
  isFrozen,
}: {
  title: string;
  facet: CompositionFacetView;
  isFrozen: boolean;
}) {
  return (
    <div
      className={`rounded-xl border p-4.5 transition shadow-xs ${
        isFrozen
          ? 'border-indigo-200 bg-indigo-50/25'
          : 'border-slate-200 bg-white'
      }`}
    >
      <div className="mb-3.5 flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wider text-slate-600 flex items-center gap-1.5">
          {isFrozen ? <CheckCircle2 className="h-3.5 w-3.5 text-indigo-600" /> : <Layers className="h-3.5 w-3.5 text-emerald-600" />}
          {title}
        </span>
        <Badge className="font-mono text-[10px] bg-white ring-slate-200 text-slate-700">
          指纹: {facet.fingerprintPrefix}...
        </Badge>
      </div>
      <dl className="grid grid-cols-2 gap-3 text-xs">
        <Item label="Profile" value={facet.profileId} />
        <Item label="模型定义" value={facet.modelRefPrefix} />
        <Item label="快照策略" value={facet.capturePolicy} />
        <Item label="执行环境" value={facet.environmentId} />
        <Item label="权限指纹" value={facet.permissionFingerprintPrefix} />
        <Item label="路由策略" value={facet.modelRoutingFingerprintPrefix ?? '—'} />
        <Item label="计价版本" value={facet.modelPricingFingerprintPrefix ?? '—'} />
      </dl>
      <div className="mt-3.5 pt-3 border-t border-slate-200/80">
        <div className="text-[11px] font-medium text-slate-500 mb-1.5">注册来源能力 (Sources)</div>
        <CapabilityList refs={facet.sourceIds} empty="无来源" />
      </div>
      <div className="mt-2.5">
        <div className="text-[11px] font-medium text-slate-500 mb-1.5">扩展插件 (Extensions)</div>
        <CapabilityList refs={facet.extensionIds} empty="无扩展" />
      </div>
    </div>
  );
}

function CapabilityList({ refs, empty }: { refs: CapabilityRef[]; empty: string }) {
  if (refs.length === 0) {
    return <div className="text-[11px] text-slate-400 italic">{empty}</div>;
  }
  return (
    <div className="flex flex-wrap gap-1">
      {refs.map((ref) => (
        <Badge key={`${ref.kind}:${ref.id}@${ref.version}`} className="bg-white ring-slate-200 text-slate-700 text-[10px]">
          {ref.kind}:{ref.id}@{ref.version}
        </Badge>
      ))}
    </div>
  );
}

function ModelCallTable({ views }: { views: AdminModelCallView[] }) {
  const statusTone = (status: string) => {
    switch (status.toLowerCase()) {
      case 'succeeded':
      case 'success':
        return 'text-emerald-700 bg-emerald-50 ring-emerald-600/20';
      case 'failed':
      case 'error':
        return 'text-rose-700 bg-rose-50 ring-rose-600/20';
      case 'dispatched':
      case 'running':
        return 'text-blue-700 bg-blue-50 ring-blue-600/20';
      case 'prepared':
      case 'pending':
        return 'text-amber-800 bg-amber-50 ring-amber-600/25';
      default:
        return 'text-slate-600 bg-slate-100 ring-slate-200';
    }
  };

  return (
    <Panel
      title="模型调用账本 (Model Call Ledger)"
      description="不可篡改的结算记录：绝不回显 Prompt 正文；仅记录状态、供应商、模型名、Token 消耗与路由决策。"
    >
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="w-full text-left text-xs text-slate-700">
          <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
            <tr>
              <th className="py-2.5 px-3 font-semibold">状态</th>
              <th className="py-2.5 px-3 font-semibold">Provider / 模型</th>
              <th className="py-2.5 px-3 font-semibold">Profile</th>
              <th className="py-2.5 px-3 font-semibold text-right">输入 Token</th>
              <th className="py-2.5 px-3 font-semibold text-right">输出 Token</th>
              <th className="py-2.5 px-3 font-semibold">供应商请求号</th>
              <th className="py-2.5 px-3 font-semibold">指纹前缀</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {views.map((row) => (
              <tr key={row.requestId} className="hover:bg-slate-50 transition">
                <td className="py-2.5 px-3">
                  <Badge className={statusTone(row.status)}>{row.status}</Badge>
                </td>
                <td className="py-2.5 px-3 font-medium text-slate-900">
                  <span className="text-slate-400">{row.provider}/</span>
                  {row.model}
                </td>
                <td className="py-2.5 px-3 text-slate-500">{row.requestedProfile ?? '—'}</td>
                <td className="py-2.5 px-3 tabular-nums font-mono text-slate-800 text-right">
                  {formatCount(row.inputTokens)}
                </td>
                <td className="py-2.5 px-3 tabular-nums font-mono text-slate-800 text-right">
                  {formatCount(row.outputTokens)}
                </td>
                <td className="py-2.5 px-3 font-mono text-[11px] text-slate-600">
                  {row.providerRequestId ? (
                    <CopyableId value={row.providerRequestId} label="Provider Request ID" truncate={12} />
                  ) : (
                    '—'
                  )}
                </td>
                <td className="py-2.5 px-3 font-mono text-[11px] text-slate-500">{row.fingerprintPrefix}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}

function SuspensionCard({ view }: { view: AdminSuspensionView }) {
  const record = view.record;
  if (!record) {
    return (
      <Panel title="协同挂起与人机介入 (HITL)" description="当前任务无等待外部输入。">
        <div className="flex items-center gap-2 text-xs text-slate-600 py-2">
          <CheckCircle2 className="h-4 w-4 text-emerald-600" />
          <span>Run 处于自主流转状态，未受审批、信号、人工输入或计时器阻塞。</span>
        </div>
      </Panel>
    );
  }

  const approval = record.approval;
  return (
    <Panel
      title="协同挂起与人机介入 (HITL)"
      description="等待原因与到期决议；遵循隐私安全设计，工具参数和 Prompt 文本绝不泄露在此视图中。"
    >
      <div className="mb-4 flex items-center gap-2">
        <Badge className="bg-amber-50 text-amber-800 ring-amber-600/25">
          {record.kind === 'approval' ? '待人工审批授权' : `挂起等待: ${record.kind}`}
        </Badge>
        <span className="text-xs text-slate-600">
          超时决议: <span className="font-mono font-medium text-slate-900">{record.expiryOutcome}</span>
        </span>
      </div>

      <dl className="grid grid-cols-2 gap-3 text-xs md:grid-cols-4">
        <Item label="等待类型" value={record.kind} />
        <Item label="超时动作" value={record.expiryOutcome} />
        <Item label="挂起时刻" value={formatInstant(record.createdAtEpochMilli)} />
        <Item label="截至期限" value={record.deadlineEpochMilli ? formatInstant(record.deadlineEpochMilli) : '无截止期限'} />
      </dl>

      {approval && (
        <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50/80 p-4 shadow-xs">
          <div className="mb-3 flex items-center gap-2 text-amber-950 text-xs font-semibold">
            <ShieldAlert className="h-4 w-4 text-amber-600" />
            待决副作用授权详情 (Approval Subject)
          </div>
          <dl className="grid grid-cols-2 gap-3 text-xs md:grid-cols-3">
            <Item label="审批单号" value={approval.approvalId} />
            <Item label="请求工具" value={approval.toolName} />
            <Item label="风险等级" value={approval.risk} />
            <Item label="授权环境" value={approval.environmentId} />
            <Item label="权限指纹" value={approval.permissionFingerprintPrefix} />
            <Item label="目标主体指纹" value={approval.subjectFingerprintPrefix ?? '—'} />
          </dl>
        </div>
      )}
    </Panel>
  );
}

function Item({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[11px] uppercase tracking-wider text-slate-400 font-medium">{label}</dt>
      <dd className="font-mono text-slate-900 font-medium truncate mt-0.5">{value}</dd>
    </div>
  );
}

'use client';

/**
 * 低敏运行检查：组合对照、ModelCall 账本和挂起/审批主体。
 * 不展示 prompt、工具参数或记忆正文。
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { formatInstant } from '@/lib/format';
import { Badge, ErrorBanner, Panel, TextInput } from '@/components/ui';
import type {
  AdminCompositionView,
  AdminModelCallView,
  AdminSuspensionView,
  CapabilityRef,
  CompositionFacetView,
} from '@/types/admin';

export function InspectPanel() {
  const { config } = useConnection();
  const [runId, setRunId] = useState('');
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

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <Panel title="运行检查" description="只读低敏投影：冻结/现场组合对照、模型调用账本、挂起原因。">
        <TextInput
          label="Run ID"
          value={runId}
          onChange={setRunId}
          placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
        />
      </Panel>
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
      ? 'text-rose-300 bg-rose-500/10 ring-rose-500/30'
      : driftKind === 'requires-revalidation'
        ? 'text-amber-300 bg-amber-500/10 ring-amber-500/30'
        : driftKind
          ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30'
          : 'text-slate-400 bg-slate-500/10 ring-slate-500/30';

  return (
    <Panel
      title="组合对照"
      description="创建时冻结的装配与当前进程对照；没有现场读出口时不臆造“无漂移”。"
    >
      <div className="mb-3 flex flex-wrap items-center gap-2 text-xs">
        <Badge className={driftTone}>{driftKind ?? '现场组合未装配'}</Badge>
        {changedFields.map((field) => (
          <Badge
            key={`${field.kind}:${field.field}`}
            className={
              field.securityRelevant
                ? 'text-rose-300 bg-rose-500/10 ring-rose-500/30'
                : 'text-slate-300 bg-slate-500/10 ring-slate-500/30'
            }
          >
            {field.kind}/{field.field}
          </Badge>
        ))}
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <FacetCard title="冻结" facet={frozen} />
        {live ? (
          <FacetCard title="现场" facet={live} />
        ) : (
          <div className="rounded-lg border border-dashed border-slate-800 px-3 py-4 text-xs text-slate-500">
            宿主未装配现场组合读出口，因此无法判断当前进程是否已经漂移。
          </div>
        )}
      </div>
    </Panel>
  );
}

function FacetCard({ title, facet }: { title: string; facet: CompositionFacetView }) {
  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/40 px-3 py-3">
      <div className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">{title}</div>
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Item label="fingerprint" value={facet.fingerprintPrefix} />
        <Item label="profile" value={facet.profileId} />
        <Item label="modelRef" value={facet.modelRefPrefix} />
        <Item label="capture" value={facet.capturePolicy} />
        <Item label="environment" value={facet.environmentId} />
        <Item label="permissions" value={facet.permissionFingerprintPrefix} />
        <Item label="routing" value={facet.modelRoutingFingerprintPrefix ?? '—'} />
        <Item label="pricing" value={facet.modelPricingFingerprintPrefix ?? '—'} />
      </dl>
      <CapabilityList refs={facet.sourceIds} empty="无来源" />
      <CapabilityList refs={facet.extensionIds} empty="无扩展" />
    </div>
  );
}

function CapabilityList({ refs, empty }: { refs: CapabilityRef[]; empty: string }) {
  if (refs.length === 0) {
    return <div className="mt-3 text-[11px] text-slate-600">{empty}</div>;
  }
  return (
    <div className="mt-3 flex flex-wrap gap-1">
      {refs.map((ref) => (
        <Badge key={`${ref.kind}:${ref.id}@${ref.version}`}>
          {ref.kind}:{ref.id}@{ref.version}
        </Badge>
      ))}
    </div>
  );
}

function ModelCallTable({ views }: { views: AdminModelCallView[] }) {
  return (
    <Panel title="模型调用账本" description="不含 prompt；只含状态、Provider、路由和 token 计数。">
      <table className="w-full text-left text-xs text-slate-300">
        <thead>
          <tr className="border-b border-slate-800 text-slate-500">
            <th className="py-2">status</th>
            <th>provider</th>
            <th>model</th>
            <th>route</th>
            <th>in</th>
            <th>out</th>
            <th>fingerprint</th>
          </tr>
        </thead>
        <tbody>
          {views.map((row) => (
            <tr key={row.requestId} className="border-b border-slate-900">
              <td className="py-2">{row.status}</td>
              <td>{row.provider}</td>
              <td>{row.model}</td>
              <td>{row.requestedProfile ?? '—'}</td>
              <td>{row.inputTokens}</td>
              <td>{row.outputTokens}</td>
              <td>{row.fingerprintPrefix}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

function SuspensionCard({ view }: { view: AdminSuspensionView }) {
  const record = view.record;
  if (!record) {
    return (
      <Panel title="挂起" description="当前没有等待外部输入。">
        <div className="text-xs text-slate-500">Run 不在等待审批、信号、人工输入或计时器。</div>
      </Panel>
    );
  }
  const approval = record.approval;
  return (
    <Panel title="挂起" description="等待原因与到期决议；工具参数和 prompt 不会出现在这里。">
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Item label="kind" value={record.kind} />
        <Item label="expiry" value={record.expiryOutcome} />
        <Item label="created" value={formatInstant(record.createdAtEpochMilli)} />
        <Item label="deadline" value={formatInstant(record.deadlineEpochMilli)} />
      </dl>
      {approval && (
        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <Item label="approvalId" value={approval.approvalId} />
          <Item label="tool" value={approval.toolName} />
          <Item label="risk" value={approval.risk} />
          <Item label="environment" value={approval.environmentId} />
          <Item label="permissions" value={approval.permissionFingerprintPrefix} />
          <Item label="subject" value={approval.subjectFingerprintPrefix ?? '—'} />
        </dl>
      )}
    </Panel>
  );
}

function Item({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="font-mono text-slate-200">{value}</dd>
    </div>
  );
}

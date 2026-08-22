'use client';

/**
 * 低敏运行检查：组合指纹、ModelCall 账本和审批主体。
 * 不展示 prompt、工具参数或记忆正文。
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { Badge, ErrorBanner, Panel, TextInput } from '@/components/ui';
import type { AdminApprovalSubjectView, AdminCompositionView, AdminModelCallView } from '@/types/admin';

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
  const approval = useQuery({
    queryKey: ['admin', 'approval', runId],
    queryFn: () => adminApi.runApproval(config, runId.trim()),
    enabled,
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <Panel title="运行检查" description="只读低敏投影：组合指纹、模型调用账本、待审批主体。">
        <TextInput
          label="Run ID"
          value={runId}
          onChange={setRunId}
          placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
        />
      </Panel>
      {composition.error && <ErrorBanner error={composition.error} context="读取组合指纹" />}
      {calls.error && <ErrorBanner error={calls.error} context="读取模型调用账本" />}
      {approval.error && <ErrorBanner error={approval.error} context="读取审批主体" />}
      {composition.data && <CompositionCard view={composition.data} />}
      {calls.data && <ModelCallTable views={calls.data} />}
      {approval.data && <ApprovalCard view={approval.data} />}
    </div>
  );
}

function CompositionCard({ view }: { view: AdminCompositionView }) {
  return (
    <Panel title="组合指纹" description="创建 Run 时冻结的装配摘要；漂移会 fail-closed。">
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Item label="fingerprint" value={view.fingerprintPrefix} />
        <Item label="profile" value={view.profileId} />
        <Item label="modelRef" value={view.modelRefPrefix} />
        <Item label="capture" value={view.capturePolicy} />
        <Item label="environment" value={view.environmentId} />
        <Item label="permissions" value={view.permissionFingerprintPrefix} />
      </dl>
      <div className="mt-3 flex flex-wrap gap-1">
        {view.sourceIds.map((id) => (
          <Badge key={id}>{id}</Badge>
        ))}
      </div>
    </Panel>
  );
}

function ModelCallTable({ views }: { views: AdminModelCallView[] }) {
  return (
    <Panel title="模型调用账本" description="不含 prompt；只含状态、Provider 和 token 计数。">
      <table className="w-full text-left text-xs text-slate-300">
        <thead>
          <tr className="border-b border-slate-800 text-slate-500">
            <th className="py-2">status</th>
            <th>provider</th>
            <th>model</th>
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

function ApprovalCard({ view }: { view: AdminApprovalSubjectView }) {
  return (
    <Panel title="审批主体" description="环境与权限摘要；工具参数不会出现在这里。">
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Item label="approvalId" value={view.approvalId} />
        <Item label="tool" value={view.toolName} />
        <Item label="risk" value={view.risk} />
        <Item label="environment" value={view.environmentId} />
        <Item label="permissions" value={view.permissionFingerprintPrefix} />
        <Item label="subject" value={view.subjectFingerprintPrefix ?? '—'} />
      </dl>
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

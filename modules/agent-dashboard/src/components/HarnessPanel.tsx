'use client';

/**
 * 低敏 Harness 检查：Goal 状态、预算剩余和 Todo 状态计数。
 * 不展示 Goal 全文、Skill 正文或 Artifact bytes。
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { Badge, ErrorBanner, Panel, TextInput } from '@/components/ui';
import type { AdminHarnessView } from '@/types/admin';

export function HarnessPanel() {
  const { config } = useConnection();
  const [goalId, setGoalId] = useState('');
  const enabled = goalId.trim().length >= 8;

  const goal = useQuery({
    queryKey: ['admin', 'harness', goalId],
    queryFn: () => adminApi.harnessGoal(config, goalId.trim()),
    enabled,
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <Panel title="任务支架" description="只读低敏投影：Goal 状态、剩余预算、Todo 状态。">
        <TextInput
          label="Goal ID"
          value={goalId}
          onChange={setGoalId}
          placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
        />
      </Panel>
      {goal.error && <ErrorBanner error={goal.error} context="读取 Harness Goal" />}
      {goal.data && <HarnessCard view={goal.data} />}
    </div>
  );
}

function HarnessCard({ view }: { view: AdminHarnessView }) {
  return (
    <Panel title="Goal 预算" description="剩余额度来自任务级账本，不是单次 Run 的软提示。">
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Item label="status" value={view.status} />
        <Item label="objective" value={view.objectivePrefix} />
        <Item label="revision" value={String(view.revision)} />
        <Item label="runId" value={view.runId ?? '—'} />
        <Item label="remaining runs" value={view.remainingRuns?.toString() ?? '—'} />
        <Item label="remaining model calls" value={view.remainingModelCalls?.toString() ?? '—'} />
        <Item label="remaining tool calls" value={view.remainingToolCalls?.toString() ?? '—'} />
        <Item label="remaining tokens" value={view.remainingTotalTokens?.toString() ?? '—'} />
      </dl>
      <div className="mt-3 flex flex-wrap gap-1">
        {view.todoStatuses.length === 0 && <Badge>no todos</Badge>}
        {view.todoStatuses.map((status, index) => (
          <Badge key={`${status}-${index}`}>{status}</Badge>
        ))}
      </div>
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

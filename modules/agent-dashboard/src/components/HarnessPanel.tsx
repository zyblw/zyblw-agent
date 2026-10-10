'use client';

/**
 * 低敏 Harness 检查：Goal 状态、预算剩余和 Todo 状态计数 (Clean Light Theme)。
 * 不展示 Goal 全文、Skill 正文或 Artifact bytes。
 */

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { useUrlState } from '@/lib/urlState';
import { formatCount } from '@/lib/format';
import { Badge, ErrorBanner, Panel, StatCard, TextInput } from '@/components/ui';
import { ListTodo } from 'lucide-react';
import type { AdminHarnessView } from '@/types/admin';

export function HarnessPanel() {
  const { config } = useConnection();
  const url = useUrlState();
  const goalId = url.get('goalId') ?? '';

  const handleGoalIdChange = (val: string) => {
    url.set({ goalId: val.trim() || undefined });
  };

  const enabled = goalId.trim().length >= 8;

  const goal = useQuery({
    queryKey: ['admin', 'harness', goalId],
    queryFn: () => adminApi.harnessGoal(config, goalId.trim()),
    enabled,
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <Panel
        title="任务支架与目标编排 (Harness Orchestration)"
        description="只读低敏权威投影：高层目标 (Goal) 状态、任务级硬预算剩余度量与分步 Todo 状态分布。"
      >
        <div className="max-w-xl">
          <TextInput
            label="Goal ID"
            value={goalId}
            onChange={handleGoalIdChange}
            placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
          />
        </div>
      </Panel>

      {goal.error && <ErrorBanner error={goal.error} context="读取 Harness Goal" />}
      {goal.data && <HarnessCard view={goal.data} />}
    </div>
  );
}

function HarnessCard({ view }: { view: AdminHarnessView }) {
  const completedTodos = view.todoStatuses.filter((s) => s.toLowerCase().includes('done') || s.toLowerCase().includes('completed')).length;
  const inProgressTodos = view.todoStatuses.filter((s) => s.toLowerCase().includes('progress') || s.toLowerCase().includes('running')).length;
  const pendingTodos = view.todoStatuses.length - completedTodos - inProgressTodos;

  const statusTone = (status: string) => {
    switch (status.toLowerCase()) {
      case 'completed':
      case 'achieved':
      case 'success':
        return 'text-emerald-700 bg-emerald-50 ring-emerald-600/20';
      case 'failed':
      case 'exhausted':
        return 'text-rose-700 bg-rose-50 ring-rose-600/20';
      case 'active':
      case 'running':
        return 'text-blue-700 bg-blue-50 ring-blue-600/20';
      default:
        return 'text-amber-800 bg-amber-50 ring-amber-600/25';
    }
  };

  return (
    <div className="space-y-4">
      {/* 核心指标卡片 */}
      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        <StatCard
          label="剩余 Run 预算"
          value={view.remainingRuns !== null && view.remainingRuns !== undefined ? formatCount(view.remainingRuns) : '无上限'}
          hint="任务级总循环额度"
        />
        <StatCard
          label="剩余模型调用"
          value={view.remainingModelCalls !== null && view.remainingModelCalls !== undefined ? formatCount(view.remainingModelCalls) : '无上限'}
          hint="LLM Invocations"
        />
        <StatCard
          label="剩余工具调用"
          value={view.remainingToolCalls !== null && view.remainingToolCalls !== undefined ? formatCount(view.remainingToolCalls) : '无上限'}
          hint="Tool Execution Budget"
        />
        <StatCard
          label="剩余 Token 额度"
          value={view.remainingTotalTokens !== null && view.remainingTotalTokens !== undefined ? formatCount(view.remainingTotalTokens) : '无上限'}
          hint="硬预算保障"
        />
      </div>

      <Panel
        title="目标状态与预算核算"
        description="硬预算来自任务级持久账本，在调度器中强校验，不是单次 Run 的软提示词约束。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2 text-xs">
          <span className="text-slate-500 font-medium">目标状态:</span>
          <Badge className={statusTone(view.status)}>{view.status}</Badge>
          <span className="text-slate-500">· 修订版本: <span className="font-mono text-slate-900 font-medium">r{view.revision}</span></span>
        </div>

        <dl className="grid grid-cols-1 gap-3.5 text-xs md:grid-cols-2 lg:grid-cols-4">
          <Item label="目标标识" value={view.goalId} />
          <Item label="当前 Run 绑定" value={view.runId ?? '无活跃绑定'} />
          <Item label="规划 Plan ID" value={view.planId ?? '—'} />
          <Item label="目标摘要前缀" value={view.objectivePrefix} />
        </dl>

        {/* Todo 进度分布 */}
        <div className="mt-5 rounded-xl border border-slate-200 bg-slate-50/60 p-4">
          <div className="mb-3 flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
              <ListTodo className="h-4 w-4 text-indigo-600" />
              任务拆解计划进度 (Todo Items: {view.todoStatuses.length})
            </span>
            <div className="flex items-center gap-3 text-xs font-medium">
              <span className="text-emerald-700">已完成: {completedTodos}</span>
              <span className="text-blue-700">进行中: {inProgressTodos}</span>
              <span className="text-slate-500">待推进: {pendingTodos}</span>
            </div>
          </div>

          {view.todoStatuses.length === 0 ? (
            <div className="text-xs text-slate-500 italic py-2">当前目标尚未生成分步任务项清单。</div>
          ) : (
            <div className="flex flex-wrap gap-2 pt-1">
              {view.todoStatuses.map((status, index) => {
                const isDone = status.toLowerCase().includes('done') || status.toLowerCase().includes('completed');
                const isDoing = status.toLowerCase().includes('progress') || status.toLowerCase().includes('running');
                return (
                  <Badge
                    key={`${status}-${index}`}
                    className={`text-xs ${
                      isDone
                        ? 'bg-emerald-50 text-emerald-700 ring-emerald-600/20'
                        : isDoing
                          ? 'bg-blue-50 text-blue-700 ring-blue-600/20'
                          : 'bg-white text-slate-700 ring-slate-200 shadow-xs'
                    }`}
                  >
                    #{index + 1} {status}
                  </Badge>
                );
              })}
            </div>
          )}
        </div>
      </Panel>
    </div>
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

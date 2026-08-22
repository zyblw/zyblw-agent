'use client';

/**
 * 记忆治理：复用业务 `/api/v1/memory/export`，只导出当前认证主体自己的记忆。
 * 审计动作是 Export；本页不展示他人 scope，也不提供跨租户 dump。
 */

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { Badge, ErrorBanner, Panel } from '@/components/ui';
import type { MemoryView } from '@/types/admin';

export function MemoryPanel() {
  const { config } = useConnection();
  const exported = useQuery({
    queryKey: ['memory', 'export', config.baseUrl],
    queryFn: () => adminApi.exportMemory(config),
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-4 p-6">
      <Panel title="记忆治理" description="有界导出自己的长期记忆；审计不含正文或原始查询。">
        <p className="text-xs text-slate-500">
          此面板调用业务 Memory API，而不是管理面跨租户投影。身份来自当前 Bearer / 宿主会话。
        </p>
      </Panel>
      {exported.error && <ErrorBanner error={exported.error} context="导出记忆" />}
      {exported.data && <MemoryTable items={exported.data.items} nextCursor={exported.data.nextCursor} />}
    </div>
  );
}

function MemoryTable({ items, nextCursor }: { items: MemoryView[]; nextCursor?: string | null }) {
  return (
    <Panel title="导出页" description="只含当前页；下一页用 nextCursor 继续，避免无界 dump。">
      <table className="w-full text-left text-xs text-slate-300">
        <thead>
          <tr className="border-b border-slate-800 text-slate-500">
            <th className="py-2">key</th>
            <th>kind</th>
            <th>sensitivity</th>
            <th>version</th>
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <tr key={row.key} className="border-b border-slate-900">
              <td className="py-2 font-mono">{row.key}</td>
              <td>{row.kind}</td>
              <td>{row.sensitivity}</td>
              <td>{row.version}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {nextCursor && <Badge>next {nextCursor}</Badge>}
    </Panel>
  );
}

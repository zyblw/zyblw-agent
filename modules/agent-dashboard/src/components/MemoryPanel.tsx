'use client';

/**
 * 记忆治理：复用业务 `/api/v1/memory/export`，只导出当前认证主体自己的记忆 (Clean Light Theme)。
 * 审计动作是 Export；本页不展示他人 scope，也不提供跨租户 dump。
 */

import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/lib/adminClient';
import { useConnection } from '@/lib/connection';
import { formatInstant, formatCount } from '@/lib/format';
import { Badge, CopyableId, ErrorBanner, Panel, StatCard, TextInput } from '@/components/ui';

export function MemoryPanel() {
  const { config } = useConnection();
  const [filterKey, setFilterKey] = useState('');

  const exported = useQuery({
    queryKey: ['memory', 'export', config.baseUrl],
    queryFn: () => adminApi.exportMemory(config),
  });

  const items = useMemo(() => exported.data?.items ?? [], [exported.data?.items]);

  const filteredItems = useMemo(() => {
    if (!filterKey.trim()) return items;
    const q = filterKey.toLowerCase().trim();
    return items.filter(
      (m) => m.key.toLowerCase().includes(q) || m.kind.toLowerCase().includes(q) || m.sensitivity.toLowerCase().includes(q)
    );
  }, [items, filterKey]);

  // 分类统计
  const countBySensitivity = useMemo(() => {
    const acc: Record<string, number> = {};
    for (const m of items) {
      acc[m.sensitivity] = (acc[m.sensitivity] ?? 0) + 1;
    }
    return acc;
  }, [items]);

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <Panel
        title="长期记忆治理与合规视图 (Memory Governance)"
        description="基于当前认证主体有界导出长期记忆元数据；遵循安全零信任原则，审计动作仅限 Export，绝不展示他人 scope 或进行跨租户 Dump。"
      >
        <p className="text-xs text-slate-500 leading-relaxed">
          此面板调用业务安全隔离的 Memory API，由当前 Bearer 或宿主会话严格约束隔离。不向不可信前端输出敏感记忆正文。
        </p>
      </Panel>

      {exported.data && (
        <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
          <StatCard
            label="当前导出记忆项"
            value={formatCount(items.length)}
            hint="有界导出当前分页"
          />
          <StatCard
            label="公开/内部级记忆"
            value={formatCount((countBySensitivity['public'] ?? 0) + (countBySensitivity['internal'] ?? 0))}
            tone="good"
            hint="标准语义记忆"
          />
          <StatCard
            label="机密/受限级记忆"
            value={formatCount((countBySensitivity['confidential'] ?? 0) + (countBySensitivity['restricted'] ?? 0))}
            tone={(countBySensitivity['restricted'] ?? 0) > 0 ? 'warn' : 'neutral'}
            hint="严格访问控制标记"
          />
          <StatCard
            label="隔离级别"
            value="租户+主体"
            hint="严格会话绑定"
          />
        </div>
      )}

      {exported.error && <ErrorBanner error={exported.error} context="导出记忆" />}

      {exported.data && (
        <Panel
          title="记忆条目清单"
          description="按 key 层级索引；下一页通过游标继续，避免无界全量 Dump。"
          actions={
            <div className="w-56">
              <TextInput
                value={filterKey}
                onChange={setFilterKey}
                placeholder="搜索 Key 或分类..."
              />
            </div>
          }
        >
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full text-left text-xs text-slate-700">
              <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                <tr>
                  <th className="py-2.5 px-3 font-semibold">Memory Key</th>
                  <th className="py-2.5 px-3 font-semibold">类别 (Kind)</th>
                  <th className="py-2.5 px-3 font-semibold">敏感度 (Sensitivity)</th>
                  <th className="py-2.5 px-3 font-semibold">版本</th>
                  <th className="py-2.5 px-3 font-semibold">更新时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filteredItems.map((row) => (
                  <tr key={row.key} className="hover:bg-slate-50 transition">
                    <td className="py-2.5 px-3 font-mono text-slate-900 font-medium">
                      <CopyableId value={row.key} label="Memory Key" truncate={32} />
                    </td>
                    <td className="py-2.5 px-3">
                      <Badge className="bg-slate-100 text-slate-700 ring-slate-200 font-mono">
                        {row.kind}
                      </Badge>
                    </td>
                    <td className="py-2.5 px-3">
                      <Badge className={sensitivityTone(row.sensitivity)}>
                        {row.sensitivity}
                      </Badge>
                    </td>
                    <td className="py-2.5 px-3 font-mono text-slate-600">v{row.version}</td>
                    <td className="py-2.5 px-3 text-slate-500">{formatInstant(row.updatedAtEpochMilli)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {filteredItems.length === 0 && (
            <div className="py-8 text-center text-xs text-slate-500 italic">
              没有找到匹配的记忆条目。
            </div>
          )}

          {exported.data.nextCursor && (
            <div className="mt-4 pt-3 border-t border-slate-100 flex items-center justify-between text-xs text-slate-500">
              <span>下一页游标: <span className="font-mono text-slate-700 font-medium">{exported.data.nextCursor}</span></span>
              <Badge className="bg-slate-100 text-slate-700 ring-slate-200">存在更多数据</Badge>
            </div>
          )}
        </Panel>
      )}
    </div>
  );
}

function sensitivityTone(sensitivity: string): string {
  switch (sensitivity.toLowerCase()) {
    case 'restricted':
      return 'text-rose-700 bg-rose-50 ring-rose-600/20';
    case 'confidential':
      return 'text-amber-800 bg-amber-50 ring-amber-600/25';
    case 'internal':
      return 'text-blue-700 bg-blue-50 ring-blue-600/20';
    case 'public':
      return 'text-emerald-700 bg-emerald-50 ring-emerald-600/20';
    default:
      return 'text-slate-600 bg-slate-100 ring-slate-200';
  }
}

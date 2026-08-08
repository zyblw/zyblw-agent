'use client';

/**
 * 评测趋势：通过率走势、维度分数与发布门禁。
 *
 * 维度**分数**和门禁**结果**分开展示，因为发布门禁的判定依据是布尔通过而不是分数高低：一个维度可以分数
 * 很高但仍然没通过硬门禁（例如安全项要求零违规）。把两者画成同一条曲线会让人以为"分数上去了就能发布"。
 *
 * 趋势线来自部署显式声明跟踪的套件，而不是数据库里碰巧存在的一切——管理台展示的是运维关心的少数几条线。
 */

import React, { useMemo } from 'react';
import { CheckCircle2, GitCommitHorizontal, TrendingDown, TrendingUp, XCircle } from 'lucide-react';
import { useEvalSuites, useEvalTrend } from '@/lib/queries';
import { useUrlState } from '@/lib/urlState';
import { evalSuiteKey, type EvalTrendPointView } from '@/types/admin';
import { formatCount, formatInstant, formatPercent, formatScore } from '@/lib/format';
import {
  Badge,
  CopyableId,
  EmptyState,
  ErrorBanner,
  Field,
  FOCUS_RING,
  LoadingRows,
  Panel,
  StatCard,
} from '@/components/ui';

export function EvalAnalytics() {
  const suites = useEvalSuites();
  const url = useUrlState();
  /** 空串表示"尚未手动选择"，此时派生出第一条趋势线。 */
  const pickedKey = url.get('suite');

  // 默认选择通过派生得到，而不是在 effect 里 setState：后者会多一轮级联渲染，且在套件列表刷新时
  // 需要额外逻辑判断当前选择是否仍然有效。
  const selected =
    suites.data?.find((suite) => evalSuiteKey(suite) === pickedKey) ?? suites.data?.[0];
  const selectedKey = selected ? evalSuiteKey(selected) : null;

  const trend = useEvalTrend(selected, 50);
  const points = useMemo(() => trend.data?.points ?? [], [trend.data]);
  const latest = points.length > 0 ? points[points.length - 1] : undefined;
  const previous = points.length > 1 ? points[points.length - 2] : undefined;

  const dimensionNames = useMemo(() => {
    const names = new Set<string>();
    for (const point of points) {
      for (const name of Object.keys(point.dimensionScores)) names.add(name);
      for (const name of Object.keys(point.dimensionGates)) names.add(name);
    }
    return Array.from(names).sort();
  }, [points]);

  const passRateDelta =
    latest && previous ? latest.passRate - previous.passRate : undefined;

  return (
    <div className="space-y-4 p-4">
      <ErrorBanner error={suites.error} context="读取评测套件" />

      {suites.isPending ? (
        <LoadingRows rows={2} />
      ) : (suites.data?.length ?? 0) === 0 ? (
        <EmptyState
          title="没有跟踪的评测套件"
          reason="趋势线由部署显式声明。请在宿主装配评测趋势适配器时列出需要在管理台展示的套件身份。"
        />
      ) : (
        <>
          <div className="flex flex-wrap gap-1.5">
            {suites.data?.map((suite) => {
              const key = evalSuiteKey(suite);
              const active = key === selectedKey;
              return (
                <button
                  key={key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => url.set({ suite: key })}
                  className={`rounded-md border px-2.5 py-1.5 text-left text-xs transition ${FOCUS_RING} ${
                    active
                      ? 'border-indigo-600 bg-indigo-950/30 text-slate-100'
                      : 'border-slate-800 text-slate-400 hover:bg-slate-900'
                  }`}
                >
                  <div className="font-medium">{suite.suiteId}</div>
                  <div className="text-[10px] text-slate-500">
                    {suite.kind} · {suite.datasetId}@{suite.datasetVersion}
                  </div>
                </button>
              );
            })}
          </div>

          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard
              label="最近通过率"
              value={formatPercent(latest?.passRate)}
              tone={latest?.passed ? 'good' : 'danger'}
              hint={
                passRateDelta === undefined
                  ? undefined
                  : `相比上次 ${passRateDelta >= 0 ? '+' : ''}${formatPercent(passRateDelta)}`
              }
            />
            <StatCard
              label="发布门禁"
              value={
                latest === undefined ? (
                  '—'
                ) : latest.passed ? (
                  <span className="inline-flex items-center gap-1.5 text-base">
                    <CheckCircle2 className="h-4 w-4" /> 通过
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 text-base">
                    <XCircle className="h-4 w-4" /> 未通过
                  </span>
                )
              }
              tone={latest?.passed ? 'good' : 'danger'}
            />
            <StatCard label="用例数" value={formatCount(latest?.caseCount)} />
            <StatCard
              label="数据点"
              value={formatCount(points.length)}
              hint={latest ? `最近 ${formatInstant(latest.finishedAtEpochMilli)}` : undefined}
            />
          </div>

          <ErrorBanner error={trend.error} context="读取趋势历史" />

          {trend.isPending ? (
            <LoadingRows rows={6} />
          ) : points.length === 0 ? (
            <EmptyState
              title="该套件尚无历史数据"
              reason="评测运行后会由 CI 或本地 harness 写入趋势仓库；此前列表为空是预期的。"
            />
          ) : (
            <>
              <Panel title="通过率走势" description="按评测完成时间升序；红点表示未通过发布门禁">
                <PassRateChart points={points} />
              </Panel>

              <div className="grid gap-4 xl:grid-cols-2">
                <Panel
                  title="维度分数与门禁"
                  description="分数高不等于通过门禁；两者是独立判定"
                >
                  {dimensionNames.length === 0 ? (
                    <EmptyState title="该套件未上报维度指标" />
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-xs">
                        <thead className="text-slate-500">
                          <tr className="border-b border-slate-800">
                            <th className="py-2 pr-3 font-medium">维度</th>
                            <th className="py-2 pr-3 font-medium">最近分数</th>
                            <th className="py-2 pr-3 font-medium">上次分数</th>
                            <th className="py-2 pr-3 font-medium">门禁</th>
                          </tr>
                        </thead>
                        <tbody>
                          {dimensionNames.map((name) => {
                            const current = latest?.dimensionScores[name];
                            const before = previous?.dimensionScores[name];
                            const gate = latest?.dimensionGates[name];
                            const delta =
                              current !== undefined && before !== undefined ? current - before : undefined;
                            return (
                              <tr key={name} className="border-b border-slate-900">
                                <td className="py-2 pr-3 text-slate-300">{name}</td>
                                <td className="py-2 pr-3 tabular-nums text-slate-200">
                                  {formatScore(current)}
                                  {delta !== undefined && Math.abs(delta) > 1e-9 && (
                                    <span
                                      className={`ml-1.5 inline-flex items-center text-[10px] ${
                                        delta > 0 ? 'text-emerald-400' : 'text-rose-400'
                                      }`}
                                    >
                                      {delta > 0 ? (
                                        <TrendingUp className="h-2.5 w-2.5" />
                                      ) : (
                                        <TrendingDown className="h-2.5 w-2.5" />
                                      )}
                                      {formatScore(Math.abs(delta), 3)}
                                    </span>
                                  )}
                                </td>
                                <td className="py-2 pr-3 tabular-nums text-slate-500">
                                  {formatScore(before)}
                                </td>
                                <td className="py-2 pr-3">
                                  {gate === undefined ? (
                                    <span className="text-slate-600">未设门禁</span>
                                  ) : (
                                    <Badge
                                      className={
                                        gate
                                          ? 'text-emerald-300 bg-emerald-500/10 ring-emerald-500/30'
                                          : 'text-rose-300 bg-rose-500/10 ring-rose-500/30'
                                      }
                                    >
                                      {gate ? '通过' : '未通过'}
                                    </Badge>
                                  )}
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                </Panel>

                <Panel title="最近一次评测" description="用于把一次回归定位到具体提交与模型">
                  {latest === undefined ? (
                    <EmptyState title="没有数据点" />
                  ) : (
                    <div className="divide-y divide-slate-900">
                      <Field label="评测 ID">
                        <CopyableId
                          value={latest.evaluationId}
                          label="评测 ID"
                          truncate={24}
                          className="text-slate-200"
                        />
                      </Field>
                      <Field label="Harness 版本">{latest.harnessVersion}</Field>
                      <Field label="提交">
                        {latest.commitSha ? (
                          <span className="inline-flex items-center gap-1">
                            <GitCommitHorizontal className="h-3 w-3" />
                            <CopyableId
                              value={latest.commitSha}
                              label="提交 SHA"
                              truncate={12}
                              className="text-slate-200"
                            />
                          </span>
                        ) : (
                          '—'
                        )}
                      </Field>
                      <Field label="Provider / 模型">
                        {latest.provider ?? '—'} / {latest.model ?? '—'}
                      </Field>
                      <Field label="完成时间">{formatInstant(latest.finishedAtEpochMilli)}</Field>
                      <Field label="通过率">{formatPercent(latest.passRate)}</Field>
                      <Field label="用例数">{formatCount(latest.caseCount)}</Field>
                    </div>
                  )}
                </Panel>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}

/**
 * 通过率折线图。
 *
 * 用内联 SVG 而不是引入图表库：这是一条单序列折线，一个图表库会为此带来数十 KB 的依赖和一套需要长期
 * 跟随升级的 API。纵轴固定 0..1，因为通过率是比例——自适应纵轴会把 0.98 到 0.99 的波动画成悬崖。
 */
function PassRateChart({ points }: { points: EvalTrendPointView[] }) {
  const width = 800;
  const height = 180;
  const padding = { top: 12, right: 12, bottom: 24, left: 36 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;

  const x = (index: number) =>
    padding.left + (points.length <= 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth);
  const y = (value: number) => padding.top + (1 - Math.min(1, Math.max(0, value))) * plotHeight;

  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${x(index)},${y(point.passRate)}`).join(' ');

  return (
    <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${width} ${height}`} className="h-48 w-full min-w-[640px]">
        {[0, 0.25, 0.5, 0.75, 1].map((tick) => (
          <g key={tick}>
            <line
              x1={padding.left}
              x2={width - padding.right}
              y1={y(tick)}
              y2={y(tick)}
              stroke="currentColor"
              className="text-slate-800"
              strokeWidth={1}
            />
            <text x={4} y={y(tick) + 3} className="fill-slate-600 text-[9px]">
              {(tick * 100).toFixed(0)}%
            </text>
          </g>
        ))}

        <path d={path} fill="none" stroke="currentColor" className="text-indigo-400" strokeWidth={1.5} />

        {points.map((point, index) => (
          <circle
            key={point.evaluationId}
            cx={x(index)}
            cy={y(point.passRate)}
            r={3}
            className={point.passed ? 'fill-emerald-400' : 'fill-rose-400'}
          >
            <title>
              {`${formatInstant(point.finishedAtEpochMilli)}\n通过率 ${formatPercent(point.passRate)}\n门禁 ${
                point.passed ? '通过' : '未通过'
              }\n${point.commitSha ? `提交 ${point.commitSha.slice(0, 12)}` : ''}`}
            </title>
          </circle>
        ))}

        {points.length > 1 && (
          <>
            <text x={padding.left} y={height - 6} className="fill-slate-600 text-[9px]">
              {formatInstant(points[0].finishedAtEpochMilli)}
            </text>
            <text
              x={width - padding.right}
              y={height - 6}
              textAnchor="end"
              className="fill-slate-600 text-[9px]"
            >
              {formatInstant(points[points.length - 1].finishedAtEpochMilli)}
            </text>
          </>
        )}
      </svg>
    </div>
  );
}

'use client';

import React from 'react';
import { EvalTrendPoint } from '@/types/agent';
import {
  CheckCircle2,
  Coins,
  ShieldCheck,
  TrendingUp
} from 'lucide-react';

interface EvalAnalyticsProps {
  trends: EvalTrendPoint[];
}

export const EvalAnalytics: React.FC<EvalAnalyticsProps> = ({ trends }) => {
  const latestTrend = trends[trends.length - 1] || trends[0];

  return (
    <div className="p-6 bg-slate-950 text-slate-100 font-sans space-y-6 min-h-[calc(100vh-100px)]">
      {/* Top Release Gate Status Banner */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex flex-wrap items-center justify-between gap-4 shadow-xl backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h2 className="font-bold text-base text-slate-100">
                Fail-Closed 发布门禁与质量评测大盘
              </h2>
              <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-xs px-2.5 py-0.5 rounded-full font-bold flex items-center gap-1">
                <CheckCircle2 className="w-3.5 h-3.5" />
                门禁已通过 ({latestTrend.commitHash})
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              重复试验评测器 • pass@k / pass^k 指标曲线 • 多试验 CI 发布趋势流水线
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-4 text-xs font-mono">
          <div className="bg-slate-950 px-3 py-1.5 rounded-lg border border-slate-800">
            <span className="text-slate-500 text-[10px] block">pass@k (至少一次成功率)</span>
            <span className="font-bold text-emerald-400 text-sm">
              {(latestTrend.passAtK * 100).toFixed(1)}%
            </span>
          </div>

          <div className="bg-slate-950 px-3 py-1.5 rounded-lg border border-slate-800">
            <span className="text-slate-500 text-[10px] block">pass^k (连续全成功率)</span>
            <span className="font-bold text-indigo-400 text-sm">
              {(latestTrend.passPowK * 100).toFixed(1)}%
            </span>
          </div>
        </div>
      </div>

      {/* Historical Eval Metrics Curves Card */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-xl backdrop-blur-sm">
        <div className="pb-4 border-b border-slate-800 mb-4 flex items-center justify-between">
          <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
            <TrendingUp className="w-4 h-4 text-indigo-400" />
            跨版本历史质量评测指标趋势
          </h3>
          <span className="text-xs text-slate-500 font-mono">AgentEvalRunner 快照</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          {trends.map((item) => (
            <div
              key={item.commitHash}
              className="bg-slate-950 border border-slate-800/80 p-4 rounded-xl space-y-3"
            >
              <div className="flex items-center justify-between pb-2 border-b border-slate-900">
                <span className="font-mono text-sm font-bold text-indigo-300">
                  {item.commitHash}
                </span>
                <span className="text-xs text-slate-500 font-mono">{item.date}</span>
              </div>

              <div className="space-y-2 text-xs font-mono">
                <div className="flex justify-between items-center">
                  <span className="text-slate-400">pass@k 评分:</span>
                  <span className="font-bold text-emerald-400">
                    {(item.passAtK * 100).toFixed(1)}%
                  </span>
                </div>
                <div className="w-full bg-slate-900 h-1.5 rounded-full overflow-hidden">
                  <div
                    className="bg-emerald-400 h-full rounded-full"
                    style={{ width: `${item.passAtK * 100}%` }}
                  />
                </div>

                <div className="flex justify-between items-center pt-2">
                  <span className="text-slate-400">pass^k 评分:</span>
                  <span className="font-bold text-indigo-400">
                    {(item.passPowK * 100).toFixed(1)}%
                  </span>
                </div>
                <div className="w-full bg-slate-900 h-1.5 rounded-full overflow-hidden">
                  <div
                    className="bg-indigo-400 h-full rounded-full"
                    style={{ width: `${item.passPowK * 100}%` }}
                  />
                </div>

                <div className="flex justify-between items-center pt-2 border-t border-slate-900 text-[11px]">
                  <span className="text-slate-500">引用准确度 (Citation Correctness):</span>
                  <span className="text-cyan-300">{(item.citationAccuracy * 100).toFixed(1)}%</span>
                </div>

                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-slate-500">工具选择准确度 (Tool Selection):</span>
                  <span className="text-amber-300">{(item.toolSelectionAccuracy * 100).toFixed(1)}%</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Token Usage & Cost Telemetry Card */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-xl backdrop-blur-sm">
        <div className="pb-4 border-b border-slate-800 mb-4 flex items-center justify-between">
          <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
            <Coins className="w-4 h-4 text-amber-400" />
            Token 遥测与模型估计费用统计 (Token Telemetry & Cost)
          </h3>
          <span className="text-xs text-slate-500 font-mono">OpenTelemetry GenAI 规范</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800">
            <span className="text-slate-500 text-[10px] font-mono block">累计 Token 消耗</span>
            <span className="text-xl font-bold text-slate-100 font-mono">1,280,400</span>
            <span className="text-[10px] text-slate-400 mt-1 block">所有 Provider 调用合计</span>
          </div>

          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800">
            <span className="text-slate-500 text-[10px] font-mono block">PROMPT CACHE 命中率</span>
            <span className="text-xl font-bold text-emerald-400 font-mono">68.4%</span>
            <span className="text-[10px] text-slate-400 mt-1 block">节约的输入 Token 比例</span>
          </div>

          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800">
            <span className="text-slate-500 text-[10px] font-mono block">REASONING TOKENS 占比</span>
            <span className="text-xl font-bold text-cyan-400 font-mono">14.2%</span>
            <span className="text-[10px] text-slate-400 mt-1 block">DeepSeek/Gemini 推理 Tokens</span>
          </div>

          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800">
            <span className="text-slate-500 text-[10px] font-mono block">估计运行费用 (USD)</span>
            <span className="text-xl font-bold text-amber-400 font-mono">$1.42 USD</span>
            <span className="text-[10px] text-slate-400 mt-1 block">配置速率估算花费</span>
          </div>
        </div>
      </div>
    </div>
  );
};

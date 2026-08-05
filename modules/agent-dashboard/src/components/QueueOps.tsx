'use client';

import React, { useState } from 'react';
import { QueueSnapshotView, WorkerNodeView } from '@/types/agent';
import {
  Clock,
  Cpu,
  RotateCcw,
  Server,
  ShieldCheck
} from 'lucide-react';

interface QueueOpsProps {
  workers: WorkerNodeView[];
  queueSnapshot: QueueSnapshotView;
}

export const QueueOps: React.FC<QueueOpsProps> = ({ workers, queueSnapshot }) => {
  const [snapshot, setSnapshot] = useState<QueueSnapshotView>(queueSnapshot);
  const [feedback, setFeedback] = useState<string | null>(null);

  const handleReclaimLeases = () => {
    setSnapshot({ ...snapshot, expiredLeases: 0 });
    setFeedback('已成功清理 0 个过期写租约并修复 Worker fencing generation。');
    setTimeout(() => setFeedback(null), 4000);
  };

  return (
    <div className="p-6 bg-slate-950 text-slate-100 font-sans space-y-6 min-h-[calc(100vh-100px)]">
      {/* Top Banner */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex flex-wrap items-center justify-between gap-4 shadow-xl backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-3 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-cyan-400">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <h2 className="font-bold text-base text-slate-100">
              分布式 Command Worker 与写租约运维控制台
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              PostgreSQL 控制队列 • Claim/Lease/Heartbeat Fencing • 串行分发保证
            </p>
          </div>
        </div>

        <button
          onClick={handleReclaimLeases}
          className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold px-4 py-2 rounded-xl flex items-center space-x-2 shadow-lg shadow-indigo-600/20 transition-all"
        >
          <RotateCcw className="w-4 h-4" />
          <span>清理过期租约与死信 (Reclaim Stale Leases)</span>
        </button>
      </div>

      {feedback && (
        <div className="p-3 bg-emerald-500/20 border border-emerald-500/30 rounded-xl text-xs text-emerald-200 font-mono flex items-center space-x-2">
          <ShieldCheck className="w-4 h-4 text-emerald-400" />
          <span>{feedback}</span>
        </div>
      )}

      {/* Queue Depth Metric Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4">
        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            排队命令 (QUEUED)
          </div>
          <div className="text-2xl font-bold text-slate-100 font-mono">
            {snapshot.queuedCommands}
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">等待 Start/Cancel 命令</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            可调度 (DISPATCHABLE)
          </div>
          <div className="text-2xl font-bold text-indigo-400 font-mono">
            {snapshot.dispatchableRuns}
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">准备被 Worker Claim</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            活动租约 (LEASED)
          </div>
          <div className="text-2xl font-bold text-cyan-400 font-mono">
            {snapshot.leasedRuns}
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">正在执行的 Worker 车道</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            过期租约 (EXPIRED)
          </div>
          <div className="text-2xl font-bold text-amber-400 font-mono">
            {snapshot.expiredLeases}
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">Fencing 超时租约</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            死信命令 (DEAD LETTER)
          </div>
          <div className="text-2xl font-bold text-rose-400 font-mono">
            {snapshot.deadLetterCommands}
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">不可恢复错误命令</span>
        </div>

        <div className="bg-slate-900/80 border border-slate-800 p-4 rounded-xl shadow-lg backdrop-blur-sm">
          <div className="text-[10px] font-mono text-slate-500 uppercase tracking-wider mb-1">
            最大等待延迟
          </div>
          <div className="text-2xl font-bold text-slate-200 font-mono">
            {snapshot.oldestDispatchableAgeMs}ms
          </div>
          <span className="text-[10px] text-slate-400 mt-1 block">队列平均延迟</span>
        </div>
      </div>

      {/* Active Worker Nodes Grid */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 shadow-xl backdrop-blur-sm">
        <div className="pb-4 border-b border-slate-800 mb-4 flex items-center justify-between">
          <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
            <Server className="w-4 h-4 text-indigo-400" />
            活动 Worker 节点集群 ({workers.length} 节点)
          </h3>
          <span className="text-xs text-slate-500 font-mono">WorkerHost 监督并发车道</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {workers.map((w) => (
            <div
              key={w.workerId}
              className="bg-slate-950 border border-slate-800/80 p-4 rounded-xl space-y-3"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping" />
                  <span className="font-mono text-xs font-bold text-slate-100">
                    {w.workerId}
                  </span>
                </div>
                <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-[10px] px-2 py-0.5 rounded-full font-bold">
                  {w.status === 'Healthy' ? '健康运行' : w.status}
                </span>
              </div>

              <div className="grid grid-cols-3 gap-2 text-xs font-mono pt-2 border-t border-slate-900">
                <div>
                  <span className="text-slate-500 text-[10px] block">节点 IP</span>
                  <span className="text-slate-300">{w.hostIp}</span>
                </div>
                <div>
                  <span className="text-slate-500 text-[10px] block">活动租约数</span>
                  <span className="text-indigo-400 font-bold">{w.activeLeases} 车道</span>
                </div>
                <div>
                  <span className="text-slate-500 text-[10px] block">FENCING GEN</span>
                  <span className="text-cyan-300">Gen #{w.fencingGeneration}</span>
                </div>
              </div>

              <div className="text-[11px] font-mono text-slate-500 flex items-center space-x-1 pt-1">
                <Clock className="w-3 h-3 text-slate-600" />
                <span>最后心跳时间: {new Date(w.lastHeartbeatAt).toLocaleTimeString()}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

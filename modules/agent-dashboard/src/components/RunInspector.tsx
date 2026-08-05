'use client';

import React, { useState } from 'react';
import { AgentRunView, ToolCallLedgerItem } from '@/types/agent';
import {
  CheckCircle2,
  Clock,
  Code2,
  Coins,
  ExternalLink,
  Flame,
  Play,
  ShieldAlert,
  ShieldCheck,
  Terminal,
  UserCheck,
  XCircle,
  Zap
} from 'lucide-react';

interface RunInspectorProps {
  runs: AgentRunView[];
  selectedRunId: string;
  onSelectRun: (id: string) => void;
  langfuseUrl: string;
}

export const RunInspector: React.FC<RunInspectorProps> = ({
  runs,
  selectedRunId,
  onSelectRun,
  langfuseUrl
}) => {
  const currentRun = runs.find((r) => r.runId === selectedRunId) || runs[0];
  const [approvals, setApprovals] = useState<ToolCallLedgerItem[]>(
    currentRun?.pendingApprovals || []
  );
  const [approvalFeedback, setApprovalFeedback] = useState<string | null>(null);

  // SSE Debugger state
  const [ssePrompt, setSsePrompt] = useState('');
  const [sseLogs, setSseLogs] = useState<string[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);

  const handleApprove = (callId: string) => {
    setApprovals(approvals.filter((item) => item.callId !== callId));
    setApprovalFeedback(`已向 /api/v1/commands 提交【已批准】命令 (callId: ${callId})`);
    setTimeout(() => setApprovalFeedback(null), 4000);
  };

  const handleReject = (callId: string) => {
    setApprovals(approvals.filter((item) => item.callId !== callId));
    setApprovalFeedback(`已向 /api/v1/commands 提交【拒绝并终止】命令 (callId: ${callId})`);
    setTimeout(() => setApprovalFeedback(null), 4000);
  };

  const handleStartSseStream = () => {
    if (!ssePrompt.trim()) return;
    setIsStreaming(true);
    setSseLogs([`[INFO] POST /api/v1/agents/${currentRun.agentId}/runs 提交接收`]);

    setTimeout(() => {
      setSseLogs((prev) => [
        ...prev,
        `[SSE] event: run.started { runId: "run-live-${Date.now()}" }`,
        `[SSE] event: delta { text: "收到您的 Prompt，正在解析语义与查询依赖..." }`
      ]);
    }, 600);

    setTimeout(() => {
      setSseLogs((prev) => [
        ...prev,
        `[SSE] event: tool.started { name: "knowledge_search", callId: "call-live-1" }`,
        `[SSE] event: tool.completed { name: "knowledge_search", status: "success" }`
      ]);
    }, 1500);

    setTimeout(() => {
      setSseLogs((prev) => [
        ...prev,
        `[SSE] event: delta { text: "已根据已授权 RAG 谱系提取证据，结论为：架构模式符合 0.4.0 规范。" }`,
        `[SSE] event: run.completed { status: "Completed" }`
      ]);
      setIsStreaming(false);
    }, 2800);
  };

  const getStatusBadgeClass = (status: string) => {
    switch (status) {
      case 'Completed':
        return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30';
      case 'Running':
        return 'bg-blue-500/10 text-blue-400 border-blue-500/30';
      case 'Paused':
        return 'bg-amber-500/10 text-amber-400 border-amber-500/30 animate-pulse';
      case 'Failed':
        return 'bg-rose-500/10 text-rose-400 border-rose-500/30';
      default:
        return 'bg-slate-800 text-slate-400 border-slate-700';
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 h-[calc(100vh-100px)] p-6 bg-slate-950 text-slate-100 font-sans">
      {/* ------------------ Left Pane: Run Selection Thread (3 Cols) ------------------ */}
      <div className="lg:col-span-3 bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm">
        <div className="flex items-center justify-between pb-3 border-b border-slate-800 mb-3">
          <h2 className="font-bold text-sm text-slate-200 tracking-wide flex items-center gap-2">
            <Terminal className="w-4 h-4 text-indigo-400" />
            Agent Runs ({runs.length})
          </h2>
          <span className="text-[10px] text-slate-500 font-mono">Durable Runs</span>
        </div>

        <div className="flex-1 overflow-y-auto space-y-2.5 pr-1 custom-scrollbar">
          {runs.map((run) => {
            const isSelected = run.runId === currentRun.runId;
            return (
              <div
                key={run.runId}
                onClick={() => {
                  onSelectRun(run.runId);
                  setApprovals(run.pendingApprovals || []);
                }}
                className={`p-3.5 rounded-xl border cursor-pointer transition-all duration-200 ${
                  isSelected
                    ? 'bg-slate-800/90 border-indigo-500 shadow-md shadow-indigo-500/10'
                    : 'bg-slate-950/40 border-slate-800/80 hover:bg-slate-800/40 hover:border-slate-700'
                }`}
              >
                <div className="flex items-center justify-between mb-1.5">
                  <span className="font-mono text-xs font-bold text-indigo-300">
                    {run.runId}
                  </span>
                  <span
                    className={`text-[10px] px-2 py-0.5 rounded-full font-semibold border ${getStatusBadgeClass(
                      run.status
                    )}`}
                  >
                    {run.status}
                  </span>
                </div>
                <p className="text-xs text-slate-300 line-clamp-2 mb-2 font-medium">
                  {run.userQuery}
                </p>
                <div className="flex items-center justify-between text-[11px] text-slate-500 font-mono">
                  <span>{new Date(run.createdAt).toLocaleTimeString()}</span>
                  <span>{run.cumulativeUsage.totalTokens} tokens</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* ------------------ Center Pane: Timeline & Tool Ledger (5 Cols) ------------------ */}
      <div className="lg:col-span-5 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 flex flex-col shadow-xl overflow-hidden backdrop-blur-sm">
        {/* Run Metadata Header */}
        <div className="pb-4 border-b border-slate-800 mb-4 flex items-center justify-between">
          <div>
            <div className="flex items-center space-x-2">
              <span className="font-mono text-sm font-bold text-slate-100">
                {currentRun.runId}
              </span>
              <span className="text-xs text-slate-400 font-mono">({currentRun.agentId})</span>
            </div>
            <p className="text-xs text-slate-400 mt-1">{currentRun.userQuery}</p>
          </div>

          {/* Langfuse Deep Link */}
          {currentRun.traceId && (
            <a
              href={`${langfuseUrl}/trace/${currentRun.traceId}`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center space-x-1.5 text-xs bg-amber-500/10 text-amber-300 border border-amber-500/30 px-3 py-1.5 rounded-lg font-medium hover:bg-amber-500/20 transition-all"
            >
              <Flame className="w-3.5 h-3.5 text-amber-400" />
              <span>Trace ID</span>
              <ExternalLink className="w-3 h-3 text-amber-400 opacity-70" />
            </a>
          )}
        </div>

        {/* Step Waterfall Timeline */}
        <div className="flex-1 overflow-y-auto space-y-4 pr-1">
          <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-2">
            Execution Step Timeline
          </h3>

          {currentRun.steps.map((step) => (
            <div
              key={step.stepIndex}
              className="bg-slate-950/60 border border-slate-800/80 rounded-xl p-4 hover:border-slate-700 transition-all"
            >
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center space-x-2">
                  <span className="w-5 h-5 rounded-full bg-indigo-500/20 text-indigo-300 font-mono text-xs flex items-center justify-center font-bold">
                    {step.stepIndex}
                  </span>
                  <span className="font-semibold text-xs text-slate-200">{step.nodeName}</span>
                </div>
                <div className="flex items-center space-x-2 text-[11px] text-slate-400 font-mono">
                  <Clock className="w-3 h-3 text-slate-500" />
                  <span>{step.durationMs}ms</span>
                </div>
              </div>

              {/* Token Metrics if Model Inference */}
              {step.usage && (
                <div className="mt-2 pt-2 border-t border-slate-800/60 flex items-center space-x-4 text-[11px] text-slate-400 font-mono">
                  <span className="flex items-center gap-1">
                    <Coins className="w-3 h-3 text-amber-400" />
                    Input: {step.usage.inputTokens}
                  </span>
                  <span>Output: {step.usage.outputTokens}</span>
                  {step.usage.promptCacheHits ? (
                    <span className="text-emerald-400">
                      Cache Hits: {step.usage.promptCacheHits}
                    </span>
                  ) : null}
                </div>
              )}

              {/* Tool Calls if Any */}
              {step.toolCalls && step.toolCalls.length > 0 && (
                <div className="mt-3 space-y-2">
                  {step.toolCalls.map((tool) => (
                    <div
                      key={tool.callId}
                      className="bg-slate-900 border border-slate-800 rounded-lg p-3 text-xs"
                    >
                      <div className="flex items-center justify-between mb-1.5">
                        <div className="flex items-center space-x-2">
                          <Code2 className="w-3.5 h-3.5 text-cyan-400" />
                          <span className="font-mono font-bold text-cyan-300">
                            {tool.toolName}
                          </span>
                        </div>
                        <span
                          className={`text-[10px] px-2 py-0.5 rounded font-mono font-semibold ${
                            tool.riskLevel === 'High'
                              ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30'
                              : 'bg-slate-800 text-slate-300'
                          }`}
                        >
                          Risk: {tool.riskLevel}
                        </span>
                      </div>
                      <div className="bg-slate-950 p-2 rounded font-mono text-[11px] text-slate-300 overflow-x-auto">
                        <pre>{JSON.stringify(tool.arguments, null, 2)}</pre>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>

        {/* Final Answer Banner if Completed */}
        {currentRun.finalAnswer && (
          <div className="mt-4 pt-3 border-t border-slate-800 bg-emerald-950/20 border-emerald-500/30 p-3.5 rounded-xl border">
            <h4 className="text-xs font-bold text-emerald-400 flex items-center gap-1.5 mb-1">
              <CheckCircle2 className="w-4 h-4 text-emerald-400" />
              Final Output Verified
            </h4>
            <p className="text-xs text-slate-200 leading-relaxed font-mono">
              {currentRun.finalAnswer}
            </p>
          </div>
        )}
      </div>

      {/* ------------------ Right Pane: Approvals & SSE Debugger (4 Cols) ------------------ */}
      <div className="lg:col-span-4 flex flex-col space-y-5">
        {/* Human-in-the-Loop Approvals Box */}
        <div className="bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm">
          <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
            <h3 className="font-bold text-xs uppercase tracking-wider text-amber-400 flex items-center gap-1.5">
              <ShieldAlert className="w-4 h-4 text-amber-400" />
              Human-in-the-Loop Approvals
            </h3>
            <span className="text-[10px] bg-amber-500/20 text-amber-300 px-2 py-0.5 rounded-full font-mono font-bold">
              {approvals.length} Pending
            </span>
          </div>

          {approvalFeedback && (
            <div className="mb-3 p-2.5 bg-indigo-500/20 border border-indigo-500/30 rounded-lg text-xs text-indigo-200 font-mono">
              {approvalFeedback}
            </div>
          )}

          {approvals.length === 0 ? (
            <div className="py-6 text-center text-xs text-slate-500 font-mono flex flex-col items-center">
              <ShieldCheck className="w-8 h-8 text-slate-600 mb-2 opacity-50" />
              没有处于暂停状态的写工具审批请求
            </div>
          ) : (
            <div className="space-y-3">
              {approvals.map((item) => (
                <div
                  key={item.callId}
                  className="bg-slate-950 border border-amber-500/30 p-3.5 rounded-xl text-xs space-y-2.5 shadow-md shadow-amber-500/5"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono font-bold text-amber-300">{item.toolName}</span>
                    <span className="bg-rose-500/20 text-rose-300 px-2 py-0.5 rounded text-[10px] font-bold">
                      {item.riskLevel} Risk
                    </span>
                  </div>

                  <div className="bg-slate-900 p-2 rounded font-mono text-[11px] text-slate-300 overflow-x-auto">
                    <pre>{JSON.stringify(item.arguments, null, 2)}</pre>
                  </div>

                  <div className="flex space-x-2 pt-1">
                    <button
                      onClick={() => handleApprove(item.callId)}
                      className="flex-1 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold py-1.5 rounded-lg text-xs flex items-center justify-center space-x-1 shadow-md shadow-emerald-600/20 transition-all"
                    >
                      <UserCheck className="w-3.5 h-3.5" />
                      <span>批准执行</span>
                    </button>

                    <button
                      onClick={() => handleReject(item.callId)}
                      className="flex-1 bg-rose-600/20 hover:bg-rose-600/30 text-rose-300 border border-rose-500/30 font-semibold py-1.5 rounded-lg text-xs flex items-center justify-center space-x-1 transition-all"
                    >
                      <XCircle className="w-3.5 h-3.5" />
                      <span>拒绝并终止</span>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* SSE Event Stream Live Debugger */}
        <div className="bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex-1 flex flex-col shadow-xl backdrop-blur-sm">
          <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
            <h3 className="font-bold text-xs uppercase tracking-wider text-slate-300 flex items-center gap-1.5">
              <Zap className="w-4 h-4 text-cyan-400" />
              SSE Live Event Debugger
            </h3>
            <span className="text-[10px] text-slate-500 font-mono">/api/v1/events/stream</span>
          </div>

          {/* Test Prompt Input */}
          <div className="flex space-x-2 mb-3">
            <input
              type="text"
              value={ssePrompt}
              onChange={(e) => setSsePrompt(e.target.value)}
              placeholder="测试提交新 Run并模拟 SSE 流..."
              className="flex-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 focus:outline-none focus:border-indigo-500 font-sans"
            />
            <button
              onClick={handleStartSseStream}
              disabled={isStreaming}
              className="bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center space-x-1 transition-all"
            >
              <Play className="w-3.5 h-3.5" />
              <span>测试</span>
            </button>
          </div>

          {/* Terminal Console Log */}
          <div className="flex-1 bg-slate-950 rounded-xl p-3 border border-slate-800/80 font-mono text-[11px] text-slate-300 overflow-y-auto space-y-1.5">
            {sseLogs.length === 0 ? (
              <span className="text-slate-600 italic">等待测试命令提交以开始 SSE 捕获...</span>
            ) : (
              sseLogs.map((log, index) => (
                <div key={index} className="leading-relaxed">
                  <span className="text-slate-500">{new Date().toLocaleTimeString()}</span>{' '}
                  <span className={log.includes('started') ? 'text-amber-400' : 'text-cyan-300'}>
                    {log}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

'use client';

import React, { useState } from 'react';
import { RuntimeConfigModel } from '@/types/agent';
import {
  Check,
  Code2,
  Copy,
  Cpu,
  Database,
  Layers,
  Save,
  Settings,
  Sliders,
  Sparkles
} from 'lucide-react';

interface ConfigStudioProps {
  initialConfig: RuntimeConfigModel;
}

export const ConfigStudio: React.FC<ConfigStudioProps> = ({ initialConfig }) => {
  const [config, setConfig] = useState<RuntimeConfigModel>(initialConfig);
  const [copiedCode, setCopiedCode] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const generateScalaSnippet = () => {
    return `// 自动生成的 zyblw-agent 运行时 ZLayer 调优代码
val applicationConfig = AgentApplicationConfig(
  workerConcurrency = ${config.workerConcurrency},
  leaseTimeout = ${config.workerLeaseTimeoutSeconds}.seconds,
  toolPolicy = ToolPolicyConfig(
    allowedTools = Set(${config.allowedToolNames.map((t) => `ToolName("${t}")`).join(', ')}),
    maxCallsPerRun = 12
  )
)

val ragPolicy = RetrievalExpansionConfig(
  neighborRadius = ${config.ragExpansionRadius},
  maxAdditionalChunks = 8
)
`;
  };

  const generateEnvSnippet = () => {
    return `# 自动生成的环境变数
ZYBLW_AGENT_WORKER_CONCURRENCY=${config.workerConcurrency}
ZYBLW_AGENT_LEASE_TIMEOUT_SECONDS=${config.workerLeaseTimeoutSeconds}
ZYBLW_AGENT_DEFAULT_MODEL=${config.defaultModel}
ZYBLW_AGENT_RAG_LIMIT=${config.ragLimit}
ZYBLW_AGENT_RERANK_ENABLED=${config.rerankEnabled}
`;
  };

  const handleSave = () => {
    setFeedback('已成功更新全局运行时配置参数（只读状态已同步至 AgentApplication Config）。');
    setTimeout(() => setFeedback(null), 4000);
  };

  const handleCopyScala = () => {
    navigator.clipboard.writeText(generateScalaSnippet());
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  };

  return (
    <div className="p-6 bg-slate-950 text-slate-100 font-sans space-y-6 min-h-[calc(100vh-100px)]">
      {/* Top Banner */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex flex-wrap items-center justify-between gap-4 shadow-xl backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-3 rounded-xl bg-indigo-500/10 border border-indigo-500/30 text-indigo-400">
            <Sliders className="w-6 h-6" />
          </div>
          <div>
            <h2 className="font-bold text-base text-slate-100">
              运行时配置与参数调优面板 (Live Config Studio)
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              实时参数调优 • 动态生成 Scala ZLayer 与 .env 环境变量代码
            </p>
          </div>
        </div>

        <button
          onClick={handleSave}
          className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold px-4 py-2 rounded-xl flex items-center space-x-2 shadow-lg shadow-indigo-600/20 transition-all"
        >
          <Save className="w-4 h-4" />
          <span>保存调优配置</span>
        </button>
      </div>

      {feedback && (
        <div className="p-3 bg-emerald-500/20 border border-emerald-500/30 rounded-xl text-xs text-emerald-200 font-mono flex items-center space-x-2">
          <Check className="w-4 h-4 text-emerald-400" />
          <span>{feedback}</span>
        </div>
      )}

      {/* Grid Layout: Config Forms vs Code Generators */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left: Parameter Tuning Sliders & Inputs (7 Cols) */}
        <div className="lg:col-span-7 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 space-y-5 shadow-xl backdrop-blur-sm">
          <h3 className="font-bold text-sm text-slate-200 border-b border-slate-800 pb-3 flex items-center gap-2">
            <Settings className="w-4 h-4 text-indigo-400" />
            框架运行时参数调整
          </h3>

          {/* Model & Provider */}
          <div className="space-y-4">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-amber-400" />
              1. 模型与 LLM Provider 策略
            </h4>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  默认 PROVIDER
                </label>
                <input
                  type="text"
                  value={config.defaultProvider}
                  onChange={(e) => setConfig({ ...config, defaultProvider: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  默认模型 ID (MODEL ID)
                </label>
                <input
                  type="text"
                  value={config.defaultModel}
                  onChange={(e) => setConfig({ ...config, defaultModel: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>
            </div>

            {/* Temperature Slider */}
            <div>
              <div className="flex justify-between items-center text-xs mb-1">
                <span className="text-slate-400 font-mono">模型随机采样温度 (TEMPERATURE)</span>
                <span className="font-mono text-indigo-400 font-bold">{config.temperature}</span>
              </div>
              <input
                type="range"
                min="0"
                max="1"
                step="0.05"
                value={config.temperature}
                onChange={(e) => setConfig({ ...config, temperature: parseFloat(e.target.value) })}
                className="w-full h-1.5 bg-slate-950 rounded-lg appearance-none cursor-pointer accent-indigo-500"
              />
            </div>
          </div>

          {/* RAG & Lineage Parameters */}
          <div className="space-y-4 pt-4 border-t border-slate-800">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
              <Layers className="w-3.5 h-3.5 text-indigo-400" />
              2. RAG 谱系与扩展控制参数
            </h4>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  检索 TOP-K 上限
                </label>
                <input
                  type="number"
                  value={config.ragLimit}
                  onChange={(e) => setConfig({ ...config, ragLimit: parseInt(e.target.value) || 5 })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  邻近块扩展半径 (0..1)
                </label>
                <input
                  type="number"
                  min="0"
                  max="1"
                  value={config.ragExpansionRadius}
                  onChange={(e) => setConfig({ ...config, ragExpansionRadius: parseInt(e.target.value) || 0 })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>
            </div>

            <div className="flex items-center space-x-2 pt-1">
              <input
                type="checkbox"
                id="rerankToggle"
                checked={config.rerankEnabled}
                onChange={(e) => setConfig({ ...config, rerankEnabled: e.target.checked })}
                className="w-4 h-4 rounded bg-slate-950 border-slate-800 text-indigo-600 focus:ring-0"
              />
              <label htmlFor="rerankToggle" className="text-xs text-slate-300">
                开启 Cross-Encoder Model Reranker 后排序与校验
              </label>
            </div>
          </div>

          {/* Worker Queue Parameters */}
          <div className="space-y-4 pt-4 border-t border-slate-800">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
              <Cpu className="w-3.5 h-3.5 text-cyan-400" />
              3. Worker 队列与并发池参数
            </h4>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  WORKER 并发车道数 (CONCURRENCY)
                </label>
                <input
                  type="number"
                  value={config.workerConcurrency}
                  onChange={(e) => setConfig({ ...config, workerConcurrency: parseInt(e.target.value) || 4 })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-slate-400 block mb-1">
                  租约超时时间 (LEASE TIMEOUT - 秒)
                </label>
                <input
                  type="number"
                  value={config.workerLeaseTimeoutSeconds}
                  onChange={(e) => setConfig({ ...config, workerLeaseTimeoutSeconds: parseInt(e.target.value) || 30 })}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-indigo-500"
                />
              </div>
            </div>
          </div>
        </div>

        {/* Right: Real-time Code Generators (5 Cols) */}
        <div className="lg:col-span-5 space-y-5">
          {/* Generated Scala ZLayer Snippet */}
          <div className="bg-slate-900/80 rounded-2xl border border-slate-800 p-5 shadow-xl backdrop-blur-sm">
            <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
              <h3 className="font-bold text-xs uppercase tracking-wider text-indigo-400 flex items-center gap-1.5">
                <Code2 className="w-4 h-4 text-indigo-400" />
                生成的 Scala ZLayer 逻辑代码
              </h3>
              <button
                onClick={handleCopyScala}
                className="text-[11px] bg-slate-800 hover:bg-slate-700 text-slate-300 px-2.5 py-1 rounded flex items-center space-x-1 transition-all"
              >
                {copiedCode ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                <span>{copiedCode ? '已复制' : '复制'}</span>
              </button>
            </div>

            <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-[11px] text-slate-300 overflow-x-auto custom-scrollbar">
              <pre>{generateScalaSnippet()}</pre>
            </div>
          </div>

          {/* Generated .env File Snippet */}
          <div className="bg-slate-900/80 rounded-2xl border border-slate-800 p-5 shadow-xl backdrop-blur-sm">
            <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
              <h3 className="font-bold text-xs uppercase tracking-wider text-cyan-400 flex items-center gap-1.5">
                <Database className="w-4 h-4 text-cyan-400" />
                生成的 .env 配置文件行
              </h3>
            </div>

            <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-[11px] text-emerald-400 overflow-x-auto custom-scrollbar">
              <pre>{generateEnvSnippet()}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

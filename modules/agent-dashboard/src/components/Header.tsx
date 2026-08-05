'use client';

import React from 'react';
import {
  Activity,
  Cpu,
  Database,
  ExternalLink,
  Flame,
  Layers,
  Shield,
  Sliders,
  Terminal,
  TrendingUp,
  Zap
} from 'lucide-react';

export type DashboardTab = 'runs' | 'rag' | 'queue' | 'config' | 'security' | 'evals';

interface HeaderProps {
  activeTab: DashboardTab;
  onTabChange: (tab: DashboardTab) => void;
  serverUrl: string;
  onServerUrlChange: (url: string) => void;
  langfuseUrl: string;
  grafanaUrl: string;
}

export const Header: React.FC<HeaderProps> = ({
  activeTab,
  onTabChange,
  serverUrl,
  onServerUrlChange,
  langfuseUrl,
  grafanaUrl
}) => {
  return (
    <header className="sticky top-0 z-50 bg-slate-950/90 backdrop-blur-md border-b border-slate-800 text-slate-100 px-6 py-3.5 shadow-xl">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        {/* Brand & Title */}
        <div className="flex items-center space-x-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 via-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-indigo-500/30">
            <Zap className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h1 className="font-bold text-lg tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-slate-100 via-slate-200 to-slate-400">
                zyblw-agent
              </h1>
              <span className="px-2 py-0.5 text-xs font-semibold rounded-full bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                v0.4.0
              </span>
              <span className="px-2 py-0.5 text-xs font-medium rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                ZIO 2 运行正常
              </span>
            </div>
            <p className="text-xs text-slate-400 font-sans">
              企业级智能体运行时与 RAG 谱系管理控制台
            </p>
          </div>
        </div>

        {/* Tab Navigation in Chinese */}
        <nav className="flex items-center space-x-1 bg-slate-900/90 p-1.5 rounded-xl border border-slate-800 shadow-inner overflow-x-auto">
          <button
            onClick={() => onTabChange('runs')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'runs'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            <span>智能体运行追溯</span>
          </button>

          <button
            onClick={() => onTabChange('rag')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'rag'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span>RAG 知识库与解析</span>
          </button>

          <button
            onClick={() => onTabChange('queue')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'queue'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Cpu className="w-3.5 h-3.5" />
            <span>Worker 队列与节点</span>
          </button>

          <button
            onClick={() => onTabChange('config')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'config'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Sliders className="w-3.5 h-3.5" />
            <span>运行时配置与调优</span>
          </button>

          <button
            onClick={() => onTabChange('security')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'security'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Shield className="w-3.5 h-3.5" />
            <span>安全规则与文件</span>
          </button>

          <button
            onClick={() => onTabChange('evals')}
            className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 ${
              activeTab === 'evals'
                ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <TrendingUp className="w-3.5 h-3.5" />
            <span>评测与质量大盘</span>
          </button>
        </nav>

        {/* Server Switcher & External Tracing Links */}
        <div className="flex items-center space-x-3">
          {/* Server Input */}
          <div className="flex items-center space-x-2 bg-slate-900 border border-slate-800 px-3 py-1.5 rounded-lg">
            <Database className="w-3.5 h-3.5 text-slate-400" />
            <input
              type="text"
              value={serverUrl}
              onChange={(e) => onServerUrlChange(e.target.value)}
              className="bg-transparent text-xs text-slate-200 font-mono focus:outline-none w-36"
              placeholder="http://localhost:8080"
            />
          </div>

          {/* Langfuse Deep Tracing Bridge */}
          <a
            href={langfuseUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center space-x-1.5 bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border border-amber-500/30 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150"
            title="跳转至 Langfuse 深度 Flamegraph 追踪页面"
          >
            <Flame className="w-3.5 h-3.5 text-amber-400" />
            <span>Langfuse 追踪</span>
            <ExternalLink className="w-3 h-3 text-amber-400 opacity-70" />
          </a>

          {/* Grafana Prometheus Bridge */}
          <a
            href={grafanaUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center space-x-1.5 bg-orange-500/10 hover:bg-orange-500/20 text-orange-300 border border-orange-500/30 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-150"
            title="跳转至 Grafana Prometheus SRE 指标监控大盘"
          >
            <Activity className="w-3.5 h-3.5 text-orange-400" />
            <span>Grafana 监控</span>
            <ExternalLink className="w-3 h-3 text-orange-400 opacity-70" />
          </a>
        </div>
      </div>
    </header>
  );
};

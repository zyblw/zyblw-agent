'use client';

import React from 'react';
import { ArtifactItem } from '@/types/agent';
import {
  Download,
  FileCheck,
  FolderLock,
  Lock,
  Shield,
  ShieldCheck,
  Terminal,
  Trash2,
  Unlock
} from 'lucide-react';

interface SecurityArtifactsProps {
  artifacts: ArtifactItem[];
  allowedTools: string[];
}

export const SecurityArtifacts: React.FC<SecurityArtifactsProps> = ({
  artifacts,
  allowedTools
}) => {
  return (
    <div className="p-6 bg-slate-950 text-slate-100 font-sans space-y-6 min-h-[calc(100vh-100px)]">
      {/* Top Banner */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex flex-wrap items-center justify-between gap-4 shadow-xl backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <div>
            <h2 className="font-bold text-base text-slate-100">
              Guardrail Security Policies & Artifact Explorer
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Tool Whitelist • Risk Classification • Metadata Redaction • Session File Isolation
            </p>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Guardrail Policy Whitelist (6 Cols) */}
        <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 space-y-4 shadow-xl backdrop-blur-sm">
          <div className="pb-3 border-b border-slate-800 flex items-center justify-between">
            <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
              <Shield className="w-4 h-4 text-emerald-400" />
              Active Tool Policies & Risk Classifications
            </h3>
            <span className="text-[10px] bg-emerald-500/20 text-emerald-300 px-2 py-0.5 rounded font-mono font-bold">
              {allowedTools.length} Tools Whitelisted
            </span>
          </div>

          <div className="space-y-3">
            {allowedTools.map((tool) => (
              <div
                key={tool}
                className="bg-slate-950 border border-slate-800/80 p-3.5 rounded-xl flex items-center justify-between text-xs"
              >
                <div className="flex items-center space-x-3">
                  <Lock className="w-4 h-4 text-indigo-400" />
                  <div>
                    <span className="font-mono font-bold text-slate-200">{tool}</span>
                    <span className="text-[10px] text-slate-500 block mt-0.5">
                      Stateful & Boundary Enforced
                    </span>
                  </div>
                </div>

                <span
                  className={`text-[10px] font-mono font-bold px-2.5 py-1 rounded-full border ${
                    tool.includes('schema') || tool.includes('sql')
                      ? 'bg-rose-500/10 text-rose-400 border-rose-500/30'
                      : 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                  }`}
                >
                  {tool.includes('schema') || tool.includes('sql') ? 'High Risk (Approval Required)' : 'Low Risk (Auto Direct)'}
                </span>
              </div>
            ))}
          </div>

          {/* Privacy Redaction Policy Box */}
          <div className="mt-4 p-4 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
            <h4 className="text-xs font-bold text-indigo-400 flex items-center gap-1.5">
              <FolderLock className="w-4 h-4" />
              MetadataOnly Redaction Policies Active
            </h4>
            <p className="text-xs text-slate-400 leading-relaxed font-sans">
              根据 `MetadataOnly` 隐私脱敏策略：所有的密码、Authorization Token、API Key、完整的 System Prompt、敏感患者/用户正文已被过滤，不进 Log、Metrics 或视图回显。
            </p>
          </div>
        </div>

        {/* Artifact Session Storage Explorer (6 Cols) */}
        <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 space-y-4 shadow-xl backdrop-blur-sm">
          <div className="pb-3 border-b border-slate-800 flex items-center justify-between">
            <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
              <FileCheck className="w-4 h-4 text-cyan-400" />
              Agent Session Artifact Explorer
            </h3>
            <span className="text-[10px] text-slate-500 font-mono">
              Immutable Artifact Store
            </span>
          </div>

          <div className="space-y-3">
            {artifacts.map((art) => (
              <div
                key={art.id}
                className="bg-slate-950 border border-slate-800/80 p-3.5 rounded-xl space-y-2 text-xs"
              >
                <div className="flex items-center justify-between">
                  <span className="font-mono font-bold text-slate-200">{art.fileName}</span>
                  <span className="text-[10px] font-mono text-slate-500">
                    {(art.sizeBytes / 1024).toFixed(1)} KB
                  </span>
                </div>

                <div className="flex justify-between items-center text-[10px] font-mono text-slate-500">
                  <span>MIME: {art.mediaType}</span>
                  <span>SHA256: {art.sha256}</span>
                </div>

                <div className="flex space-x-2 pt-1 border-t border-slate-900">
                  <button className="flex-1 bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 py-1 rounded text-[11px] font-mono flex items-center justify-center space-x-1">
                    <Download className="w-3 h-3 text-cyan-400" />
                    <span>查看/下载 Artifact</span>
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

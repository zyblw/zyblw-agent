'use client';

import React, { useState } from 'react';
import { DocumentBlock, RetrievalHit } from '@/types/agent';
import {
  FileText,
  Filter,
  Layers,
  MapPin,
  Search,
  ShieldCheck,
  Sparkles,
  Table,
  Type
} from 'lucide-react';

interface RagInspectorProps {
  blocks: DocumentBlock[];
  hits: RetrievalHit[];
}

export const RagInspector: React.FC<RagInspectorProps> = ({ blocks, hits }) => {
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(blocks[0]?.id || null);
  const [queryText, setQueryText] = useState('PDF RAG 结构切分');
  const [tenantId, setTenantId] = useState('tenant-enterprise-a');
  const [permissions, setPermissions] = useState('knowledge:read');
  const [searchHits, setSearchHits] = useState<RetrievalHit[]>(hits);

  const selectedBlock = blocks.find((b) => b.id === selectedBlockId) || blocks[0];
  const originBox = selectedBlock?.origins[0]?.boundingBox;

  const handleRunSearch = () => {
    setSearchHits(hits);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 h-[calc(100vh-100px)] p-6 bg-slate-950 text-slate-100 font-sans">
      {/* ------------------ Top Banner & Index Status ------------------ */}
      <div className="lg:col-span-12 bg-slate-900/80 border border-slate-800 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-4 backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-xl bg-indigo-500/10 border border-indigo-500/30 text-indigo-400">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h2 className="font-bold text-sm text-slate-100">
                Knowledge Schema: <span className="font-mono text-indigo-400">zyblw_agent_knowledge</span>
              </h2>
              <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-[10px] px-2 py-0.5 rounded-full font-bold">
                Active Version 1
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              Isolated PostgreSQL Flyway History • vector(1536) • DocumentStructure Lineage Preserved
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-6 text-xs font-mono">
          <div>
            <span className="text-slate-500 block text-[10px]">TOTAL DOCUMENTS</span>
            <span className="font-bold text-slate-200">12 Documents</span>
          </div>
          <div className="h-6 w-px bg-slate-800" />
          <div>
            <span className="text-slate-500 block text-[10px]">TOTAL CHUNKS</span>
            <span className="font-bold text-indigo-300">1,480 Chunks</span>
          </div>
          <div className="h-6 w-px bg-slate-800" />
          <div>
            <span className="text-slate-500 block text-[10px]">EMBEDDING DIMENSION</span>
            <span className="font-bold text-cyan-300">1536 Float32</span>
          </div>
        </div>
      </div>

      {/* ------------------ Left: PDF Visual Canvas Overlay (6 Cols) ------------------ */}
      <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm overflow-hidden">
        <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
          <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200 flex items-center gap-1.5">
            <FileText className="w-4 h-4 text-indigo-400" />
            PDF Canvas & BoundingBox Visual Overlay
          </h3>
          <span className="text-[10px] bg-slate-800 px-2 py-0.5 rounded font-mono text-slate-400">
            Page {selectedBlock?.origins[0]?.pageNumber || 1} of 12
          </span>
        </div>

        {/* Simulated PDF Canvas Container */}
        <div className="flex-1 bg-slate-950 rounded-xl border border-slate-800 p-6 flex flex-col items-center justify-start overflow-y-auto relative custom-scrollbar">
          {/* Simulated PDF Document Page Canvas */}
          <div className="w-[480px] h-[640px] bg-slate-900 border border-slate-800 rounded-lg p-8 relative shadow-2xl overflow-hidden text-slate-300">
            {/* Page Header Mock */}
            <div className="text-[10px] text-slate-500 border-b border-slate-800 pb-2 mb-4 font-mono flex justify-between">
              <span>CONFIDENTIAL TECHNICAL SPECIFICATION</span>
              <span>zyblw-agent v0.4.0</span>
            </div>

            {/* Render Block Mock Lines */}
            <div className="space-y-4 text-xs font-sans">
              <h2 className="text-sm font-bold text-slate-100 border-b border-slate-800 pb-1">
                zyblw-agent 0.4.0 架构与 RAG 谱系规范
              </h2>
              <h3 className="text-xs font-bold text-indigo-300 mt-2">
                1. 结构化切分与 pgvector 隔离
              </h3>
              <p className="text-[11px] text-slate-300 leading-relaxed">
                DocumentStructureChunker 优先按文档层级结构 block 切分，合并同标题同父级的相邻小块，并在数据库中保留 zyblw_agent_knowledge 专属 Schema 隔离。
              </p>
              <div className="bg-slate-950 p-2.5 rounded border border-slate-800 text-[10px] font-mono">
                <p>| 模块名 | 数据库 Schema | 维数 |</p>
                <p>| Agent Core | public (default) | N/A |</p>
                <p>| Agent Knowledge | zyblw_agent_knowledge | 1536 |</p>
              </div>
            </div>

            {/* BoundingBox Highlight Overlay */}
            {originBox && (
              <div
                className="absolute border-2 border-amber-400 bg-amber-400/20 rounded shadow-lg shadow-amber-400/20 transition-all duration-300 pointer-events-none flex items-start justify-end p-1"
                style={{
                  left: `${(originBox.left / (originBox.pageWidth || 595)) * 100}%`,
                  top: `${(originBox.top / (originBox.pageHeight || 842)) * 100}%`,
                  width: `${((originBox.right - originBox.left) / (originBox.pageWidth || 595)) * 100}%`,
                  height: `${((originBox.bottom - originBox.top) / (originBox.pageHeight || 842)) * 100}%`
                }}
              >
                <span className="bg-amber-400 text-slate-950 font-mono text-[9px] font-bold px-1 rounded shadow">
                  BBox #{selectedBlock.id}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ------------------ Right: DocumentStructure Blocks & Lineage Tree (6 Cols) ------------------ */}
      <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm overflow-hidden">
        <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
          <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200 flex items-center gap-1.5">
            <Sparkles className="w-4 h-4 text-cyan-400" />
            DocumentStructure Block Tree
          </h3>
          <span className="text-[10px] text-slate-500 font-mono">Docling / Tika Projection</span>
        </div>

        {/* Blocks List */}
        <div className="flex-1 overflow-y-auto space-y-3 pr-1 mb-4 custom-scrollbar">
          {blocks.map((block) => {
            const isSelected = block.id === selectedBlockId;
            return (
              <div
                key={block.id}
                onClick={() => setSelectedBlockId(block.id)}
                className={`p-3.5 rounded-xl border cursor-pointer transition-all ${
                  isSelected
                    ? 'bg-slate-800 border-amber-400/80 shadow-md shadow-amber-400/5'
                    : 'bg-slate-950/60 border-slate-800/80 hover:border-slate-700'
                }`}
              >
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center space-x-2">
                    <span className="font-mono text-xs font-bold text-indigo-300">#{block.id}</span>
                    <span className="bg-slate-900 border border-slate-800 text-[10px] font-mono px-2 py-0.5 rounded text-slate-300 flex items-center gap-1">
                      {block.kind === 'Table' ? (
                        <Table className="w-3 h-3 text-cyan-400" />
                      ) : (
                        <Type className="w-3 h-3 text-indigo-400" />
                      )}
                      {block.kind}
                    </span>
                  </div>
                  <span className="text-[10px] text-slate-500 font-mono">
                    Ordinal: {block.ordinal}
                  </span>
                </div>

                <p className="text-xs text-slate-200 leading-relaxed font-sans line-clamp-2">
                  {block.text}
                </p>

                {block.headingPath && block.headingPath.length > 0 && (
                  <div className="mt-2 text-[10px] font-mono text-slate-400 bg-slate-950 px-2 py-1 rounded border border-slate-800/60">
                    Path: {block.headingPath.join(' > ')}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* Hybrid Search Sandbox Sub-Panel */}
        <div className="border-t border-slate-800 pt-4">
          <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider mb-2.5 flex items-center gap-1.5">
            <Search className="w-3.5 h-3.5 text-indigo-400" />
            Hybrid Search Sandbox (Vector + FTS + RRF + Reranker)
          </h4>

          <div className="flex space-x-2 mb-3">
            <input
              type="text"
              value={queryText}
              onChange={(e) => setQueryText(e.target.value)}
              className="flex-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-sans focus:outline-none focus:border-indigo-500"
              placeholder="测试检索 Query..."
            />
            <button
              onClick={handleRunSearch}
              className="bg-indigo-600 hover:bg-indigo-500 text-white px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center space-x-1"
            >
              <Search className="w-3.5 h-3.5" />
              <span>检索</span>
            </button>
          </div>

          <div className="flex items-center space-x-4 text-[11px] text-slate-400 font-mono mb-2">
            <span className="flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              Tenant: {tenantId}
            </span>
            <span className="flex items-center gap-1">
              <Filter className="w-3.5 h-3.5 text-indigo-400" />
              ACL: {permissions}
            </span>
          </div>

          {/* Search Hit Diagnostic */}
          {searchHits.length > 0 && (
            <div className="bg-slate-950 p-3 rounded-xl border border-slate-800/80 space-y-1.5 text-xs">
              <div className="flex justify-between items-center text-[11px] font-mono text-emerald-400 font-semibold">
                <span>Hit #1: {searchHits[0].chunk.id}</span>
                <span>Final Score: {searchHits[0].score}</span>
              </div>
              <div className="text-[10px] font-mono text-slate-400 flex space-x-3">
                <span>Vector: {searchHits[0].signals.vectorScore}</span>
                <span>FTS Lexeme: {searchHits[0].signals.textScore}</span>
                <span>RRF Rank: #{searchHits[0].signals.hybridRrfRank}</span>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

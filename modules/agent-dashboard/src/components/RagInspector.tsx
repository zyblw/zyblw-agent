'use client';

import React, { useState } from 'react';
import { DocumentBlock, KnowledgeDocumentView, RetrievalHit } from '@/types/agent';
import {
  CheckCircle2,
  FileText,
  Filter,
  FolderPlus,
  Layers,
  Play,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  Table,
  Type,
  UploadCloud
} from 'lucide-react';

interface RagInspectorProps {
  initialDocs: KnowledgeDocumentView[];
  blocks: DocumentBlock[];
  hits: RetrievalHit[];
}

export const RagInspector: React.FC<RagInspectorProps> = ({
  initialDocs,
  blocks,
  hits
}) => {
  const [docs, setDocs] = useState<KnowledgeDocumentView[]>(initialDocs);
  const [selectedDocId, setSelectedDocId] = useState<string>(docs[0]?.id || '');
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(blocks[0]?.id || null);

  // Ingestion Form State
  const [uploadFileName, setUploadFileName] = useState('');
  const [loaderEngine, setLoaderEngine] = useState<'Docling' | 'Apache Tika'>('Docling');
  const [chunkerStrategy, setChunkerStrategy] = useState<'DocumentStructureChunker' | 'SlidingWindowChunker'>('DocumentStructureChunker');
  const [tenantId, setTenantId] = useState('tenant-enterprise-a');
  const [permissions, setPermissions] = useState('knowledge:read');
  const [isIngesting, setIsIngesting] = useState(false);
  const [ingestStep, setIngestStep] = useState<string | null>(null);

  // Retrieval Sandbox State
  const [queryText, setQueryText] = useState('PDF RAG 结构切分与向量表隔离');
  const [searchHits, setSearchHits] = useState<RetrievalHit[]>(hits);

  const selectedBlock = blocks.find((b) => b.id === selectedBlockId) || blocks[0];
  const originBox = selectedBlock?.origins[0]?.boundingBox;

  const handleSimulateUpload = () => {
    const fileName = uploadFileName.trim() || '未命名业务资料文档.pdf';
    setIsIngesting(true);
    setIngestStep('1/4 读取 DocumentInput 并启动解析...');

    setTimeout(() => setIngestStep('2/4 Docling 结构解析与 DocumentBlock 识别...'), 700);
    setTimeout(() => setIngestStep('3/4 Governed Embedding 缓存与向量编码...'), 1400);
    setTimeout(() => {
      setIngestStep('4/4 Staging 验证并原子发布至 Active 向量库!');
      const newDoc: KnowledgeDocumentView = {
        id: `doc-${Date.now()}`,
        fileName: fileName,
        sourceUri: `knowledge://local/docs/${fileName}`,
        mediaType: fileName.endsWith('.pdf') ? 'application/pdf' : 'text/markdown',
        fileSizeBytes: 980000,
        status: 'Active',
        totalChunks: 14,
        tenantId: tenantId,
        permissions: permissions.split(',').map((p) => p.trim()),
        loaderEngine: loaderEngine,
        chunkerStrategy: chunkerStrategy,
        createdAt: new Date().toISOString(),
        sha256: '9f3e478a89bc21374092...'
      };
      setDocs([newDoc, ...docs]);
      setSelectedDocId(newDoc.id);
      setIsIngesting(false);
      setUploadFileName('');
      setTimeout(() => setIngestStep(null), 3000);
    }, 2200);
  };

  const handleRunSearch = () => {
    setSearchHits(hits);
  };

  return (
    <div className="p-6 bg-slate-950 text-slate-100 font-sans space-y-6 min-h-[calc(100vh-100px)]">
      {/* Top Banner & Schema Info */}
      <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 flex flex-wrap items-center justify-between gap-4 shadow-xl backdrop-blur-sm">
        <div className="flex items-center space-x-3">
          <div className="p-3 rounded-xl bg-indigo-500/10 border border-indigo-500/30 text-indigo-400">
            <Layers className="w-6 h-6" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h2 className="font-bold text-base text-slate-100">
                PostgreSQL 专属 Schema: <span className="font-mono text-indigo-400">zyblw_agent_knowledge</span>
              </h2>
              <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-xs px-2.5 py-0.5 rounded-full font-bold">
                Active 向量库 V1
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              独立的 Flyway 迁移历史 • public.vector(1536) • 完整 DocumentStructure 结构谱系保留
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-6 text-xs font-mono">
          <div>
            <span className="text-slate-500 block text-[10px]">已摄入文档总数</span>
            <span className="font-bold text-slate-200">{docs.length} 份文档</span>
          </div>
          <div className="h-6 w-px bg-slate-800" />
          <div>
            <span className="text-slate-500 block text-[10px]">ACTIVE CHUNKS 总数</span>
            <span className="font-bold text-indigo-300">1,480 个 Chunk 节点</span>
          </div>
          <div className="h-6 w-px bg-slate-800" />
          <div>
            <span className="text-slate-500 block text-[10px]">向量维度契约</span>
            <span className="font-bold text-cyan-300">1536 维 Float32</span>
          </div>
        </div>
      </div>

      {/* Grid: Document Upload Studio & Ingested List */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Document Upload & Parsing Studio (5 Cols) */}
        <div className="lg:col-span-5 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 space-y-4 shadow-xl backdrop-blur-sm">
          <h3 className="font-bold text-sm text-slate-200 border-b border-slate-800 pb-3 flex items-center gap-2">
            <UploadCloud className="w-4 h-4 text-indigo-400" />
            多源文档一键导入与向量化测试
          </h3>

          {/* File Input Mock */}
          <div className="border-2 border-dashed border-slate-800 hover:border-indigo-500/50 rounded-xl p-4 text-center cursor-pointer transition-all bg-slate-950/40">
            <FolderPlus className="w-8 h-8 text-indigo-400 mx-auto mb-2 opacity-80" />
            <input
              type="text"
              value={uploadFileName}
              onChange={(e) => setUploadFileName(e.target.value)}
              placeholder="输入文件名 (如：业务规范说明书.pdf)..."
              className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-sans focus:outline-none focus:border-indigo-500 text-center mb-1"
            />
            <span className="text-[11px] text-slate-500 block">
              支持 PDF、Markdown (.md)、Word (.docx)、TXT 格式
            </span>
          </div>

          {/* Options: Loader Engine & Chunker Strategy */}
          <div className="grid grid-cols-2 gap-3 text-xs">
            <div>
              <label className="text-[10px] font-mono text-slate-400 block mb-1">
                解析引擎 (Loader Engine)
              </label>
              <select
                value={loaderEngine}
                onChange={(e) => setLoaderEngine(e.target.value as any)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-2.5 py-1.5 text-slate-200 font-mono focus:outline-none"
              >
                <option value="Docling">Docling (无损 JSON + PDF BBox)</option>
                <option value="Apache Tika">Apache Tika (文本解析)</option>
              </select>
            </div>

            <div>
              <label className="text-[10px] font-mono text-slate-400 block mb-1">
                切分策略 (Chunker Strategy)
              </label>
              <select
                value={chunkerStrategy}
                onChange={(e) => setChunkerStrategy(e.target.value as any)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-2.5 py-1.5 text-slate-200 font-mono focus:outline-none"
              >
                <option value="DocumentStructureChunker">结构切分 (Structure Lineage)</option>
                <option value="SlidingWindowChunker">滑动窗口 (Sliding Window)</option>
              </select>
            </div>
          </div>

          {/* Tenant & Permissions */}
          <div className="grid grid-cols-2 gap-3 text-xs">
            <div>
              <label className="text-[10px] font-mono text-slate-400 block mb-1">
                租户归属 (Tenant ID)
              </label>
              <input
                type="text"
                value={tenantId}
                onChange={(e) => setTenantId(e.target.value)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-slate-200 font-mono focus:outline-none"
              />
            </div>

            <div>
              <label className="text-[10px] font-mono text-slate-400 block mb-1">
                授权集合 (ACL Permissions)
              </label>
              <input
                type="text"
                value={permissions}
                onChange={(e) => setPermissions(e.target.value)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-slate-200 font-mono focus:outline-none"
              />
            </div>
          </div>

          <button
            onClick={handleSimulateUpload}
            disabled={isIngesting}
            className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white font-semibold py-2 rounded-xl text-xs flex items-center justify-center space-x-2 shadow-lg shadow-indigo-600/20 transition-all"
          >
            {isIngesting ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            <span>{isIngesting ? '正在执行向量摄入流水线...' : '开始解析并摄入向量库'}</span>
          </button>

          {ingestStep && (
            <div className="p-3 bg-indigo-500/10 border border-indigo-500/30 rounded-xl text-xs text-indigo-300 font-mono flex items-center space-x-2">
              <Sparkles className="w-4 h-4 text-indigo-400 animate-pulse" />
              <span>{ingestStep}</span>
            </div>
          )}
        </div>

        {/* Ingested Documents List Table (7 Cols) */}
        <div className="lg:col-span-7 bg-slate-900/80 rounded-2xl border border-slate-800 p-5 space-y-3 shadow-xl backdrop-blur-sm">
          <div className="pb-3 border-b border-slate-800 flex items-center justify-between">
            <h3 className="font-bold text-sm text-slate-200 flex items-center gap-2">
              <FileText className="w-4 h-4 text-cyan-400" />
              知识库文档清单与发布状态
            </h3>
            <span className="text-[10px] text-slate-500 font-mono">
              KnowledgeIndexStore Metadata
            </span>
          </div>

          <div className="space-y-2.5 overflow-y-auto max-h-[280px] pr-1 custom-scrollbar">
            {docs.map((doc) => (
              <div
                key={doc.id}
                onClick={() => setSelectedDocId(doc.id)}
                className={`p-3.5 rounded-xl border cursor-pointer transition-all ${
                  doc.id === selectedDocId
                    ? 'bg-slate-800 border-indigo-500 shadow-md shadow-indigo-500/10'
                    : 'bg-slate-950/60 border-slate-800/80 hover:border-slate-700'
                }`}
              >
                <div className="flex items-center justify-between mb-1">
                  <span className="font-semibold text-xs text-slate-200 flex items-center gap-1.5">
                    <FileText className="w-3.5 h-3.5 text-indigo-400" />
                    {doc.fileName}
                  </span>
                  <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-[10px] px-2 py-0.5 rounded-full font-bold">
                    {doc.status}
                  </span>
                </div>

                <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono mt-2 pt-2 border-t border-slate-900">
                  <span>Engine: {doc.loaderEngine}</span>
                  <span>Strategy: {doc.chunkerStrategy}</span>
                  <span className="text-indigo-300 font-bold">{doc.totalChunks} Chunks</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Grid: PDF Visual Overlay vs DocumentStructure Blocks Tree */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* PDF Canvas Overlay (6 Cols) */}
        <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm overflow-hidden">
          <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
            <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200 flex items-center gap-1.5">
              <FileText className="w-4 h-4 text-indigo-400" />
              PDF 原文 Canvas 渲染与 BoundingBox 矩形高亮
            </h3>
            <span className="text-[10px] bg-slate-800 px-2 py-0.5 rounded font-mono text-slate-400">
              第 {selectedBlock?.origins[0]?.pageNumber || 1} 页
            </span>
          </div>

          <div className="flex-1 bg-slate-950 rounded-xl border border-slate-800 p-6 flex flex-col items-center justify-start overflow-y-auto relative custom-scrollbar min-h-[380px]">
            <div className="w-[440px] h-[520px] bg-slate-900 border border-slate-800 rounded-lg p-6 relative shadow-2xl overflow-hidden text-slate-300">
              <div className="text-[10px] text-slate-500 border-b border-slate-800 pb-2 mb-4 font-mono flex justify-between">
                <span>CONFIDENTIAL TECHNICAL SPECIFICATION</span>
                <span>zyblw-agent v0.4.0</span>
              </div>

              <div className="space-y-3 text-xs font-sans">
                <h2 className="text-sm font-bold text-slate-100 border-b border-slate-800 pb-1">
                  zyblw-agent 0.4.0 架构与 RAG 谱系规范
                </h2>
                <h3 className="text-xs font-bold text-indigo-300 mt-2">
                  1. 结构化切分与 pgvector 隔离
                </h3>
                <p className="text-[11px] text-slate-300 leading-relaxed">
                  DocumentStructureChunker 优先按文档层级结构 block 切分，合并同标题同父级的相邻小块，并在数据库中保留 zyblw_agent_knowledge 专属 Schema 隔离。
                </p>
                <div className="bg-slate-950 p-2 rounded border border-slate-800 text-[10px] font-mono">
                  <p>| 模块名 | 数据库 Schema | 维数 |</p>
                  <p>| Agent Core | public (default) | N/A |</p>
                  <p>| Agent Knowledge | zyblw_agent_knowledge | 1536 |</p>
                </div>
              </div>

              {/* Highlight Box Overlay */}
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

        {/* DocumentStructure Blocks Tree & Lineage (6 Cols) */}
        <div className="lg:col-span-6 bg-slate-900/80 rounded-2xl border border-slate-800 p-4 flex flex-col shadow-xl backdrop-blur-sm overflow-hidden">
          <div className="pb-3 border-b border-slate-800 mb-3 flex items-center justify-between">
            <h3 className="font-bold text-xs uppercase tracking-wider text-slate-200 flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-cyan-400" />
              DocumentStructure Block 结构树
            </h3>
            <span className="text-[10px] text-slate-500 font-mono">Docling Lineage Projection</span>
          </div>

          <div className="flex-1 overflow-y-auto space-y-3 pr-1 mb-4 custom-scrollbar max-h-[360px]">
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
                </div>
              );
            })}
          </div>

          {/* Hybrid Retrieval Playground */}
          <div className="border-t border-slate-800 pt-4">
            <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider mb-2.5 flex items-center gap-1.5">
              <Search className="w-3.5 h-3.5 text-indigo-400" />
              Hybrid 混合检索与 ACL 测试沙盒 (Vector + FTS + Reranker)
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
                <span>执行检索</span>
              </button>
            </div>

            <div className="flex items-center space-x-4 text-[11px] text-slate-400 font-mono mb-2">
              <span className="flex items-center gap-1">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
                租户隔离: {tenantId}
              </span>
              <span className="flex items-center gap-1">
                <Filter className="w-3.5 h-3.5 text-indigo-400" />
                ACL 权限: {permissions}
              </span>
            </div>

            {searchHits.length > 0 && (
              <div className="bg-slate-950 p-3 rounded-xl border border-slate-800/80 space-y-1 text-xs">
                <div className="flex justify-between items-center text-[11px] font-mono text-emerald-400 font-semibold">
                  <span>命中的 Chunk: {searchHits[0].chunk.id}</span>
                  <span>综合得分: {searchHits[0].score}</span>
                </div>
                <div className="text-[10px] font-mono text-slate-400 flex space-x-3">
                  <span>向量余弦: {searchHits[0].signals.vectorScore}</span>
                  <span>FTS 全文: {searchHits[0].signals.textScore}</span>
                  <span>RRF 名次: #{searchHits[0].signals.hybridRrfRank}</span>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
